package com.dhruvgandhi.swatchsync.presentation

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelUuid
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.util.*

class HeartRateBluetoothService : Service() {

    companion object {
        private val HEART_RATE_SERVICE_UUID = UUID.fromString("0000180D-0000-1000-8000-00805F9B34FB")
        private val HEART_RATE_MEASUREMENT_UUID = UUID.fromString("00002A37-0000-1000-8000-00805F9B34FB")
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val DEVICE_NAME = "GalaxyWatch_HeartSync"
        private const val NOTIF_CHANNEL_ID = "hr_bluetooth_channel"
        private const val NOTIF_ID = 1080
    }

    private lateinit var bluetoothManager: BluetoothManager
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private lateinit var heartRateManager: HeartRateManager

    private val connectedDevices = mutableSetOf<BluetoothDevice>()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        heartRateManager = HeartRateManager(this)
        setupForeground() // Reduce risk of background kill
        // Permission check
        if (
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED
        ) {
            setupGattServer()
            startAdvertising()
            startHeartRateMonitoring()
        } else {
            Log.e("HeartRateBluetoothService", "Missing Bluetooth permissions!")
            stopSelf()
        }
    }

    private fun setupForeground() {
        try {
            val channelId = NOTIF_CHANNEL_ID
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(channelId, "Heart Rate Bluetooth", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
            val notification: Notification = NotificationCompat.Builder(this, channelId)
                .setContentTitle("Heart rate sync running")
                .setContentText("Broadcasting heart rate via Bluetooth")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .build()
            startForeground(NOTIF_ID, notification)
        }catch (ex: Exception){
            Log.e("swatch",ex.message.toString())
        }

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
            CLIENT_CHARACTERISTIC_CONFIG_UUID,
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
                    Log.d("HeartRateBluetoothService", "Device connected: ${device.address}")
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    connectedDevices.remove(device)
                    Log.d("HeartRateBluetoothService", "Device disconnected: ${device.address}")
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT_UUID) {
                val heartRate = heartRateManager.heartRate.value
                val data = createHeartRateData(heartRate)
                if (
                    ActivityCompat.checkSelfPermission(
                        this@HeartRateBluetoothService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) return
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, data)
            } else {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
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
            if (descriptor.uuid == CLIENT_CHARACTERISTIC_CONFIG_UUID) {
                if (value.contentEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    Log.d("HeartRateBluetoothService", "Notifications ENABLED for ${device.address}")
                } else if (value.contentEquals(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    Log.d("HeartRateBluetoothService", "Notifications DISABLED for ${device.address}")
                }
                if (responseNeeded) {
                    bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else if (responseNeeded) {
                bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
            }
        }
    }

    private fun startAdvertising() {
        advertiser = bluetoothAdapter.bluetoothLeAdvertiser
        try {
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build()

            val advertiseData = AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(ParcelUuid(HEART_RATE_SERVICE_UUID))
                .build()

            val scanResponseData = AdvertiseData.Builder()
                .setIncludeTxPowerLevel(true)
                .build()

            val parameters = AdvertisingSetParameters.Builder()
                .setLegacyMode(true)
                .setConnectable(true)
                .setInterval(AdvertisingSetParameters.INTERVAL_LOW)
                .setTxPowerLevel(AdvertisingSetParameters.TX_POWER_MEDIUM)
                .build()
            advertiser?.startAdvertisingSet(
                parameters,
                advertiseData,
                scanResponseData,
                null,
                null,
                advertisingSetCallback
            )
        } catch (ex: Exception) {
            Log.e("BT service", "Advertising error: ${ex.message}")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.d("HeartRateBluetoothService", "Legacy advertising started successfully")
        }
        override fun onStartFailure(errorCode: Int) {
            Log.e("HeartRateBluetoothService", "Legacy advertising failed: $errorCode")
        }
    }

    private val advertisingSetCallback = object : AdvertisingSetCallback() {
        override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
            if (status == AdvertisingSetCallback.ADVERTISE_SUCCESS) {
                Log.d("HeartRateBluetoothService", "AdvertisingSet started successfully")
            } else {
                Log.e("HeartRateBluetoothService", "AdvertisingSet failed: $status")
            }
        }
        override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet?) {
            Log.d("HeartRateBluetoothService", "AdvertisingSet stopped")
        }
    }

    private fun startHeartRateMonitoring() {
        serviceScope.launch {
            heartRateManager.startMonitoring()
            heartRateManager.heartRate.collect { heartRate ->
                if (
                    heartRate > 0 &&
                    ActivityCompat.checkSelfPermission(
                        this@HeartRateBluetoothService,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) == PackageManager.PERMISSION_GRANTED
                ) {
                    notifyHeartRateUpdate(heartRate)
                }
            }
        }
    }

    @RequiresPermission(Manifest.permission.BLUETOOTH_CONNECT)
    private fun notifyHeartRateUpdate(heartRate: Double) {
        val service = bluetoothGattServer?.getService(HEART_RATE_SERVICE_UUID)
        val characteristic = service?.getCharacteristic(HEART_RATE_MEASUREMENT_UUID) ?: return
        val data = createHeartRateData(heartRate)
        characteristic.value = data
        connectedDevices.forEach { device ->
            bluetoothGattServer?.notifyCharacteristicChanged(device, characteristic, false)
        }
    }

    private fun createHeartRateData(heartRate: Double): ByteArray {
        val flags: Byte = 0x00
        val hrValue = heartRate.toInt().coerceIn(0, 255).toByte()
        return byteArrayOf(flags, hrValue)
    }

    @RequiresPermission(allOf = [Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT])
    override fun onDestroy() {
        super.onDestroy()
        advertiser?.stopAdvertising(advertiseCallback)
        bluetoothGattServer?.close()
        heartRateManager.stopMonitoring()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
