package com.example.smartcrutch.ui

import android.bluetooth.*
import android.bluetooth.le.*
import android.os.Handler
import android.os.Looper
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartcrutch.data.model.InstrumentData
import com.example.smartcrutch.data.remote.HarvesterClient
import com.example.smartcrutch.data.repository.HarvesterRepository
import com.example.smartcrutch.data.local.SensorDatabaseHelper
import com.example.smartcrutch.data.local.PatientMetadataDatabaseHelper
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

enum class Screen {
    Home, Progress, Sync, Profile, Direct
}

data class DashboardUiState(
    val currentScreen: Screen = Screen.Home,
    val weightBearing: Float = 0.78f,
    val steps: Int = 3241,
    val goalSteps: Int = 4000,
    val weightLimit: Float = 50f,
    val recoveryGoal: String = "Partial Weight Bearing",
    val organizationName: String = "St. Mary's Orthopedics",
    val gaitPattern: String = "Not Detected",
    val humidity: Float = 45.0f,
    val temperature: Float = 22.5f,
    val latestNumbers: List<Int> = emptyList(),
    val latestGm1Data: Map<String, Any>? = null,
    val lastReceivedType: String = "NONE", // "GM1", "SENSOR", "RAW", "DIRECT"
    val isSyncing: Boolean = false,
    val lastSyncTime: String = "Never",
    val syncStatus: String = "Disconnected",
    val liveLogs: List<String> = emptyList(),
    val isLiveFeedActive: Boolean = false,
    val isRawStreamEnabled: Boolean = false,
    val isExperimentalDecodingEnabled: Boolean = false,
    val weightHistory: List<Float> = listOf(0.2f, 0.4f, 0.35f, 0.6f, 0.55f, 0.8f, 0.75f, 0.78f),
    
    // BLE States
    val bleStatus: String = "Idle",
    val isBleConnected: Boolean = false,
    val isScanning: Boolean = false,
    val discoveredDevices: List<android.bluetooth.BluetoothDevice> = emptyList(),
    val directImuData: Map<String, Float> = emptyMap(),
    val directHistory: List<Map<String, Float>> = emptyList(), // Store last 50 readings
    val isRecording: Boolean = false,
    val batchGait: String = "Normal",
    val batchTerrain: String = "Flat",
    val patientId: String = "P001",
    val sessionIndex: Int = 0,
    val patientAge: String = "",
    val patientGender: String = "",
    val patientWeight: String = "",
    val injuredLeg: String = "None",
    val patientName: String = "Hassan Himaz",
    val showPrescriptionPopup: Boolean = false,
    val hidePrescriptionPopupPermanently: Boolean = false,
    val selectedCrutchCount: Int = 2,
    val selectedGait: String = "Swing",
    val isDeveloperModeEnabled: Boolean = false,
    val lastNgrokData: String = "",
    val wristStrainIndex: Int = 8,
    val isDarkMode: Boolean = true,
    val showGaitMismatchPopup: Boolean = false,
    val showSplash: Boolean = true,
    val recoveryScore: Int = 87,
    val syncMessage: String = "",
    val showSyncPopup: Boolean = false,
    val lastShownMessage: String = "",
    val lastShownDate: String = ""
)

class DashboardViewModel : ViewModel() {

    // private val repository = HarvesterRepository()
    
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGatt: BluetoothGatt? = null
    
    // Pico Relay Peripheral Mode
    private var bluetoothGattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private val reassemblyBuffer = mutableMapOf<Int, ByteArray>() // msgId -> accumulated bytes
    
    private val handler = Handler(Looper.getMainLooper())
    
    private var dbHelper: SensorDatabaseHelper? = null
    private var currentSessionId: Long = -1
    private var logWriter: PrintWriter? = null

    init {
        checkPrescriptionPopup()
        startLiveFeed()
        startAutoUpdate()
    }

    private fun checkPrescriptionPopup() {
        // Simple session-based check
        if (!hasBeenDismissedThisSession) {
            _uiState.value = _uiState.value.copy(showPrescriptionPopup = true)
        }
    }

    fun dismissPrescriptionPopup(permanently: Boolean) {
        hasBeenDismissedThisSession = true
        _uiState.value = _uiState.value.copy(
            showPrescriptionPopup = false,
            hidePrescriptionPopupPermanently = permanently
        )
    }

    companion object {
        private var hasBeenDismissedThisSession = false
    }

    fun submitPrescription(crutches: Int, gait: String) {
        _uiState.value = _uiState.value.copy(
            selectedCrutchCount = crutches,
            selectedGait = gait,
            gaitPattern = "Not Detected" // Start with awaiting data
        )
        addLog("Profile updated via prescription: Crutches=$crutches, Gait=$gait")
    }

    // --- Pico Relay (Peripheral Mode) ---

    fun startPicoRelayMode(context: Context) {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _uiState.value = _uiState.value.copy(bleStatus = "Bluetooth Disabled")
            return
        }

        if (!bluetoothAdapter.isMultipleAdvertisementSupported) {
            _uiState.value = _uiState.value.copy(bleStatus = "Hardware Unsupported")
            addLog("Error: Device does not support BLE Advertising (Peripheral Mode)")
            return
        }

        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        
        // 1. Setup GATT Server
        try {
            bluetoothGattServer = bluetoothManager.openGattServer(context, gattServerCallback)
            val service = BluetoothGattService(
                UUID.fromString("8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a01"),
                BluetoothGattService.SERVICE_TYPE_PRIMARY
            )

            // DataIn: Nano writes TO this
            val dataInChar = BluetoothGattCharacteristic(
                UUID.fromString("8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a02"),
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            
            // Status: Optional
            val statusChar = BluetoothGattCharacteristic(
                UUID.fromString("8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a03"),
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                BluetoothGattCharacteristic.PERMISSION_READ
            )

            service.addCharacteristic(dataInChar)
            service.addCharacteristic(statusChar)
            bluetoothGattServer?.addService(service)

            // 2. Start Advertising
            advertiser = bluetoothAdapter.bluetoothLeAdvertiser
            val settings = AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true)
                .setTimeout(0)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
                .build()

            val data = AdvertiseData.Builder()
                .setIncludeDeviceName(false) // UUID + Name often exceeds 31 bytes
                .addServiceUuid(android.os.ParcelUuid(UUID.fromString("8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a01")))
                .build()

            advertiser?.startAdvertising(settings, data, advertiseCallback)
            _uiState.value = _uiState.value.copy(bleStatus = "Pico Relay: Advertising...")
            addLog("Pico Relay Mode Started (Peripheral)")
        } catch (e: SecurityException) {
            _uiState.value = _uiState.value.copy(bleStatus = "Permission Denied")
        }
    }

    fun stopPicoRelayMode() {
        try {
            advertiser?.stopAdvertising(advertiseCallback)
            bluetoothGattServer?.close()
            bluetoothGattServer = null
            _uiState.value = _uiState.value.copy(isBleConnected = false, bleStatus = "Idle")
            addLog("Pico Relay Mode Stopped")
        } catch (e: SecurityException) { /* ignore */ }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            super.onStartSuccess(settingsInEffect)
            addLog("BLE Advertising Started Successfully")
        }

        override fun onStartFailure(errorCode: Int) {
            val errorString = when (errorCode) {
                ADVERTISE_FAILED_DATA_TOO_LARGE -> "Data Too Large (31B limit)"
                ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "Too Many Advertisers"
                ADVERTISE_FAILED_ALREADY_STARTED -> "Already Advertising"
                ADVERTISE_FAILED_INTERNAL_ERROR -> "Internal Hardware Error"
                ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "BLE Advertising Unsupported"
                else -> "Error Code: $errorCode"
            }
            val msg = "Adv Failed: $errorString"
            handler.post { 
                _uiState.value = _uiState.value.copy(bleStatus = msg)
                addLog(msg)
                // Clean up GATT server if advertising fails
                stopPicoRelayMode()
            }
        }
    }

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            handler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    _uiState.value = _uiState.value.copy(isBleConnected = true, bleStatus = "Nano Connected")
                    addLog("Nano Connected: ${device.address}")
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    _uiState.value = _uiState.value.copy(isBleConnected = false, bleStatus = "Nano Disconnected")
                    addLog("Nano Disconnected")
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == UUID.fromString("8a3e4d2f-1b6c-4f9e-a7d8-3e5b2c1f4a02")) {
                handlePicoRelayFrame(value)
                if (responseNeeded) {
                    try {
                        bluetoothGattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
                    } catch (e: SecurityException) { /* ignore */ }
                }
            }
        }
    }

    private fun handlePicoRelayFrame(frame: ByteArray) {
        if (frame.size < 4) return
        
        val flags = frame[0].toInt() and 0xFF
        val msgId = frame[1].toInt() and 0xFF
        val seq = (frame[2].toInt() and 0xFF) or ((frame[3].toInt() and 0xFF) shl 8)
        val payload = frame.sliceArray(4 until frame.size)

        val isStart = (flags and 0x80) != 0
        val isEnd = (flags and 0x40) != 0

        var buffer = reassemblyBuffer[msgId] ?: ByteArray(0)
        
        if (isStart) {
            buffer = payload
        } else {
            buffer += payload
        }

        if (isEnd) {
            reassemblyBuffer.remove(msgId)
            handleDirectData(buffer)
        } else {
            reassemblyBuffer[msgId] = buffer
        }
    }

    // --- Legacy Central Mode (Keeping for compatibility/sync) ---

    fun startBleScan() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            _uiState.value = _uiState.value.copy(bleStatus = "Bluetooth Disabled")
            return
        }

        val scanner = bluetoothAdapter.bluetoothLeScanner
        _uiState.value = _uiState.value.copy(isScanning = true, bleStatus = "Scanning...", discoveredDevices = emptyList())

        try {
            scanner.startScan(scanCallback)
        } catch (e: SecurityException) {
            _uiState.value = _uiState.value.copy(bleStatus = "Permission Denied")
        }
        
        // Stop scan after 10 seconds
        handler.postDelayed({
            stopBleScan()
        }, 10000)
    }

    fun stopBleScan() {
        if (_uiState.value.isScanning) {
            try {
                bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
            } catch (e: SecurityException) {
                // Ignore
            }
            _uiState.value = _uiState.value.copy(isScanning = false, bleStatus = "Scan Finished")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val currentDevices = _uiState.value.discoveredDevices
            if (currentDevices.none { it.address == device.address }) {
                _uiState.value = _uiState.value.copy(discoveredDevices = currentDevices + device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            val errorMsg = when(errorCode) {
                SCAN_FAILED_ALREADY_STARTED -> "Scan already active"
                SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "App Registration Fail"
                SCAN_FAILED_FEATURE_UNSUPPORTED -> "BLE Unsupported"
                SCAN_FAILED_INTERNAL_ERROR -> "System Internal Error"
                else -> "Scan Error: $errorCode"
            }
            _uiState.value = _uiState.value.copy(isScanning = false, bleStatus = errorMsg)
        }
    }

    fun connectToDevice(device: BluetoothDevice, context: android.content.Context) {
        stopBleScan()
        val deviceName = try { device.name ?: "Unknown" } catch (e: SecurityException) { "Unknown" }
        _uiState.value = _uiState.value.copy(bleStatus = "Connecting to $deviceName...")
        try {
            bluetoothGatt = device.connectGatt(context, false, gattCallback)
        } catch (e: SecurityException) {
            _uiState.value = _uiState.value.copy(bleStatus = "Permission Denied")
        }
    }

    fun disconnectBle() {
        try {
            bluetoothGatt?.disconnect()
            bluetoothGatt?.close()
            bluetoothGatt = null
        } catch (e: SecurityException) {
            // Ignore
        }
        _uiState.value = _uiState.value.copy(isBleConnected = false, bleStatus = "Disconnected")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                try {
                    _uiState.value = _uiState.value.copy(isBleConnected = true, bleStatus = "Connected. Discovering...")
                    gatt.discoverServices()
                } catch (e: SecurityException) {
                    _uiState.value = _uiState.value.copy(bleStatus = "Permission Error")
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                handler.post {
                    _uiState.value = _uiState.value.copy(isBleConnected = false, bleStatus = "Disconnected")
                }
                try {
                    gatt.close()
                } catch (e: SecurityException) { /* ignore */ }
                if (bluetoothGatt == gatt) bluetoothGatt = null
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                // Request larger MTU for the 128-byte CSV strings
                try {
                    gatt.requestMtu(128)
                } catch (e: SecurityException) { /* ignore */ }
                
                val service = gatt.getService(java.util.UUID.fromString("12345678-1234-5678-1234-56789abcdef0"))
                val characteristic = service?.getCharacteristic(java.util.UUID.fromString("12345678-1234-5678-1234-56789abcdef1"))
                
                if (characteristic != null) {
                    try {
                        gatt.setCharacteristicNotification(characteristic, true)
                        val descriptor = characteristic.getDescriptor(java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
                        if (descriptor != null) {
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            gatt.writeDescriptor(descriptor)
                            handler.post { _uiState.value = _uiState.value.copy(bleStatus = "Streaming Active") }
                        }
                    } catch (e: SecurityException) {
                        handler.post { _uiState.value = _uiState.value.copy(bleStatus = "Notification Error") }
                    }
                } else {
                    handler.post { _uiState.value = _uiState.value.copy(bleStatus = "Char not found") }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            addLog("BLE MTU changed to $mtu")
        }

        // Modern API 33+ version
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            handleDirectData(value)
        }

        // Legacy version for older Android
        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            val data = characteristic.value
            if (data != null) {
                handleDirectData(data)
            }
        }
    }

    private fun handleDirectData(data: ByteArray) {
        processUniversalBinary(data, "DIRECT")
    }

    private fun processUniversalBinary(data: ByteArray, source: String) {
        val currentState = _uiState.value
        
        when {
            // 1. Pico Relay / SensorPacket (46 bytes)
            // Format: 6 floats (ax,ay,az,gx,gy,gz) + 3 int16 (mx,my,mz) + 4 floats (roll,pitch,yaw,force_kg)
            data.size == 46 -> {
                val pico = decodeSensorPacket(data)
                
                // Extract values safely
                val ax = pico["ax"] as? Float ?: 0f
                val ay = pico["ay"] as? Float ?: 0f
                val az = pico["az"] as? Float ?: 0f
                val gx = pico["gx"] as? Float ?: 0f
                val gy = pico["gy"] as? Float ?: 0f
                val gz = pico["gz"] as? Float ?: 0f
                val mx = pico["mx"] as? Int ?: 0
                val my = pico["my"] as? Int ?: 0
                val mz = pico["mz"] as? Int ?: 0
                val roll = pico["roll"] as? Float ?: 0f
                val pitch = pico["pitch"] as? Float ?: 0f
                val yaw = pico["yaw"] as? Float ?: 0f
                val forceKg = pico["force_kg"] as? Float ?: 0f

                // Update weight bearing based on force_kg (assuming limit is currentState.weightLimit)
                val weightLimit = currentState.weightLimit.takeIf { it > 0 } ?: 50f
                val wbPercentage = (forceKg / weightLimit).coerceIn(0f, 1f)
                val newWeightHistory = (currentState.weightHistory + wbPercentage).takeLast(30)

                // Map for UI display
                val uiMap = mapOf(
                    "ax_g" to ax, "ay_g" to ay, "az_g" to az,
                    "gx_dps" to gx, "gy_dps" to gy, "gz_dps" to gz,
                    "mx_raw" to mx, "my_raw" to my, "mz_raw" to mz,
                    "roll" to roll, "pitch" to pitch, "yaw" to yaw,
                    "force_kg" to forceKg,
                    "seq" to 0
                )

                _uiState.value = currentState.copy(
                    weightBearing = wbPercentage,
                    weightHistory = newWeightHistory,
                    latestGm1Data = uiMap,
                    lastReceivedType = "GM1",
                    directImuData = pico.filterValues { it is Float }.mapValues { it.value as Float }
                )
                
                addLog("[$source] Recv 46B: Force=%.2fkg, Roll=%.1f".format(forceKg, roll))

                // Save to SQLite if recording
                if (currentState.isRecording && currentSessionId != -1L) {
                    val dbPico = pico.filterValues { it is Float }.mapValues { it.value as Float }.toMutableMap()
                    dbPico["mx"] = mx.toFloat()
                    dbPico["my"] = my.toFloat()
                    dbPico["mz"] = mz.toFloat()
                    saveReadingToDb(dbPico)
                }
            }

            // Legacy support or fallback for 30 bytes
            data.size == 30 -> {
                val pico = decodeSensorPacket(data)
                val ax = pico["ax"] as? Float ?: 0f
                val ay = pico["ay"] as? Float ?: 0f
                val az = pico["az"] as? Float ?: 0f
                
                val uiMap = pico.toMutableMap()
                uiMap["ax_g"] = ax
                uiMap["ay_g"] = ay
                uiMap["az_g"] = az
                uiMap["seq"] = 0

                val pseudoWeight = Math.abs(az).coerceIn(0f, 1f)
                val newWeightHistory = (currentState.weightHistory + pseudoWeight).takeLast(30)

                _uiState.value = currentState.copy(
                    latestGm1Data = uiMap,
                    lastReceivedType = "GM1",
                    weightBearing = pseudoWeight,
                    weightHistory = newWeightHistory
                )
                addLog("[$source] Recv 30B (Legacy)")
            }

            // 2. EnviroMonitor / L2S2 Binary Tag (22 bytes)
            // Format: starts with 0x01 0x84 (Timestamp tag)
            data.size == 22 && data[0] == 0x01.toByte() && data[1] == 0x84.toByte() -> {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                // Skip timestamp tag (2) + timestamp (8) + temp tag (2)
                val temp = buffer.getFloat(12)
                // Skip humidity tag (2)
                val hum = buffer.getFloat(18)
                
                _uiState.value = currentState.copy(
                    temperature = temp,
                    humidity = hum,
                    lastReceivedType = "SENSOR"
                )
                addLog(String.format(Locale.US, "[%s] Sensor: %.1f°C, %.1f%% Humidity", source, temp, hum))
            }

            // 3. Experimental (32 bytes) - Format: <Hffffffhhh
            data.size == 32 && currentState.isExperimentalDecodingEnabled -> {
                val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
                val seq = buffer.short.toInt() and 0xFFFF
                val pico = mapOf(
                    "ax" to buffer.float,
                    "ay" to buffer.float,
                    "az" to buffer.float,
                    "gx" to buffer.float,
                    "gy" to buffer.float,
                    "gz" to buffer.float,
                    "mx" to buffer.short.toFloat(),
                    "my" to buffer.short.toFloat(),
                    "mz" to buffer.short.toFloat()
                )
                
                val uiMap = mapOf(
                    "ax_g" to (pico["ax"] ?: 0f),
                    "ay_g" to (pico["ay"] ?: 0f),
                    "az_g" to (pico["az"] ?: 0f),
                    "gx_dps" to (pico["gx"] ?: 0f),
                    "gy_dps" to (pico["gy"] ?: 0f),
                    "gz_dps" to (pico["gz"] ?: 0f),
                    "mx_raw" to (pico["mx"]?.toInt() ?: 0),
                    "my_raw" to (pico["my"]?.toInt() ?: 0),
                    "mz_raw" to (pico["mz"]?.toInt() ?: 0),
                    "seq" to seq
                )

                _uiState.value = currentState.copy(
                    latestGm1Data = uiMap,
                    lastReceivedType = "GM1",
                    directImuData = pico,
                    directHistory = (currentState.directHistory + pico).takeLast(100)
                )
                
                addLog(String.format(Locale.US, "[%s] Exp #%d: A(%.2f,%.2f,%.2f)", source, seq, pico["ax"], pico["ay"], pico["az"]))
            }

            // 3. Raw Debug Mode
            currentState.isRawStreamEnabled -> {
                val bytes = data.map { it.toInt() and 0xFF }
                _uiState.value = currentState.copy(latestNumbers = bytes, lastReceivedType = "RAW")
                addLog("[$source] Raw: ${bytes.take(8).joinToString(", ")}... (${data.size}B)")
            }

            else -> {
                addLog("[$source] Unknown Format: ${data.size}B")
            }
        }
    }

    private fun decodeSensorPacket(data: ByteArray): Map<String, Any> {
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        return mapOf(
            "ax" to buffer.float,
            "ay" to buffer.float,
            "az" to buffer.float,
            "gx" to buffer.float,
            "gy" to buffer.float,
            "gz" to buffer.float,
            "mx" to buffer.short.toInt(),
            "my" to buffer.short.toInt(),
            "mz" to buffer.short.toInt(),
            "roll" to buffer.float,
            "pitch" to buffer.float,
            "yaw" to buffer.float,
            "force_kg" to buffer.float
        )
    }

    private fun saveReadingToDb(imuMap: Map<String, Float>) {
        dbHelper?.let { db ->
            val now = Date()
            val timeVal = now.time.toDouble() / 1000.0
            
            val values = ContentValues().apply {
                put(SensorDatabaseHelper.COLUMN_READ_SESS_ID, currentSessionId)
                put(SensorDatabaseHelper.COLUMN_TIMESTAMP, timeVal)
                put(SensorDatabaseHelper.COLUMN_AX, imuMap["ax"])
                put(SensorDatabaseHelper.COLUMN_AY, imuMap["ay"])
                put(SensorDatabaseHelper.COLUMN_AZ, imuMap["az"])
                put(SensorDatabaseHelper.COLUMN_GX, imuMap["gx"])
                put(SensorDatabaseHelper.COLUMN_GY, imuMap["gy"])
                put(SensorDatabaseHelper.COLUMN_GZ, imuMap["gz"])
                put(SensorDatabaseHelper.COLUMN_MX, imuMap["mx"])
                put(SensorDatabaseHelper.COLUMN_MY, imuMap["my"])
                put(SensorDatabaseHelper.COLUMN_MZ, imuMap["mz"])
            }
            db.insertReading(values)
        }
    }

    fun setBatchLabels(gait: String, terrain: String, patientId: String) {
        _uiState.value = _uiState.value.copy(
            batchGait = gait, 
            batchTerrain = terrain,
            patientId = patientId
        )
    }

    fun setPatientMetadata(age: String, gender: String, weight: String, leg: String, name: String) {
        _uiState.value = _uiState.value.copy(
            patientAge = age,
            patientGender = gender,
            patientWeight = weight,
            injuredLeg = leg,
            patientName = name
        )
    }

    fun startRecording(context: android.content.Context) {
        try {
            val currentState = _uiState.value
            val now = Date()
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
            
            // 1. Initialize SQLite Database for this patient
            val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val helper = SensorDatabaseHelper(context, currentState.patientId, documentsDir)
            dbHelper = helper
            
            // Get or create Day Layer
            val dayId = helper.getOrInsertDay(dateStr)
            val nextIndex = helper.getNextSessionIndex(dayId)
            
            // 2. Create Session Record
            val sessionValues = ContentValues().apply {
                put(SensorDatabaseHelper.COLUMN_SESS_DAY_ID, dayId)
                put(SensorDatabaseHelper.COLUMN_SESS_INDEX, nextIndex)
                put(SensorDatabaseHelper.COLUMN_START_TIME, SimpleDateFormat("HH:mm:ss", Locale.US).format(now))
                put(SensorDatabaseHelper.COLUMN_GAIT, currentState.batchGait)
                put(SensorDatabaseHelper.COLUMN_TERRAIN, currentState.batchTerrain)
            }
            currentSessionId = helper.insertSession(sessionValues)
            
            // 3. Update Patient Metadata
            val metaDb = PatientMetadataDatabaseHelper(context, documentsDir)
            val metaValues = ContentValues().apply {
                put(PatientMetadataDatabaseHelper.COLUMN_PATIENT_ID, currentState.patientId)
                put(PatientMetadataDatabaseHelper.COLUMN_NAME, currentState.patientName)
                put(PatientMetadataDatabaseHelper.COLUMN_AGE, currentState.patientAge.toIntOrNull() ?: 0)
                put(PatientMetadataDatabaseHelper.COLUMN_GENDER, currentState.patientGender)
                put(PatientMetadataDatabaseHelper.COLUMN_BODY_WEIGHT, currentState.patientWeight.toFloatOrNull() ?: 0f)
                put(PatientMetadataDatabaseHelper.COLUMN_INJURED_LEG, currentState.injuredLeg)
                put(PatientMetadataDatabaseHelper.COLUMN_TIMESTAMP, SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now))
            }
            metaDb.insertMetadata(metaValues)
            metaDb.close()
            
            _uiState.value = _uiState.value.copy(
                isRecording = true,
                sessionIndex = nextIndex
            )
            addLog("Recording started: Day $dateStr | Session $nextIndex")
        } catch (e: Exception) {
            addLog("Recording failed: ${e.message}")
            android.util.Log.e("DashboardViewModel", "Start recording error", e)
        }
    }

    fun stopRecording() {
        dbHelper?.close()
        dbHelper = null
        currentSessionId = -1
        _uiState.value = _uiState.value.copy(isRecording = false)
        addLog("Recording stopped. Session data saved to SQLite.")
    }

    fun shareLatestData(context: android.content.Context) {
        val state = _uiState.value
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
        
        val dbFile = File(documentsDir, "${state.patientId}.db")
        val metaDbFile = File(documentsDir, "PatientMetadata.db")

        val filesToShare = mutableListOf<File>()
        if (dbFile.exists()) filesToShare.add(dbFile)
        if (metaDbFile.exists()) filesToShare.add(metaDbFile)

        if (filesToShare.isEmpty()) {
            addLog("Error: No databases found for sharing")
            android.widget.Toast.makeText(context, "No database found for Patient ${state.patientId}", android.widget.Toast.LENGTH_LONG).show()
            return
        }

        val uris = filesToShare.map {
            FileProvider.getUriForFile(
                context,
                "com.example.smartcrutch.fileprovider",
                it
            )
        }

        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/octet-stream"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            putExtra(Intent.EXTRA_SUBJECT, "SmartCrutch Full History: Patient ${state.patientId}")
            putExtra(Intent.EXTRA_TEXT, "Attached is the full sensor history for Patient ${state.patientId} (${state.patientName}) and the Metadata record.")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, "Send Patient History")
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(chooser)
    }

    fun clearAllLocalData(context: android.content.Context) {
        val documentsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: return
        val files = documentsDir.listFiles() ?: return
        
        var deletedCount = 0
        files.forEach { file ->
            if (file.name.endsWith(".db") || file.name.endsWith(".db-shm") || file.name.endsWith(".db-wal")) {
                if (file.delete()) {
                    deletedCount++
                }
            }
        }
        
        addLog("Cleared $deletedCount local database files.")
        android.widget.Toast.makeText(context, "Deleted $deletedCount database files.", android.widget.Toast.LENGTH_SHORT).show()
    }

    fun navigateTo(screen: Screen) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun toggleRawStream(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isRawStreamEnabled = enabled)
        addLog("Raw Stream Debug ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun toggleExperimentalDecoding(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isExperimentalDecodingEnabled = enabled)
        addLog("Experimental Decoding ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun syncData() {
        fetchFromNgrok()
    }

    private fun startLiveFeed() {
        // Disabled: No background connection or token requests
        _uiState.value = _uiState.value.copy(syncStatus = "Local Mode")
    }

    /*
    private fun processIncomingData(dataList: List<InstrumentData>) {
        dataList.sortedBy { it.deviceDataId }.forEach { data ->
            val rawData = try {
                android.util.Base64.decode(data.dataValue, android.util.Base64.DEFAULT)
            } catch (e: Exception) { return@forEach }

            processUniversalBinary(rawData, "API")
        }
    }
    */

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLogs = (listOf("[$timestamp] $message") + _uiState.value.liveLogs).take(50)
        _uiState.value = _uiState.value.copy(liveLogs = newLogs)
    }

    fun toggleDeveloperMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isDeveloperModeEnabled = enabled)
        addLog("Developer Mode ${if (enabled) "ENABLED" else "DISABLED"}")
    }

    fun fetchFromNgrok() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                withContext(Dispatchers.Main) {
                    _uiState.value = _uiState.value.copy(isSyncing = true, syncStatus = "Syncing...")
                }
                addLog("Fetching patient data from ngrok...")
                val urlString = "https://lucrative-alienate-settling.ngrok-free.dev/patient_app_data.json"
                val url = URL(urlString)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                
                val responseText = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseText)
                
                // Parse nested metrics
                val metrics = root.optJSONObject("metrics") ?: JSONObject()
                val stepCount = metrics.optInt("stepCount", 0)
                val stepTarget = metrics.optInt("dailyStepTarget", 4000)
                val weightBearing = metrics.optDouble("weightBearingPctBW", 0.0).toFloat() / 100f
                val weightLimit = metrics.optDouble("prescribedWeightBearingPctBW", 50.0).toFloat()
                val detectedGait = metrics.optString("detectedGait", "Not Detected")
                val prescribedGait = metrics.optString("prescribedGait", "Swing")
                val cusi = metrics.optInt("cusi", 0)
                val message = root.optString("message", "")
                
                withContext(Dispatchers.Main) {
                    val currentState = _uiState.value
                    val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                    val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date())
                    
                    // Only show popup if it's a NEW message AND we haven't shown a popup today yet
                    // Or just if it's a new message? User said "just once is enough per day"
                    val isNewMessage = message.isNotEmpty() && 
                                     message != currentState.lastShownMessage && 
                                     today != currentState.lastShownDate
                    
                    _uiState.value = currentState.copy(
                        lastNgrokData = "Sync Successful: ${root.optString("timestamp")}",
                        isSyncing = false,
                        lastSyncTime = now,
                        syncStatus = "Connected",
                        steps = stepCount,
                        goalSteps = stepTarget,
                        weightBearing = weightBearing,
                        weightLimit = weightLimit,
                        gaitPattern = detectedGait,
                        selectedGait = prescribedGait,
                        wristStrainIndex = cusi, // Updated to follow 'cusi' key
                        recoveryScore = 65,
                        syncMessage = message,
                        showSyncPopup = isNewMessage,
                        lastShownMessage = message,
                        lastShownDate = if (isNewMessage) today else currentState.lastShownDate
                    )
                    if (isNewMessage) {
                        addLog("New Clinician Message received for today.")
                    } else {
                        addLog("Dashboard updated (Background sync).")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    val errorMsg = e.message ?: "Unknown error"
                    addLog("Fetch failed: $errorMsg")
                    _uiState.value = _uiState.value.copy(
                        isSyncing = false,
                        syncStatus = "Error",
                        lastNgrokData = "Error: $errorMsg"
                    )
                }
            }
        }
    }

    private fun startAutoUpdate() {
        viewModelScope.launch {
            while (true) {
                delay(30000) // 30 seconds
                if (!_uiState.value.isSyncing) {
                    addLog("Automatic 30s update triggered")
                    syncData()
                }
            }
        }
    }

    fun updateGoalSteps(steps: Int) {
        _uiState.value = _uiState.value.copy(goalSteps = steps)
        addLog("Daily Step Goal updated to $steps")
    }

    fun updateWeightLimit(limit: Float) {
        _uiState.value = _uiState.value.copy(weightLimit = limit)
        addLog("Weight Limit updated to $limit kg")
    }

    fun updatePrescribedGait(gait: String) {
        _uiState.value = _uiState.value.copy(selectedGait = gait)
        addLog("Prescribed Gait updated to $gait")
    }

    fun updateDetectedGait(gait: String) {
        val currentState = _uiState.value
        val isMismatch = gait != "Not Detected" && 
                        currentState.selectedGait.isNotBlank() && 
                        !currentState.selectedGait.equals(gait, ignoreCase = true)
        
        _uiState.value = currentState.copy(
            gaitPattern = gait,
            showGaitMismatchPopup = isMismatch
        )
        addLog("Detected Gait updated to $gait ${if (isMismatch) "(MISMATCH)" else ""}")
    }

    fun dismissGaitMismatchPopup() {
        _uiState.value = _uiState.value.copy(showGaitMismatchPopup = false)
    }

    fun dismissSplash() {
        _uiState.value = _uiState.value.copy(showSplash = false)
    }

    fun dismissSyncPopup() {
        _uiState.value = _uiState.value.copy(showSyncPopup = false)
    }

    fun toggleDarkMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(isDarkMode = enabled)
    }

    override fun onCleared() {
        super.onCleared()
        disconnectBle()
        stopPicoRelayMode()
    }
}
