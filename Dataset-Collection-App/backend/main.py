import paho.mqtt.client as mqtt
import json
import csv
import os

# --- CONFIGURE YOUR HIVEMQ CREDENTIALS HERE ---
# These MUST be the same as in your Android app's MqttClient.kt

MQTT_BROKER_URL = "ba70606db22d4a5db773598694423e08.s1.eu.hivemq.cloud"
MQTT_PORT = 8883  # Standard port for MQTT over TLS
MQTT_TOPIC = "telematics/data"

# Use the credentials from the "Access Management" section of HiveMQ Cloud
MQTT_USERNAME = "hivemq.webclient.1756177628468"  # Replace with your actual username
MQTT_PASSWORD = "FwPxQGU6&,d>rL.95s1n"  # Replace with your actual password

# The name of the CSV file where data will be saved
CSV_FILENAME = "telematics_dataset.csv"

# The header row for our CSV file
CSV_HEADER = [
    "eventType", "timestamp", "tripId", "accX", "accY", "accZ",
    "gyroX", "gyroY", "gyroZ", "latitude", "longitude", "speed", "altitude"
]

# --- SCRIPT LOGIC ---

# This function is called when the script successfully connects to the broker
def on_connect(client, userdata, flags, rc, properties=None):
    if rc == 0:
        print(f"Successfully connected to HiveMQ Broker!")
        # Subscribe to the topic once connected
        client.subscribe(MQTT_TOPIC)
        print(f"Subscribed to topic: {MQTT_TOPIC}")
    else:
        print(f"Failed to connect, return code {rc}\n")
        print("Please check your broker URL, port, username, and password.")

# This function is called every time a new message is received on the subscribed topic
def on_message(client, userdata, msg):
    try:
        # Decode the message payload from bytes to a string, then parse as JSON
        payload = json.loads(msg.payload.decode())
        print(f"Received data: {payload}")

        # Write the received data to the CSV file
        with open(CSV_FILENAME, mode='a', newline='') as file:
            writer = csv.DictWriter(file, fieldnames=CSV_HEADER)
            # Use .get() to provide a default empty value if a key is missing
            writer.writerow({
                "eventType": payload.get("eventType"),
                "timestamp": payload.get("timestamp"),
                "tripId": payload.get("tripId"),
                "accX": payload.get("accX"),
                "accY": payload.get("accY"),
                "accZ": payload.get("accZ"),
                "gyroX": payload.get("gyroX"),
                "gyroY": payload.get("gyroY"),
                "gyroZ": payload.get("gyroZ"),
                "latitude": payload.get("latitude"),
                "longitude": payload.get("longitude"),
                "speed": payload.get("speed"),
                "altitude": payload.get("altitude")
            })

    except json.JSONDecodeError:
        print(f"Error decoding JSON: {msg.payload.decode()}")
    except Exception as e:
        print(f"An error occurred: {e}")

def setup_csv():
    # Check if the CSV file already exists to avoid writing the header multiple times
    file_exists = os.path.isfile(CSV_FILENAME)
    if not file_exists:
        with open(CSV_FILENAME, mode='w', newline='') as file:
            writer = csv.writer(file)
            writer.writerow(CSV_HEADER)
        print(f"Created new CSV file: {CSV_FILENAME}")

# Main execution block
if __name__ == "__main__":
    setup_csv()

    # Create a new MQTT client
    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2)

    # Set the username and password for authentication
    client.username_pw_set(MQTT_USERNAME, MQTT_PASSWORD)

    # Enable TLS for a secure connection
    client.tls_set(tls_version=mqtt.ssl.PROTOCOL_TLS)

    # Assign the callback functions
    client.on_connect = on_connect
    client.on_message = on_message

    print("Connecting to MQTT broker...")
    # Connect to the broker
    client.connect(MQTT_BROKER_URL, MQTT_PORT)

    # Start a background loop to process messages. This is non-blocking.
    client.loop_forever()
