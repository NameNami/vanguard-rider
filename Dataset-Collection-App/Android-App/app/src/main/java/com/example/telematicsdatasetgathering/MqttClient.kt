// file: com/example/telematicsdatasetgathering/MqttClient.kt

package com.example.telematicsdatasetgathering

import android.content.Context
import android.util.Log
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import javax.net.ssl.SSLSocketFactory

class MqttClient(context: Context, private val config: MqttConfig) {
    // hivemq.webclient.1755785471926
    // Hr7m><CzT*ax6g@0BJ8A
    companion object {
        const val TAG = "MqttClient"
        private const val MQTT_TOPIC = "telematics/data"
    }

    private val clientId = org.eclipse.paho.client.mqttv3.MqttClient.generateClientId()
    private val mqttClient = MqttAndroidClient(context, config.brokerUrl, clientId)


    fun connect(onSuccess: () -> Unit, onFailure: (Throwable?) -> Unit) {
        try {
            if (mqttClient.isConnected) {
                onSuccess()
                return
            }

            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                if (config.username.isNotBlank()) {
                    userName = config.username
                }
                if (config.password.isNotBlank()) {
                    password = config.password.toCharArray()
                }

                // Automatically enable SSL if the URL starts with "ssl://"
                if (config.brokerUrl.startsWith("ssl://")) {
                    socketFactory = SSLSocketFactory.getDefault()
                }
            }

            mqttClient.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    Log.d(TAG, "MQTT Connection Complete (reconnect: $reconnect)")
                    onSuccess()
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.e(TAG, "MQTT Connection Lost: ${cause?.message}")
                }

                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    // Not used for publishing
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    // Not used for publishing
                }
            })

            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(TAG, "MQTT Connection Success")
                    // The connectComplete callback will handle the success logic
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.e(TAG, "MQTT Connection Failure: ${exception?.message}")
                    onFailure(exception)
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
            onFailure(e)
        }
    }

    fun publish(payload: String) {
        if (!mqttClient.isConnected) {
            Log.w(TAG, "MQTT client is not connected. Cannot publish.")
            return
        }
        try {
            val message = MqttMessage()
            message.payload = payload.toByteArray()
            message.qos = 1 // At least once
            mqttClient.publish(MQTT_TOPIC, message)
            Log.d(TAG, "Published message to $MQTT_TOPIC")
        } catch (e: MqttException) {
            Log.e(TAG, "Error publishing message: ${e.message}")
        }
    }

    fun disconnect() {
        if (mqttClient.isConnected) {
            mqttClient.disconnect()
            Log.d(TAG, "MQTT client disconnected.")
        }
    }
}