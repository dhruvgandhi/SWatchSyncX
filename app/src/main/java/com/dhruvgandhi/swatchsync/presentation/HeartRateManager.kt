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

data class HeartRateState(
    val heartRate: Double = 0.0,
    val lastUpdated: LocalDateTime = LocalDateTime.MIN
)
object HeartRateInfo {
    private val _state = MutableStateFlow(HeartRateState())
    val state: StateFlow<HeartRateState> = _state

    fun update(heartRate: Double, time: LocalDateTime) {
        _state.value = HeartRateState(heartRate, time)
    }
}
class HeartRatePassiveListenerService : PassiveListenerService() {


    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val heartRateData = dataPoints.getData(DataType.HEART_RATE_BPM)
        if (heartRateData.isNotEmpty()) {
            val latestHeartRate = heartRateData.last().value
            val now = LocalDateTime.now()
            HeartRateInfo.update(latestHeartRate, now)
            Log.d("HeartRate", "Passive HR update: $latestHeartRate BPM")
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
                    .setDataTypes(setOf(DataType.HEART_RATE_BPM))
                    .build()
                passiveMonitoringClient.setPassiveListenerServiceAsync(
                    HeartRatePassiveListenerService::class.java,
                    config
                ).await()
                _isMonitoring.value = true

//                HeartRatePassiveListenerService.heartRateFlow.collect { bpm ->
//                    _heartRate.value = bpm
//                }
                HeartRateInfo.state.collect {
                    hrx->
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
