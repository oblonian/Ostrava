package com.ostrava.app.tracking

import android.annotation.SuppressLint
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.UUID

/**
 * Connects to a standard Bluetooth LE heart-rate strap (GATT Heart Rate service).
 * Process-wide singleton so the tracking service and UI share one connection.
 *
 * Callers are responsible for holding BLUETOOTH_SCAN / BLUETOOTH_CONNECT
 * (or location on Android 11 and below) before calling [startScan]/[connect].
 */
@SuppressLint("MissingPermission")
object HeartRateMonitor {

    enum class ConnectionState { IDLE, SCANNING, CONNECTING, CONNECTED }

    data class FoundDevice(val name: String, val address: String)

    private val HR_SERVICE: UUID = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
    private val HR_MEASUREMENT: UUID = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
    private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    private const val SCAN_TIMEOUT_MS = 15_000L

    val state = MutableStateFlow(ConnectionState.IDLE)
    val bpm = MutableStateFlow<Int?>(null)
    val deviceName = MutableStateFlow<String?>(null)
    val foundDevices = MutableStateFlow<List<FoundDevice>>(emptyList())

    private val handler = Handler(Looper.getMainLooper())
    private var gatt: BluetoothGatt? = null
    private var scanner: android.bluetooth.le.BluetoothLeScanner? = null
    private var scanning = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = try {
                result.device.name ?: result.scanRecord?.deviceName
            } catch (_: SecurityException) {
                null
            } ?: "Unknown sensor"
            val device = FoundDevice(name, result.device.address)
            val current = foundDevices.value
            if (current.none { it.address == device.address }) {
                foundDevices.value = current + device
            }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan()
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post { disconnect() }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val characteristic = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (characteristic == null) {
                handler.post { disconnect() }
                return
            }
            g.setCharacteristicNotification(characteristic, true)
            val descriptor = characteristic.getDescriptor(CCCD)
            if (descriptor != null) {
                @Suppress("DEPRECATION")
                descriptor.value = android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                @Suppress("DEPRECATION")
                g.writeDescriptor(descriptor)
            }
            handler.post { state.value = ConnectionState.CONNECTED }
        }

        @Deprecated("Deprecated in API 33 but still delivered on all versions")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            parseHeartRate(value)?.let { heartRate ->
                handler.post { bpm.value = heartRate }
            }
        }
    }

    /** Flags bit 0: 0 = uint8 bpm at offset 1, 1 = uint16 little-endian at offset 1. */
    private fun parseHeartRate(value: ByteArray): Int? {
        if (value.isEmpty()) return null
        val uint16 = value[0].toInt() and 0x01 != 0
        return when {
            uint16 && value.size >= 3 ->
                (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
            !uint16 && value.size >= 2 -> value[1].toInt() and 0xFF
            else -> null
        }
    }

    fun startScan(context: Context) {
        if (scanning || state.value == ConnectionState.CONNECTED) return
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val leScanner = adapter?.bluetoothLeScanner ?: return
        scanner = leScanner
        foundDevices.value = emptyList()
        scanning = true
        state.value = ConnectionState.SCANNING
        try {
            leScanner.startScan(
                listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build()),
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scanCallback,
            )
            handler.postDelayed({ stopScan() }, SCAN_TIMEOUT_MS)
        } catch (_: SecurityException) {
            scanning = false
            state.value = ConnectionState.IDLE
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        if (state.value == ConnectionState.SCANNING) state.value = ConnectionState.IDLE
    }

    fun connect(context: Context, address: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            ?: return
        stopScan()
        try {
            val device = adapter.getRemoteDevice(address)
            deviceName.value = foundDevices.value.firstOrNull { it.address == address }?.name
            state.value = ConnectionState.CONNECTING
            gatt = device.connectGatt(context.applicationContext, false, gattCallback)
        } catch (_: Exception) {
            state.value = ConnectionState.IDLE
        }
    }

    fun disconnect() {
        try {
            gatt?.close()
        } catch (_: Exception) {
        }
        gatt = null
        bpm.value = null
        deviceName.value = null
        if (state.value != ConnectionState.SCANNING) state.value = ConnectionState.IDLE
    }
}
