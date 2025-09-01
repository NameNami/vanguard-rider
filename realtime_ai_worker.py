# file: backend/realtime_ai_worker.py
import asyncio
import json
import csv
import os
import aiomqtt
from aiohttp import web, ClientSession
import sys
import time
import logging
import numpy as np
import joblib
import pandas as pd
from tensorflow.keras.models import load_model
from collections import deque

# --- 1. SETUP LOGGING ---
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - %(message)s')

# --- 2. CONFIGURATION CLASS ---
class Config:
    # --- MQTT and Web Server Settings ---
    MQTT_BROKER_URL = "ba70606db22d4a5db773598694423e08.s1.eu.hivemq.cloud"
    MQTT_PORT = 8883
    MQTT_TOPIC = "telematics/data"
    MQTT_USERNAME = "hivemq.webclient.1756177628468" # <-- IMPORTANT
    MQTT_PASSWORD = "FwPxQGU6&,d>rL.95s1n" # <-- IMPORTANT
    WEB_SERVER_PORT = 8080
    
    # --- Data Logging and Trip Management ---
    CSV_FILENAME = "telematics_dataset_live.csv"
    DEVICE_TIMEOUT_SECONDS = 5.0
    TRIP_INACTIVITY_TIMEOUT_SECONDS = 60.0
    CSV_HEADER = [
        "timestamp", "tripId", "label", "accX", "accY", "accZ", "accMag",
        "gyroX", "gyroY", "gyroZ", "gyroMag", "rotVecX", "rotVecY", "rotVecZ", "rotVecW",
        "latitude", "longitude", "speed", "altitude"
    ]
    MODEL_PATH = "best_lstm_model.h5"
    SCALER_PATH = "feature_scaler.pkl"
    ENCODER_PATH = "label_encoder.pkl"
    SEQUENCE_LENGTH = 40
    FEATURES = [
        "accX", "accY", "accZ", "accMag",
        "gyroX", "gyroY", "gyroZ", "gyroMag",
        "rotVecX", "rotVecY", "rotVecZ", "rotVecW"
    ]
    SPEED_CHECK_INTERVAL_SECONDS = 10 
    NOMINATIM_API_URL = "https://nominatim.openstreetmap.org/reverse"
    REQUEST_HEADERS = {
        'User-Agent': 'VanguardRider/1.0 (najmi.sanusi@s.unikl.edu.my)' # <-- IMPORTANT: UPDATE THIS
    }

# --- All classes and functions (get_road_data, InferenceEngine, DashboardServer) ---
# --- are exactly the same. No changes are needed in the application logic.     ---
async def get_road_data(session, lat, lon):
    params = {'format': 'json', 'lat': lat, 'lon': lon, 'zoom': 18, 'extratags': 1}
    try:
        async with session.get(Config.NOMINATIM_API_URL, params=params, headers=Config.REQUEST_HEADERS) as response:
            if response.status == 200:
                data = await response.json()
                speed_limit_str = data.get('extratags', {}).get('maxspeed')
                snapped_lat = float(data.get('lat', lat)); snapped_lon = float(data.get('lon', lon))
                speed_limit = int(speed_limit_str) if speed_limit_str and speed_limit_str.isdigit() else None
                return speed_limit, snapped_lat, snapped_lon
            else: logging.warning(f"Nominatim API request failed: {response.status}")
    except Exception as e: logging.error(f"Error calling Nominatim API: {e}")
    return None, lat, lon

class InferenceEngine:
    def __init__(self, config):
        self.config = config; self.model = None; self.scaler = None; self.encoder = None; self.load_artifacts()
    def load_artifacts(self):
        try:
            logging.info("Loading AI artifacts...")
            self.model = load_model(self.config.MODEL_PATH); self.scaler = joblib.load(self.config.SCALER_PATH); self.encoder = joblib.load(self.config.ENCODER_PATH)
            logging.info("Warming up the model..."); self.model.predict(np.zeros((1, self.config.SEQUENCE_LENGTH, len(self.config.FEATURES))))
            logging.info("AI artifacts loaded and model is warmed up.")
        except Exception as e: logging.error(f"Fatal: Could not load AI artifacts. Error: {e}"); sys.exit(1)
    def predict(self, sequence_data):
        if len(sequence_data) < self.config.SEQUENCE_LENGTH: return "Buffering..."
        sequence_df = pd.DataFrame(data=sequence_data, columns=self.config.FEATURES)
        model_input = np.expand_dims(self.scaler.transform(sequence_df), axis=0)
        prediction_probs = self.model.predict(model_input)[0]
        predicted_class_index = np.argmax(prediction_probs)
        return str(self.encoder.inverse_transform([predicted_class_index])[0])

class DashboardServer: # Condensed for brevity - this class is unchanged
    def __init__(self, config, engine, http_session):
        self.config=config; self.engine=engine; self.http_session=http_session; self.trip_buffers={}; self.last_message_time=0
        self.app=web.Application(); self.app['clients']=set(); self.speed_check_states={}; self.setup_routes()
    def setup_routes(self): self.app.router.add_get('/sse', self.sse_handler)
    async def sse_handler(self, request):
        headers={'Content-Type':'text/event-stream','Cache-Control':'no-cache','Connection':'keep-alive','Access-Control-Allow-Origin':'*'}
        resp=web.StreamResponse(headers=headers); await resp.prepare(request)
        logging.info("Dashboard UI connected via SSE.")
        client_queue=asyncio.Queue(); self.app['clients'].add(client_queue)
        try:
            initial_status="Device Connected" if time.time()-self.last_message_time<=self.config.DEVICE_TIMEOUT_SECONDS else "Device Disconnected"
            await resp.write(f"event: status-update\ndata: {json.dumps({'deviceStatus': initial_status})}\n\n".encode('utf-8'))
            while True:
                event_type, data = await client_queue.get()
                await resp.write(f"event: {event_type}\ndata: {json.dumps(data)}\n\n".encode('utf-8')); client_queue.task_done()
        except(asyncio.CancelledError, ConnectionResetError): logging.warning("Browser connection closed.")
        finally: self.app['clients'].remove(client_queue)
        return resp
    async def broadcast_to_clients(self, event_type, data):
        for q in self.app['clients']: await q.put((event_type, data))
    async def handle_mqtt_messages(self, client):
        logging.info("MQTT Message handler started.")
        async for message in client.messages:
            try:
                payload=json.loads(message.payload.decode()); trip_id=payload.get("tripId")
                if not trip_id: continue
                if trip_id not in self.trip_buffers:
                    logging.info(f"New trip detected: {trip_id}"); self.trip_buffers[trip_id]={"data": deque(maxlen=self.config.SEQUENCE_LENGTH)}; self.speed_check_states[trip_id]={"last_check_time": 0, "speed_limit": None}
                self.trip_buffers[trip_id]["last_seen"]=time.time(); self.last_message_time=time.time()
                current_features=[payload.get(f) for f in self.config.FEATURES]; self.trip_buffers[trip_id]["data"].append(current_features)
                payload['prediction']=self.engine.predict(self.trip_buffers[trip_id]["data"])
                now=time.time(); trip_speed_state=self.speed_check_states[trip_id]
                if now - trip_speed_state["last_check_time"] > self.config.SPEED_CHECK_INTERVAL_SECONDS:
                    trip_speed_state["last_check_time"]=now
                    limit, s_lat, s_lon=await get_road_data(self.http_session, payload['latitude'], payload['longitude'])
                    trip_speed_state["speed_limit"]=limit; payload['snapped_latitude']=s_lat; payload['snapped_longitude']=s_lon
                else: payload['snapped_latitude']=payload['latitude']; payload['snapped_longitude']=payload['longitude']
                current_speed_kmh=payload['speed']*3.6; speed_limit=trip_speed_state["speed_limit"]
                if speed_limit is not None:
                    if current_speed_kmh > speed_limit + 5: payload['alert']=f"SPEEDING! {int(current_speed_kmh)} km/h in a {speed_limit} km/h zone."
                    else: payload['alert']=f"OK. Limit: {speed_limit} km/h"
                else: payload['alert']="Speed limit unknown."
                with open(self.config.CSV_FILENAME, mode='a', newline='') as file:
                    writer=csv.DictWriter(file, fieldnames=self.config.CSV_HEADER); writer.writerow({key: payload.get(key) for key in self.config.CSV_HEADER})
                await self.broadcast_to_clients('telematics-update', payload)
            except Exception as e: logging.error(f"Error processing message: {e}", exc_info=True)
    async def broadcast_active_trips(self):
        while True: await asyncio.sleep(3); await self.broadcast_to_clients('trip-list-update', {"activeTrips": list(self.trip_buffers.keys())})
    async def cleanup_inactive_trips(self):
        while True:
            await asyncio.sleep(30); stale_trips=[tid for tid, buf in list(self.trip_buffers.items()) if time.time()-buf.get("last_seen",0)>self.config.TRIP_INACTIVITY_TIMEOUT_SECONDS]
            if stale_trips:
                logging.info(f"Cleaning up stale trips: {stale_trips}")
                for trip_id in stale_trips: self.trip_buffers.pop(trip_id, None); self.speed_check_states.pop(trip_id, None)
    async def monitor_device_connection(self):
        current_status="Unknown"
        while True:
            await asyncio.sleep(2)
            new_status="Device Connected" if time.time()-self.last_message_time<=self.config.DEVICE_TIMEOUT_SECONDS else "Device Disconnected"
            if new_status != current_status: current_status=new_status; logging.info(f"DEVICE STATUS CHANGE: {current_status}"); await self.broadcast_to_clients('status-update', {"deviceStatus": new_status})
    async def run(self):
        runner=web.AppRunner(self.app); await runner.setup()
        site=web.TCPSite(runner, 'localhost', self.config.WEB_SERVER_PORT); await site.start()
        logging.info(f"Web server running on http://localhost:{self.config.WEB_SERVER_PORT}")
    async def main_mqtt_client(self):
        while True:
            try:
                async with aiomqtt.Client(hostname=self.config.MQTT_BROKER_URL, port=self.config.MQTT_PORT, username=self.config.MQTT_USERNAME, password=self.config.MQTT_PASSWORD, tls_params=aiomqtt.TLSParameters()) as client:
                    logging.info("Successfully connected to HiveMQ Broker."); await client.subscribe(self.config.MQTT_TOPIC); await self.handle_mqtt_messages(client)
            except aiomqtt.MqttError as error: logging.error(f'MQTT error: "{error}". Reconnecting in 5 seconds.'); await asyncio.sleep(5)
def setup_csv_file(config):
    if not os.path.exists(config.CSV_FILENAME):
        with open(config.CSV_FILENAME, mode='w', newline='') as file: writer=csv.writer(file); writer.writerow(config.CSV_HEADER)
        logging.info(f"Created CSV file: {config.CSV_FILENAME}")

# --- 6. SCRIPT EXECUTION BLOCK (REVISED AND MORE ROBUST) ---

# Define the main asynchronous logic in its own function
async def run_application():
    config = Config()
    setup_csv_file(config)
    
    # Use a single, shared ClientSession for all API calls
    async with ClientSession() as http_session:
        inference_engine = InferenceEngine(config)
        server = DashboardServer(config, inference_engine, http_session)
        
        # Run all server tasks concurrently
        await asyncio.gather(
            server.main_mqtt_client(),
            server.monitor_device_connection(),
            server.cleanup_inactive_trips(),
            server.broadcast_active_trips(),
            server.run()
        )

# This is the main entry point of the script
if __name__ == '__main__':
    # --- THIS IS THE CRITICAL FIX FOR WINDOWS ---
    # Set the event loop policy *before* any asyncio code is run.
    # This ensures that the correct (compatible) event loop is used from the very beginning.
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

    try:
        # Now, run the main async function
        asyncio.run(run_application())
    except KeyboardInterrupt:
        logging.info("Server shutting down.")