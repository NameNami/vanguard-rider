import asyncio
import json
import csv
import os
import aiomqtt
from aiohttp import web
import sys
import time

# --- CONFIGURATION ---
MQTT_BROKER_URL = "ba70606db22d4a5db773598694423e08.s1.eu.hivemq.cloud"
MQTT_PORT = 8883
MQTT_TOPIC = "telematics/data"

# --- IMPORTANT: REPLACE WITH YOUR PERMANENT CREDENTIALS ---
# Use the credentials from the "Access Management" section of your HiveMQ dashboard.
MQTT_USERNAME = "hivemq.webclient.1756177628468"  # Replace with your actual username
MQTT_PASSWORD = "FwPxQGU6&,d>rL.95s1n"

WEB_SERVER_PORT = 8080
CSV_FILENAME = "telematics_dataset.csv"
DEVICE_TIMEOUT_SECONDS = 5.0  # Seconds of no data before device is "Disconnected"

CSV_HEADER = [
    "timestamp", "tripId", "label", "accX", "accY", "accZ", "accMag",
    "gyroX", "gyroY", "gyroZ", "gyroMag", "rotVecX", "rotVecY", "rotVecZ", "rotVecW",
    "latitude", "longitude", "speed", "altitude"
]

# --- SHARED STATE ---
# This holds the timestamp of the last message received from the phone
last_message_time = {"time": time.time()}


# --- SSE HANDLER (Sends data to the browser) ---
async def sse_handler(request):
    headers = {
        'Content-Type': 'text/event-stream',
        'Cache-Control': 'no-cache',
        'Connection': 'keep-alive',
        'Access-Control-Allow-Origin': '*'
    }
    resp = web.StreamResponse(headers=headers)
    await resp.prepare(request)
    print("✅ BROWSER CONNECTED to SSE endpoint.")

    # Each browser gets its own queue to receive messages
    client_queue = asyncio.Queue()
    request.app['clients'].add(client_queue)

    try:
        while True:
            # Wait for either a data message or a status message from the other tasks
            event_type, data = await client_queue.get()

            # Format the data in the required SSE format with a custom event name
            sse_message = f"event: {event_type}\ndata: {json.dumps(data)}\n\n"
            await resp.write(sse_message.encode('utf-8'))

            client_queue.task_done()
    except asyncio.CancelledError:
        print("❗️ Browser disconnected.")
    finally:
        # Clean up by removing the client's queue
        request.app['clients'].remove(client_queue)

    return resp


# --- MQTT MESSAGE HANDLING (Receives data from the phone) ---
async def handle_mqtt_messages(client, app_state):
    print("MQTT Message handler started.")
    async for message in client.messages:
        try:
            payload = json.loads(message.payload.decode())
            print(f"Received from MQTT: Label {payload.get('label', '')}")

            # Update the last message time to now
            last_message_time["time"] = time.time()

            # Write data to the CSV file
            with open(CSV_FILENAME, mode='a', newline='') as file:
                writer = csv.DictWriter(file, fieldnames=CSV_HEADER)
                row_to_write = {key: payload.get(key) for key in CSV_HEADER}
                writer.writerow(row_to_write)

            # Put the data into the queue for ALL connected browser clients
            for q in app_state['clients']:
                await q.put(('telematics-update', payload))

        except Exception as e:
            print(f"Error processing message: {e}")


# --- DEVICE CONNECTION MONITOR (Checks for phone connection timeout) ---
async def monitor_device_connection(app_state):
    current_status = "Unknown"
    while True:
        await asyncio.sleep(2)  # Check every 2 seconds

        time_since_last_message = time.time() - last_message_time["time"]

        new_status = "Device Connected" if time_since_last_message <= DEVICE_TIMEOUT_SECONDS else "Device Disconnected"

        if new_status != current_status:
            current_status = new_status
            print(f"DEVICE STATUS CHANGE: {current_status}")
            # Send the new status to ALL connected browser clients
            for q in app_state['clients']:
                await q.put(('status-update', {"deviceStatus": current_status}))


# --- MQTT CLIENT (Manages connection to HiveMQ) ---
async def main_mqtt_client(app_state):
    while True:
        try:
            async with aiomqtt.Client(
                    hostname=MQTT_BROKER_URL, port=MQTT_PORT,
                    username=MQTT_USERNAME, password=MQTT_PASSWORD,
                    tls_params=aiomqtt.TLSParameters()
            ) as client:
                print(f"✅ Connected to HiveMQ Broker!")
                await client.subscribe(MQTT_TOPIC)
                print(f"✅ Subscribed to topic: {MQTT_TOPIC}")
                await handle_mqtt_messages(client, app_state)
        except aiomqtt.MqttError as e:
            print(f"❌ MQTT connection error: {e}. Reconnecting in 5 seconds...")
            await asyncio.sleep(5)


def setup_csv():
    if not os.path.isfile(CSV_FILENAME):
        with open(CSV_FILENAME, mode='w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(CSV_HEADER)
        print(f"Created new CSV file: {CSV_FILENAME}")


# --- MAIN EXECUTION BLOCK ---
if __name__ == '__main__':
    # Fix for asyncio on Windows
    if sys.platform == 'win32':
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

    setup_csv()

    # Create the web application
    app = web.Application()
    app['clients'] = set()  # A set to hold all connected browser clients
    app.router.add_get('/sse', sse_handler)


    async def run_all():
        # Start MQTT client and Device Monitor as background tasks
        mqtt_task = asyncio.create_task(main_mqtt_client(app))
        monitor_task = asyncio.create_task(monitor_device_connection(app))

        # Start the web server
        runner = web.AppRunner(app)
        await runner.setup()
        site = web.TCPSite(runner, 'localhost', WEB_SERVER_PORT)
        await site.start()
        print(f"✅ Dashboard server running on http://localhost:{WEB_SERVER_PORT}")

        # Run both tasks forever
        await asyncio.gather(mqtt_task, monitor_task)


    try:
        asyncio.run(run_all())
    except KeyboardInterrupt:
        print("\nShutting down.")