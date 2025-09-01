import asyncio
import json
import csv
import os
import aiomqtt
from aiohttp import web
import sys
import time
import logging

# --- 1. SETUP PROFESSIONAL LOGGING ---
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(levelname)s - %(message)s',
    datefmt='%Y-%m-%d %H:%M:%S',
)

# --- 2. CONFIGURATION CLASS ---
class Config:
    MQTT_BROKER_URL = "ba70606db22d4a5db773598694423e08.s1.eu.hivemq.cloud"
    MQTT_PORT = 8883
    MQTT_TOPIC = "telematics/data"
    MQTT_USERNAME = "hivemq.webclient.1756177628468" # <-- IMPORTANT
    MQTT_PASSWORD = "FwPxQGU6&,d>rL.95s1n" # <-- IMPORTANT
    WEB_SERVER_PORT = 8080
    CSV_FILENAME = "telematics_dataset.csv"
    DEVICE_TIMEOUT_SECONDS = 6.0
    TRIP_INACTIVITY_TIMEOUT_SECONDS = 60.0
    CSV_HEADER = [
        "timestamp", "tripId", "label", "accX", "accY", "accZ", "accMag",
        "gyroX", "gyroY", "gyroZ", "gyroMag", "rotVecX", "rotVecY", "rotVecZ", "rotVecW",
        "latitude", "longitude", "speed", "altitude"
    ]

# --- 3. MAIN SERVER CLASS ---
class DashboardServer:
    def __init__(self, config):
        self.config = config
        self.trip_buffers = {}
        self.last_message_time = 0
        self.app = web.Application()
        self.app['clients'] = set() # A set to hold all connected browser client queues
        self.setup_routes()

    def setup_routes(self):
        self.app.router.add_get('/sse', self.sse_handler)

    async def sse_handler(self, request):
        headers = {
            'Content-Type': 'text/event-stream',
            'Cache-Control': 'no-cache',
            'Connection': 'keep-alive',
            'Access-Control-Allow-Origin': '*'
        }
        resp = web.StreamResponse(headers=headers)
        await resp.prepare(request)
        logging.info("Dashboard UI connected via SSE.")
        
        client_queue = asyncio.Queue()
        self.app['clients'].add(client_queue)
        
        try:
            # Send initial status
            time_since_last = time.time() - self.last_message_time
            initial_status = "Device Connected" if time_since_last <= self.config.DEVICE_TIMEOUT_SECONDS else "Device Disconnected"
            initial_msg = f"event: status-update\ndata: {json.dumps({'deviceStatus': initial_status})}\n\n"
            await resp.write(initial_msg.encode('utf-8'))
            
            while True:
                event_type, data = await client_queue.get()
                try:
                    sse_message = f"event: {event_type}\ndata: {json.dumps(data)}\n\n"
                    await resp.write(sse_message.encode('utf-8'))
                    client_queue.task_done()
                except ConnectionResetError:
                    logging.warning("Browser connection closed by client.")
                    break
        except asyncio.CancelledError:
            logging.info("Browser connection cancelled.")
        finally:
            logging.info("Cleaning up client resources.")
            self.app['clients'].remove(client_queue)
            
        return resp

    async def broadcast_to_clients(self, event_type, data):
        for q in self.app['clients']:
            await q.put((event_type, data))

    async def handle_mqtt_messages(self, client):
        logging.info("MQTT Message handler started.")
        async for message in client.messages:
            try:
                payload = json.loads(message.payload.decode())
                trip_id = payload.get("tripId")
                if not trip_id: continue

                if trip_id not in self.trip_buffers:
                    logging.info(f"New trip detected: {trip_id}")
                self.trip_buffers[trip_id] = time.time()
                self.last_message_time = time.time()
                
                with open(self.config.CSV_FILENAME, mode='a', newline='') as file:
                    writer = csv.DictWriter(file, fieldnames=self.config.CSV_HEADER)
                    row_to_write = {key: payload.get(key) for key in self.config.CSV_HEADER}
                    writer.writerow(row_to_write)
                
                await self.broadcast_to_clients('telematics-update', payload)
            except Exception as e:
                logging.error(f"Error processing message: {e}")

    async def broadcast_active_trips(self):
        while True:
            await asyncio.sleep(3)
            await self.broadcast_to_clients('trip-list-update', {"activeTrips": list(self.trip_buffers.keys())})

    async def cleanup_inactive_trips(self):
        while True:
            await asyncio.sleep(30)
            stale_trips = [trip_id for trip_id, last_seen in list(self.trip_buffers.items())
                           if time.time() - last_seen > self.config.TRIP_INACTIVITY_TIMEOUT_SECONDS]
            if stale_trips:
                logging.info(f"Cleaning up stale trips: {stale_trips}")
                for trip_id in stale_trips:
                    if trip_id in self.trip_buffers:
                        del self.trip_buffers[trip_id]

    async def monitor_device_connection(self):
        current_status = "Unknown"
        while True:
            await asyncio.sleep(2)
            time_since_last = time.time() - self.last_message_time
            new_status = "Device Connected" if time_since_last <= self.config.DEVICE_TIMEOUT_SECONDS else "Device Disconnected"
            if new_status != current_status:
                current_status = new_status
                logging.info(f"DEVICE STATUS CHANGE: {current_status}")
                await self.broadcast_to_clients('status-update', {"deviceStatus": current_status})

    async def run(self):
        runner = web.AppRunner(self.app)
        await runner.setup()
        site = web.TCPSite(runner, 'localhost', self.config.WEB_SERVER_PORT)
        await site.start()
        logging.info(f"Dashboard server running on http://localhost:{self.config.WEB_SERVER_PORT}")
        
        asyncio.create_task(self.broadcast_active_trips())
        asyncio.create_task(self.cleanup_inactive_trips())
        asyncio.create_task(self.monitor_device_connection())
        
        await self.main_mqtt_client()

    async def main_mqtt_client(self):
        while True:
            try:
                async with aiomqtt.Client(
                    hostname=self.config.MQTT_BROKER_URL, port=self.config.MQTT_PORT,
                    username=self.config.MQTT_USERNAME, password=self.config.MQTT_PASSWORD,
                    tls_params=aiomqtt.TLSParameters()
                ) as client:
                    logging.info(f"Connected to HiveMQ Broker!")
                    await client.subscribe(self.config.MQTT_TOPIC)
                    logging.info(f"Subscribed to topic: {self.config.MQTT_TOPIC}")
                    await self.handle_mqtt_messages(client)
            except aiomqtt.MqttError as e:
                logging.error(f"MQTT connection error: {e}. Reconnecting in 5 seconds...")
                await asyncio.sleep(5)

def setup_csv(filename, header):
    if not os.path.isfile(filename):
        with open(filename, mode='w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(header)
        logging.info(f"Created new CSV file: {filename}")

# --- MAIN EXECUTION BLOCK ---
if __name__ == '__main__':
    if sys.platform == 'win32':
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    
    config = Config()
    setup_csv(config.CSV_FILENAME, config.CSV_HEADER)
    
    server = DashboardServer(config)
    
    try:
        asyncio.run(server.run())
    except KeyboardInterrupt:
        logging.info("\nShutdown signal received.")