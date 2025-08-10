package com.dhruvgandhi.swatchsync.presentation

import android.content.Context
import android.util.Log
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.guava.await
import java.time.LocalDateTime


data class FitnessData(
    val heartRate: Double = Double.MIN_VALUE,
    val stepCount: Long = Long.MIN_VALUE,  // Add step count
    val lastUpdated: LocalDateTime = LocalDateTime.MIN
)

object FitnessInfo {  // Renamed from HeartRateInfo
    private val _state = MutableStateFlow(FitnessData())
    val state: StateFlow<FitnessData> = _state

    fun updateHeartRate(heartRate: Double, time: LocalDateTime) {
        _state.value = _state.value.copy(heartRate = heartRate, lastUpdated = time)
    }

    fun updateStepCount(steps: Long, time: LocalDateTime) {
        _state.value = _state.value.copy(stepCount = steps, lastUpdated = time)
    }

    fun updateAll(heartRate: Double, steps: Long, time: LocalDateTime) {
        _state.value = FitnessData(heartRate, steps, time)
    }
}

class HeartRatePassiveListenerService : PassiveListenerService() {


    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val now = LocalDateTime.now()


        // Get heart rate points and filter out zero/invalid values
        val heartRateData = dataPoints.getData(DataType.HEART_RATE_BPM)
            .map { it.value }
            .filter { it in 25.0..250.0 } // allow realistic human range

        if (heartRateData.isNotEmpty()) {
            // --- Median for stable current value ---
            val sorted = heartRateData.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            }

            // --- Max in current batch (for alerting) ---
            val maxReading = heartRateData.maxOrNull()

            // --- Optional: detect sustained low or high HR ---
            val lowThreshold = 40.0
            val highThreshold = 180.0
            val lowCount = heartRateData.count { it < lowThreshold }
            val highCount = heartRateData.count { it > highThreshold }

            // Update main fitness state with median value
            FitnessInfo.updateHeartRate(median, now)

            // Example: log alerts (or trigger an event)
            if (lowCount > heartRateData.size / 2) {
                Log.w("FitnessData", "⚠ Sustained LOW heart rate detected: median=$median")
            }
            if (highCount > heartRateData.size / 2) {
                Log.w("FitnessData", "⚠ Sustained HIGH heart rate detected: median=$median")
            }
        }

        // Handle step count data
        val stepData = dataPoints.getData(DataType.STEPS_DAILY)
        if (stepData.isNotEmpty()) {
            val latestSteps = stepData.last().value.toLong()
            FitnessInfo.updateStepCount(latestSteps, now)
            Log.d("FitnessData", "Steps update: $latestSteps steps")
        }
    }

}

class HeartRateManager(private val context: Context) {
    private val healthServicesClient = HealthServices.getClient(context)
    private val passiveMonitoringClient = healthServicesClient.passiveMonitoringClient
    private val _heartRate = MutableStateFlow(0.0)
    private val _lastUpdated = MutableStateFlow(LocalDateTime.MIN)
    val heartRate: StateFlow<Double> = _heartRate

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun startMonitoring() {
        managerScope.launch {
            try {
                val config = PassiveListenerConfig.builder()
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM, DataType.STEPS_DAILY))
                    .build()
                passiveMonitoringClient.setPassiveListenerServiceAsync(
                    HeartRatePassiveListenerService::class.java,
                    config
                ).await()
                _isMonitoring.value = true

//                HeartRatePassiveListenerService.heartRateFlow.collect { bpm ->
//                    _heartRate.value = bpm
//                }
                FitnessInfo.state.collect { hrx ->
                    _heartRate.value = hrx.heartRate
                }

            } catch (e: Exception) {
                _isMonitoring.value = false
                Log.e("HeartRateManager", "Failed to start passive HR monitoring", e)
            }
        }
    }

    fun stopMonitoring() {
        managerScope.launch {
            try {
                passiveMonitoringClient.clearPassiveListenerServiceAsync().await()
                _isMonitoring.value = false
                Log.d("HeartRateManager", "Stopped passive HR monitoring")
            } catch (e: Exception) {
                Log.e("HeartRateManager", "Failed to stop passive HR monitoring", e)
            }
        }
    }
}
