// file: com/example/telematicsdatasetgathering/MainActivity.kt

package com.example.telematicsdatasetgathering

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.telematicsdatasetgathering.ui.theme.TelematicsDatasetGatheringTheme
import kotlinx.coroutines.launch
import java.text.DecimalFormat

class MainActivity : ComponentActivity() {

    private val statsViewModel: StatsViewModel by viewModels()
    private lateinit var settingsManager: SettingsManager

    private val requestPermissionsLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                Log.d("MAIN_ACTIVITY_DEBUG", "Location permission is granted, starting service.")
                startDataCollectionService()
            } else {
                Log.d("MAIN_ACTIVITY_DEBUG", "Critical location permission was denied.")
                // Optionally, show a message to the user explaining why location is needed.
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsManager = SettingsManager(applicationContext)

        setContent {
            TelematicsDatasetGatheringTheme {
                // Collect the config flow for the settings dialog
                val config by settingsManager.mqttConfigFlow.collectAsState(initial = MqttConfig("", "", ""))

                // The service's actual running state, collected from the repository
                val isServiceRunning by DataRepository.isServiceRunningFlow.collectAsState(initial = false)

                var currentLabel by rememberSaveable { mutableStateOf(0) }
                var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

                MainScreen(
                    isServiceRunning = isServiceRunning,
                    currentLabel = currentLabel,
                    stats = statsViewModel.statsFlow.collectAsState().value,
                    onStartClick = {
                        Log.d("MAIN_ACTIVITY_DEBUG", "Start Button Clicked!")
                        checkPermissionsAndStartService()
                    },
                    onStopClick = {
                        Log.d("MAIN_ACTIVITY_DEBUG", "Stop Button Clicked!")
                        stopDataCollectionService()
                    },
                    onLabelChange = { newLabel ->
                        currentLabel = newLabel
                        changeCollectionLabel(newLabel)
                    },
                    onShowSettings = { showSettingsDialog = true }
                )

                if (showSettingsDialog) {
                    SettingsDialog(
                        config = config,
                        onDismiss = { showSettingsDialog = false },
                        onSave = { newConfig ->
                            lifecycleScope.launch {
                                settingsManager.saveMqttConfig(newConfig)
                            }
                            showSettingsDialog = false
                        }
                    )
                }
            }
        }
    }

    private fun checkPermissionsAndStartService() {
        val permissionsToRequest = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            Log.d("MAIN_ACTIVITY_DEBUG", "Requesting permissions: $permissionsToRequest")
            requestPermissionsLauncher.launch(permissionsToRequest.toTypedArray())
        } else {
            // If permissions are already granted, start the service directly
            Log.d("MAIN_ACTIVITY_DEBUG", "Permissions already granted, starting service directly.")
            startDataCollectionService()
        }
    }

    private fun startDataCollectionService() {
        val serviceIntent = Intent(this, DataCollectionService::class.java).apply {
            action = DataCollectionService.ACTION_START
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun stopDataCollectionService() {
        val serviceIntent = Intent(this, DataCollectionService::class.java).apply {
            action = DataCollectionService.ACTION_STOP
        }
        startService(serviceIntent)
    }

    private fun changeCollectionLabel(label: Int) {
        val serviceIntent = Intent(this, DataCollectionService::class.java).apply {
            action = DataCollectionService.ACTION_CHANGE_LABEL
            putExtra(DataCollectionService.EXTRA_LABEL, label)
        }
        startService(serviceIntent)
    }
}


@Composable
fun MainScreen(
    isServiceRunning: Boolean,
    currentLabel: Int,
    stats: RealTimeStats,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit,
    onLabelChange: (Int) -> Unit,
    onShowSettings: () -> Unit
) {
    val labelMap = mapOf(
        0 to "Normal", 1 to "Harsh Brake", 2 to "Harsh Cornering",
        3 to "Pothole/Bump", 4 to "Accident (Sim.)", 5 to "Phone Fall"
    )

    val decimalFormat = DecimalFormat("#.##")

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // --- Control Panel ---
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Button(onClick = onStartClick, enabled = !isServiceRunning) { Text("Start") }
                Button(onClick = onStopClick, enabled = isServiceRunning) { Text("Stop") }
            }
            TextButton(onClick = onShowSettings) {
                Text("Broker Settings")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (isServiceRunning) "Status: Collecting" else "Status: Idle",
                style = MaterialTheme.typography.headlineSmall
            )
            if (isServiceRunning) {
                Text(
                    text = "Current Label: ${labelMap[currentLabel]}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        // --- Live Stats Display ---
        if (isServiceRunning) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("Live Stats", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(8.dp))
                        StatRow("Accel. Magnitude:", "${decimalFormat.format(stats.accMag)} m/s²")
                        StatRow("Gyro. Magnitude:", "${decimalFormat.format(stats.gyroMag)} rad/s")
                        StatRow("Speed:", "${decimalFormat.format(stats.speed * 3.6)} km/h")
                        StatRow("Latitude:", "${decimalFormat.format(stats.latitude)}")
                        StatRow("Longitude:", "${decimalFormat.format(stats.longitude)}")
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // --- Label Buttons ---
        if (isServiceRunning) {
            item {
                Text("--- Tap to Label Event ---", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
            }

            val labelItems = labelMap.toList()
            items(
                count = labelItems.size,
                key = { index -> labelItems[index].first }
            ) { index ->
                val (labelId, labelName) = labelItems[index]
                Button(
                    onClick = { onLabelChange(labelId) },
                    modifier = Modifier
                        .fillMaxWidth(0.8f)
                        .padding(vertical = 4.dp)
                ) {
                    Text(labelName)
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(text = label, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(text = value, modifier = Modifier.weight(1f))
    }
}

@Composable
fun SettingsDialog(
    config: MqttConfig,
    onDismiss: () -> Unit,
    onSave: (MqttConfig) -> Unit
) {
    var brokerUrl by rememberSaveable { mutableStateOf(config.brokerUrl) }
    var username by rememberSaveable { mutableStateOf(config.username) }
    var password by rememberSaveable { mutableStateOf(config.password) }

    Dialog(onDismissRequest = onDismiss) {
        Card {
            Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("MQTT Broker Settings", style = MaterialTheme.typography.titleLarge)
                Spacer(modifier = Modifier.height(16.dp))
                OutlinedTextField(
                    value = brokerUrl,
                    onValueChange = { brokerUrl = it },
                    label = { Text("Broker URL (e.g., ssl://... or tcp://...)") }
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username (optional)") }
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password (optional)") }
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(onClick = {
                        onSave(MqttConfig(brokerUrl, username, password))
                    }) {
                        Text("Save")
                    }
                }
            }
        }
    }
}