package com.dhruvgandhi.swatchsync.presentation

import android.Manifest
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.util.*

class HeartRateBluetoothService : Service() {

    companion object {
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        private val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        private const val DEVICE_NAME = "GalaxyWatch_HeartSync"
    }

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private lateinit var heartRateManager: HeartRateManager
    private val connectedDevices = mutableSetOf<BluetoothDevice>()

    // Coroutine scope for this service
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    override fun onCreate() {
        super.onCreate()

        bluetoothManager = getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        heartRateManager = HeartRateManager(this)

        //setupGattServer()
        //startAdvertising()
        startHeartRateMonitoring()
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun setupGattServer() {
        bluetoothGattServer = bluetoothManager.openGattServer(this, gattServerCallback)

        val heartRateService = BluetoothGattService(
            HEART_RATE_SERVICE_UUID,
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        val heartRateCharacteristic = BluetoothGattCharacteristic(
            HEART_RATE_MEASUREMENT_UUID,
            BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )

        val descriptor = BluetoothGattDescriptor(
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )

        heartRateCharacteristic.addDescriptor(descriptor)
        heartRateService.addCharacteristic(heartRateCharacteristic)
        bluetoothGattServer?.addService(heartRateService)
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedDevices.add(device)
                    Log.d("Bluetooth", "Device connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    Log.d("Bluetooth", "Device disconnected: ${device.address}")
                }
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val heartRate = heartRateManager.heartRate.value
                val data = createHeartRateData(heartRate)
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, data)
            }
        }

        @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
            .build()

        advertiser?.startAdvertising(settings, data, advertiseCallback)
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("Bluetooth", "Advertising started successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e("Bluetooth", "Advertising failed with error: $errorCode")
        }
    }

    public fun startHeartRateMonitoring() {
        serviceScope.launch {
            heartRateManager.startMonitoring()

            heartRateManager.heartRate.collect { heartRate ->
               collectHeartRate(heartRate)
            }
        }
    }
    private fun collectHeartRate(heartRate:Double){
        if (heartRate > 0) {
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                // TODO: Consider calling
                //    ActivityCompat#requestPermissions
                // here to request the missing permissions, and then overriding
                //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
                //                                          int[] grantResults)
                // to handle the case where the user grants the permission. See the documentation
                // for ActivityCompat#requestPermissions for more details.
                return
            }
            notifyHeartRateUpdate(heartRate)
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyHeartRateUpdate(heartRate: Double) {
        val characteristic = bluetoothGattServer?.getService(HEART_RATE_SERVICE_UUID)
            ?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID)

        characteristic?.let {
            val data = createHeartRateData(heartRate)
            it.value = data

            connectedDevices.forEach { device ->
                if (ActivityCompat.checkSelfPermission(
                        this,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    // Permission not granted, skip notification
                    return
                }
                bluetoothGattServer?.notifyCharacteristicChanged(device, it, false)
            }
        }
    }

    private fun createHeartRateData(heartRate: Double): ByteArray {
        // Heart Rate Measurement format (simplified)
        val flags: Byte = 0x00 // 8-bit heart rate value
        val heartRateValue = heartRate.toInt().toByte()
        val timestamp = System.currentTimeMillis()

        return byteArrayOf(
            flags,
            heartRateValue,
            (timestamp and 0xFF).toByte(),
            ((timestamp shr 8) and 0xFF).toByte(),
            ((timestamp shr 16) and 0xFF).toByte(),
            ((timestamp shr 24) and 0xFF).toByte()
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onDestroy() {
        super.onDestroy()
        advertiser?.stopAdvertising(advertiseCallback)
        bluetoothGattServer?.close()
        heartRateManager.stopMonitoring()
        serviceScope.cancel()
    }
}
