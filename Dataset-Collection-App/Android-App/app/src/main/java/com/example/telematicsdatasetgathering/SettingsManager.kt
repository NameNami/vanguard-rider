// file: com/example/telematicsdatasetgathering/SettingsManager.kt

package com.example.telematicsdatasetgathering

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// Define a DataStore instance at the top level
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

// Data class to hold the broker configuration
data class MqttConfig(
    val brokerUrl: String,
    val username: String,
    val password: String
)

class SettingsManager(context: Context) {

    private val dataStore = context.dataStore

    companion object {
        // Define keys for each setting
        val BROKER_URL_KEY = stringPreferencesKey("mqtt_broker_url")
        val USERNAME_KEY = stringPreferencesKey("mqtt_username")
        val PASSWORD_KEY = stringPreferencesKey("mqtt_password")
    }

    // A Flow that emits the latest MqttConfig whenever a setting changes
    val mqttConfigFlow: Flow<MqttConfig> = dataStore.data
        .map { preferences ->
            val brokerUrl = preferences[BROKER_URL_KEY] ?: "tcp://192.168.1.10:1883" // Default value
            val username = preferences[USERNAME_KEY] ?: ""
            val password = preferences[PASSWORD_KEY] ?: ""
            MqttConfig(brokerUrl, username, password)
        }

    // A suspend function to save the new settings
    suspend fun saveMqttConfig(config: MqttConfig) {
        dataStore.edit { settings ->
            settings[BROKER_URL_KEY] = config.brokerUrl
            settings[USERNAME_KEY] = config.username
            settings[PASSWORD_KEY] = config.password
        }
    }
}