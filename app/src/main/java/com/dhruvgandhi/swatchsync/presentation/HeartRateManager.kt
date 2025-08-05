
package com.dhruvgandhi.swatchsync.presentation

import android.content.Context
import android.util.Log
import androidx.health.services.client.HealthServices
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.PassiveListenerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.guava.await

class HeartRatePassiveListenerService : PassiveListenerService() {
    companion object {
        val heartRateFlow = MutableStateFlow(0.0)
    }
    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val heartRateData = dataPoints.getData(DataType.HEART_RATE_BPM)
        if (heartRateData.isNotEmpty()) {
            val latestHeartRate = heartRateData.last().value
            heartRateFlow.value = latestHeartRate
            Log.d("HeartRate", "Passive HR update: $latestHeartRate BPM")
        }
    }
}

class HeartRateManager(private val context: Context) {

    private val healthServicesClient = HealthServices.getClient(context)
    private val passiveMonitoringClient = healthServicesClient.passiveMonitoringClient

    private val _heartRate = MutableStateFlow(0.0)
    val heartRate: StateFlow<Double> = _heartRate

    private val _isMonitoring = MutableStateFlow(false)
    val isMonitoring: StateFlow<Boolean> = _isMonitoring

    // CoroutineScope you can tie to Service, ViewModel, or App scope appropriately
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
                // Forward passive listener updates to local flow
                HeartRatePassiveListenerService.heartRateFlow.collect { bpm ->
                    _heartRate.value = bpm
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
