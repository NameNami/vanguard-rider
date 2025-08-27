// file: com/example/telematicsdatasetgathering/StatsViewModel.kt

package com.example.telematicsdatasetgathering

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import android.util.Log

class StatsViewModel : ViewModel() {

    // A private, mutable StateFlow to hold the latest stats.
    // StateFlow is perfect for UI state because it always has a value.
    private val _statsFlow = MutableStateFlow(RealTimeStats())
    // A public, read-only StateFlow for the UI to collect from.
    val statsFlow = _statsFlow.asStateFlow()

    init {
        viewModelScope.launch {
            DataRepository.statsFlow.collect { newStats ->
                Log.d("STATS_DEBUG", "VIEWMODEL: Received new stats. AccMag: ${newStats.accMag}") // <-- ADD THIS
                _statsFlow.value = newStats
            }
        }
    }
}