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

@SuppressLint("UnsafeOptInUsageError")
@Serializable
data class TelematicsData(
    val eventType: String,
    val timestamp: Long,
    val tripId: String,
    val label: Int,
    val accX: Float? = null,
    val accY: Float? = null,
    val accZ: Float? = null,
    val gyroX: Float? = null,
    val gyroY: Float? = null,
    val gyroZ: Float? = null,
    val accMag: Float? = null,
    val gyroMag: Float? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val speed: Float? = null,
    val altitude: Double? = null
)

class DataCollectionService : Service(), SensorEventListener {

    private var mqttClient: MqttClient? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO)
    private var currentStats = RealTimeStats()

    private lateinit var sensorManager: SensorManager
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private val tripId = UUID.randomUUID().toString()

    private var lastAccelPublishTime: Long = 0L
    private var lastGyroPublishTime: Long = 0L
    private val lastAccelData = FloatArray(3)
    private val lastGyroData = FloatArray(3)
    private var currentLabel: Int = 0

    companion object {
        const val TAG = "DataCollectionService"
        private const val PUBLISH_INTERVAL_MS = 100L
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_CHANNEL_ID = "DataCollectionChannel"
        private const val ACCEL_THRESHOLD = 0.5f
        private const val GYRO_THRESHOLD = 0.1f
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_CHANGE_LABEL = "ACTION_CHANGE_LABEL"
        const val EXTRA_LABEL = "EXTRA_LABEL"
    }

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
                serviceScope.launch {
                    initializeAndConnectMqtt()
                }
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
        Log.d(TAG, "Loaded MQTT Config: ${config.brokerUrl}")
        mqttClient = MqttClient(applicationContext, config)

        mqttClient?.connect(
            onSuccess = {
                Log.d(TAG, "MQTT connected, starting listeners and reporting status.")
                startSensorListeners()
                startLocationUpdates()
                // --- THE KEY CHANGE: REPORT STATUS *AFTER* SUCCESSFUL CONNECTION ---
                serviceScope.launch {
                    DataRepository.setServiceRunning(true)
                }
            },
            onFailure = {
                Log.e(TAG, "MQTT connection failed. Stopping service.", it)
                stopSelf()
            }
        )
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
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .build()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    }

    private fun startSensorListeners() {
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        sensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_GAME)
        sensorManager.registerListener(this, gyroscope, SensorManager.SENSOR_DELAY_GAME)
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000)
            .setWaitForAccurateLocation(false)
            .setMinUpdateIntervalMillis(500)
            .build()
        fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, Looper.getMainLooper())
    }

    @SuppressLint("MissingPermission")
    private fun setupLocation() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    currentStats = currentStats.copy(
                        speed = location.speed,
                        latitude = location.latitude,
                        longitude = location.longitude
                    )
                    serviceScope.launch { DataRepository.updateStats(currentStats) }
                    val data = TelematicsData(
                        eventType = "gps",
                        timestamp = System.currentTimeMillis(),
                        tripId = tripId,
                        label = this@DataCollectionService.currentLabel,
                        latitude = location.latitude,
                        longitude = location.longitude,
                        speed = location.speed,
                        altitude = location.altitude
                    )
                    publishData(data)
                }
            }
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val accMag = kotlin.math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                currentStats = currentStats.copy(accMag = accMag)
                serviceScope.launch { DataRepository.updateStats(currentStats) }

                if (hasAccelChanged(event.values) && (System.currentTimeMillis() - lastAccelPublishTime > PUBLISH_INTERVAL_MS)) {
                    lastAccelPublishTime = System.currentTimeMillis()
                    System.arraycopy(event.values, 0, lastAccelData, 0, 3)
                    val data = TelematicsData(
                        eventType = "accelerometer",
                        timestamp = lastAccelPublishTime,
                        tripId = tripId,
                        label = this.currentLabel,
                        accX = event.values[0],
                        accY = event.values[1],
                        accZ = event.values[2],
                        accMag = currentStats.accMag
                    )
                    publishData(data)
                }
            }
            Sensor.TYPE_GYROSCOPE -> {
                val gyroMag = kotlin.math.sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
                currentStats = currentStats.copy(gyroMag = gyroMag)
                serviceScope.launch { DataRepository.updateStats(currentStats) }

                if (hasGyroChanged(event.values) && (System.currentTimeMillis() - lastGyroPublishTime > PUBLISH_INTERVAL_MS)) {
                    lastGyroPublishTime = System.currentTimeMillis()
                    System.arraycopy(event.values, 0, lastGyroData, 0, 3)
                    val data = TelematicsData(
                        eventType = "gyroscope",
                        timestamp = lastGyroPublishTime,
                        tripId = tripId,
                        label = this.currentLabel,
                        gyroX = event.values[0],
                        gyroY = event.values[1],
                        gyroZ = event.values[2],
                        gyroMag = currentStats.gyroMag
                    )
                    publishData(data)
                }
            }
        }
    }

    private fun hasAccelChanged(newValues: FloatArray): Boolean {
        val deltaX = Math.abs(lastAccelData[0] - newValues[0])
        val deltaY = Math.abs(lastAccelData[1] - newValues[1])
        val deltaZ = Math.abs(lastAccelData[2] - newValues[2])
        return deltaX > ACCEL_THRESHOLD || deltaY > ACCEL_THRESHOLD || deltaZ > ACCEL_THRESHOLD
    }

    private fun hasGyroChanged(newValues: FloatArray): Boolean {
        val deltaX = Math.abs(lastGyroData[0] - newValues[0])
        val deltaY = Math.abs(lastGyroData[1] - newValues[1])
        val deltaZ = Math.abs(lastGyroData[2] - newValues[2])
        return deltaX > GYRO_THRESHOLD || deltaY > GYRO_THRESHOLD || deltaZ > GYRO_THRESHOLD
    }

    @OptIn(InternalSerializationApi::class)
    private fun publishData(data: TelematicsData) {
        mqttClient?.let { client ->
            val jsonPayload = Json.encodeToString(data)
            client.publish(jsonPayload)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy.")
        serviceScope.launch {
            DataRepository.setServiceRunning(false)
        }
        sensorManager.unregisterListener(this)
        fusedLocationClient.removeLocationUpdates(locationCallback)
        mqttClient?.disconnect()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}