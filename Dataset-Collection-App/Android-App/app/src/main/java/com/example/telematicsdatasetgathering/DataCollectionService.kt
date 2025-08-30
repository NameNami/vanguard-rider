// file: com/example/telematicsdatasetgathering/DataCollectionService.kt

package com.example.telematicsdatasetgathering

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.android.gms.location.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.*
import kotlin.concurrent.timerTask

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TelematicsData(
    val timestamp: Long,
    val tripId: String,
    val label: Int,
    val accX: Float,
    val accY: Float,
    val accZ: Float,
    val accMag: Float,
    val gyroX: Float,
    val gyroY: Float,
    val gyroZ: Float,
    val gyroMag: Float,
    val rotVecX: Float,
    val rotVecY: Float,
    val rotVecZ: Float,
    val rotVecW: Float,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val altitude: Double
)

class DataCollectionService : Service(), SensorEventListener {

    private var mqttClient: MqttClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var dataSendTimer: Timer? = null

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val tripId = UUID.randomUUID().toString()
    private var currentLabel: Int = 0

    // --- STATE MANAGEMENT FOR FILTERING ---
    // This object holds the most recent, live sensor values.
    private var liveSensorState = LiveSensorState()
    // This object holds the state of the LAST packet we successfully sent.
    private var lastPublishedState = LiveSensorState()
    // ---

    companion object {
        const val TAG = "DataCollectionService"
        private const val PUBLISH_INTERVAL_MS = 100L // 10 Hz
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "DataCollectionChannel"
        // Thresholds for detecting significant change
        private const val ACCEL_THRESHOLD = 0.5f
        private const val GYRO_THRESHOLD = 0.3f
        private const val ROT_VEC_THRESHOLD = 0.05f // Rotation vector is sensitive
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CHANGE_LABEL = "ACTION_CHANGE_LABEL"
        const val EXTRA_LABEL = "EXTRA_LABEL"
    }

    private data class LiveSensorState(
        var accX: Float = 0f, var accY: Float = 0f, var accZ: Float = 0f,
        var gyroX: Float = 0f, var gyroY: Float = 0f, var gyroZ: Float = 0f,
        var rotVecX: Float = 0f, var rotVecY: Float = 0f, var rotVecZ: Float = 0f, var rotVecW: Float = 1f,
        var latitude: Double = 0.0, var longitude: Double = 0.0,
        var speed: Float = 0f, var altitude: Double = 0.0
    )

    override fun onCreate() {
        super.onCreate()
        setupSensors()
        setupLocation()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Log.d(TAG, "ACTION_START received.")
                startForeground(NOTIFICATION_ID, createNotification())
                serviceScope.launch { initializeAndConnectMqtt() }
            }
            ACTION_CHANGE_LABEL -> {
                val newLabel = intent.getIntExtra(EXTRA_LABEL, 0)
                Log.d(TAG, "Changing label to: $newLabel")
                this.currentLabel = newLabel
            }
            ACTION_STOP -> {
                Log.d(TAG, "ACTION_STOP received.")
                stopSelf()
            }
        }
        return START_STICKY
    }

    private suspend fun initializeAndConnectMqtt() {
        val settingsManager = SettingsManager(applicationContext)
        val config = settingsManager.mqttConfigFlow.first()
        mqttClient = MqttClient(applicationContext, config)

        mqttClient?.connect(
            onSuccess = {
                Log.d(TAG, "MQTT connected. Starting data collection loop.")
                startSensorListeners()
                startLocationUpdates()
                startDataSendLoop()
                serviceScope.launch { DataRepository.setServiceRunning(true) }
            },
            onFailure = {
                Log.e(TAG, "MQTT connection failed.", it)
                stopSelf()
            }
        )
    }

    private fun startDataSendLoop() {
        Log.d(TAG, "Starting data send loop.")
        dataSendTimer?.cancel()
        dataSendTimer = Timer()
        dataSendTimer?.scheduleAtFixedRate(timerTask {
            sendCombinedDataPacket()
        }, 0, PUBLISH_INTERVAL_MS)
    }

    private fun sendCombinedDataPacket() {
        // --- THIS IS THE FIX: FILTERING LOGIC IS RE-INTRODUCED ---
        // Only proceed if the state has meaningfully changed since the last publish.
        if (hasStateChanged()) {
            // Update the last published state to be the current state
            lastPublishedState = liveSensorState.copy()

            val accMag = kotlin.math.sqrt(lastPublishedState.accX * lastPublishedState.accX + lastPublishedState.accY * lastPublishedState.accY + lastPublishedState.accZ * lastPublishedState.accZ)
            val gyroMag = kotlin.math.sqrt(lastPublishedState.gyroX * lastPublishedState.gyroX + lastPublishedState.gyroY * lastPublishedState.gyroY + lastPublishedState.gyroZ * lastPublishedState.gyroZ)

            val data = TelematicsData(
                timestamp = System.currentTimeMillis(),
                tripId = tripId,
                label = this.currentLabel,
                accX = lastPublishedState.accX, accY = lastPublishedState.accY, accZ = lastPublishedState.accZ, accMag = accMag,
                gyroX = lastPublishedState.gyroX, gyroY = lastPublishedState.gyroY, gyroZ = lastPublishedState.gyroZ, gyroMag = gyroMag,
                rotVecX = lastPublishedState.rotVecX, rotVecY = lastPublishedState.rotVecY, rotVecZ = lastPublishedState.rotVecZ, rotVecW = lastPublishedState.rotVecW,
                latitude = lastPublishedState.latitude, longitude = lastPublishedState.longitude,
                speed = lastPublishedState.speed, altitude = lastPublishedState.altitude
            )

            publishData(data)
        }
        // --- END OF FIX ---

        // The UI stats will still update continuously, showing the live noise.
        val liveAccMag = kotlin.math.sqrt(liveSensorState.accX * liveSensorState.accX + liveSensorState.accY * liveSensorState.accY + liveSensorState.accZ * liveSensorState.accZ)
        val liveGyroMag = kotlin.math.sqrt(liveSensorState.gyroX * liveSensorState.gyroX + liveSensorState.gyroY * liveSensorState.gyroY + liveSensorState.gyroZ * liveSensorState.gyroZ)
        val stats = RealTimeStats(liveAccMag, liveGyroMag, liveSensorState.speed, liveSensorState.latitude, liveSensorState.longitude)
        serviceScope.launch { DataRepository.updateStats(stats) }
    }

    // --- NEW HELPER FUNCTION TO COMPARE STATES ---
    private fun hasStateChanged(): Boolean {
        val accChanged = Math.abs(lastPublishedState.accX - liveSensorState.accX) > ACCEL_THRESHOLD ||
                Math.abs(lastPublishedState.accY - liveSensorState.accY) > ACCEL_THRESHOLD ||
                Math.abs(lastPublishedState.accZ - liveSensorState.accZ) > ACCEL_THRESHOLD

        val gyroChanged = Math.abs(lastPublishedState.gyroX - liveSensorState.gyroX) > GYRO_THRESHOLD ||
                Math.abs(lastPublishedState.gyroY - liveSensorState.gyroY) > GYRO_THRESHOLD ||
                Math.abs(lastPublishedState.gyroZ - liveSensorState.gyroZ) > GYRO_THRESHOLD

        val rotVecChanged = Math.abs(lastPublishedState.rotVecX - liveSensorState.rotVecX) > ROT_VEC_THRESHOLD ||
                Math.abs(lastPublishedState.rotVecY - liveSensorState.rotVecY) > ROT_VEC_THRESHOLD ||
                Math.abs(lastPublishedState.rotVecZ - liveSensorState.rotVecZ) > ROT_VEC_THRESHOLD

        // Also consider a change in GPS location as significant
        val locationChanged = lastPublishedState.latitude != liveSensorState.latitude ||
                lastPublishedState.longitude != liveSensorState.longitude

        return accChanged || gyroChanged || rotVecChanged || locationChanged
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        synchronized(liveSensorState) {
            when (event.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    liveSensorState.accX = event.values[0]
                    liveSensorState.accY = event.values[1]
                    liveSensorState.accZ = event.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    liveSensorState.gyroX = event.values[0]
                    liveSensorState.gyroY = event.values[1]
                    liveSensorState.gyroZ = event.values[2]
                }
                Sensor.TYPE_ROTATION_VECTOR -> {
                    liveSensorState.rotVecX = event.values[0]
                    liveSensorState.rotVecY = event.values[1]
                    liveSensorState.rotVecZ = event.values[2]
                    liveSensorState.rotVecW = event.values[3]
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    synchronized(liveSensorState) {
                        liveSensorState.latitude = location.latitude
                        liveSensorState.longitude = location.longitude
                        liveSensorState.speed = location.speed
                        liveSensorState.altitude = location.altitude
                    }
                }
            }
        }
    }

    @OptIn(InternalSerializationApi::class)
    private fun publishData(data: TelematicsData) {
        mqttClient?.let { client ->
            val jsonPayload = Json.encodeToString(data)
            client.publish(jsonPayload)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy.")
        dataSendTimer?.cancel() // Stop the timer
        serviceScope.launch {
            DataRepository.setServiceRunning(false)
        }
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mqttClient?.disconnect()
    }
    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Data Collection",
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Telematics Data")
            .setContentText("Collecting sensor and location data...")
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Ensure you have this drawable
            .build()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    private fun startSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val rotationVector = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, rotationVector, SensorManager.SENSOR_DELAY_GAME)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onBind(intent: Intent?): IBinder? = null
}