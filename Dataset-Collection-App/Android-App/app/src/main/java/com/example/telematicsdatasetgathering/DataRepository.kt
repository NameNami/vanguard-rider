// file: com/example/telematicsdatasetgathering/DataRepository.kt

package com.example.telematicsdatasetgathering

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

// A simple data class to hold the real-time stats
data class RealTimeStats(
    val accMag: Float = 0f,
    val gyroMag: Float = 0f,
    val speed: Float = 0f,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0
)

// A singleton object to hold and share the data flow
object DataRepository {
    // A private mutable flow that only the service can write to
    private val _statsFlow = MutableSharedFlow<RealTimeStats>()
    // A public, read-only flow for the UI to collect from
    val statsFlow = _statsFlow.asSharedFlow()

    private val _isServiceRunningFlow = MutableSharedFlow<Boolean>()
    val isServiceRunningFlow = _isServiceRunningFlow.asSharedFlow()

    suspend fun setServiceRunning(isRunning: Boolean) {
        _isServiceRunningFlow.emit(isRunning)
    }
    // A function for the service to call to update the stats
    suspend fun updateStats(stats: RealTimeStats) {
        _statsFlow.emit(stats)
    }
}