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
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    val gaitPattern: String = "3-Point",
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
    val patientName: String = ""
)

class DashboardViewModel : ViewModel() {

    private val repository = HarvesterRepository()
    
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothGatt: BluetoothGatt? = null
    private val handler = Handler(Looper.getMainLooper())
    
    private var dbHelper: SensorDatabaseHelper? = null
    private var currentSessionId: Long = -1
    private var logWriter: PrintWriter? = null

    init {
        startLiveFeed()
    }

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
        val currentState = _uiState.value
        
        // 1. Try Experimental Binary Detection (32 bytes, 0xAABB header)
        if (data.size == 32 || currentState.isExperimentalDecodingEnabled) {
            val experimental = decodeExperimentalBinary(data)
            if (experimental != null) {
                val imuMap = experimental.filterValues { it is Float || it is Double }.mapValues { 
                    (it.value as? Float) ?: (it.value as Double).toFloat() 
                }
                updateDirectState(imuMap)
                return
            }
        }

        // 2. Fallback to CSV String
        val csv = String(data)
        parseDirectCsv(csv)
    }

    fun updateDirectState(imuMap: Map<String, Float>) {
        val currentState = _uiState.value
        val newHistory = (currentState.directHistory + imuMap).takeLast(100)
        
        _uiState.value = currentState.copy(
            directImuData = imuMap,
            directHistory = newHistory,
            lastReceivedType = "DIRECT"
        )
        
        // Save to SQLite if recording
        if (currentState.isRecording && currentSessionId != -1L) {
            dbHelper?.let { db ->
                val now = Date()
                val timeVal = now.time.toDouble() / 1000.0 // Seconds precision
                
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
    }

    private fun decodeExperimentalBinary(data: ByteArray): Map<String, Any>? {
        if (data.size != 32) return null
        return try {
            val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val header = buffer.short.toInt() and 0xFFFF
            
            // Allow override if toggle is ON, but check header by default
            if (header != 0xAABB && !_uiState.value.isExperimentalDecodingEnabled) return null

            mapOf(
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
        } catch (e: Exception) {
            null
        }
    }

    private fun parseDirectCsv(csv: String) {
        try {
            val parts = csv.split(",")
            if (parts.size >= 9) {
                val imuMap = mapOf(
                    "ax" to parts[0].trim().toFloat(),
                    "ay" to parts[1].trim().toFloat(),
                    "az" to parts[2].trim().toFloat(),
                    "gx" to parts[3].trim().toFloat(),
                    "gy" to parts[4].trim().toFloat(),
                    "gz" to parts[5].trim().toFloat(),
                    "mx" to parts[6].trim().toFloat(),
                    "my" to parts[7].trim().toFloat(),
                    "mz" to parts[8].trim().toFloat()
                )
                updateDirectState(imuMap)
            }
        } catch (e: Exception) {
            // Parsing error
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
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncStatus = "Syncing...")
            val loginSuccess = HarvesterClient.login()
            if (loginSuccess) {
                delay(1000)
                val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                _uiState.value = _uiState.value.copy(isSyncing = false, lastSyncTime = now, syncStatus = "Connected")
                addLog("Manual sync successful at $now")
            } else {
                _uiState.value = _uiState.value.copy(isSyncing = false, syncStatus = "Auth Failed")
            }
        }
    }

    private fun startLiveFeed() {
        viewModelScope.launch {
            val loginSuccess = HarvesterClient.login()
            if (!loginSuccess) {
                delay(10000)
                startLiveFeed()
                return@launch
            }
            
            _uiState.value = _uiState.value.copy(isLiveFeedActive = true, syncStatus = "Connected")

            repository.getLiveFeed("4779fbb9b035ce55").collect { dataList ->
                if (dataList.isNotEmpty()) {
                    processIncomingData(dataList)
                }
            }
        }
    }

    private fun processIncomingData(dataList: List<InstrumentData>) {
        dataList.sortedBy { it.deviceDataId }.forEach { data ->
            val rawData = try {
                android.util.Base64.decode(data.dataValue, android.util.Base64.DEFAULT)
            } catch (e: Exception) { return@forEach }

            val currentState = _uiState.value

            // 1. Identify Format (Hard-Coded Protocol Logic)
            // We prioritize the 22-byte I9h structure (Seq + 9 axes) 
            // over whatever the server labels the instrument as.
            val isGm1Protocol = rawData.size == 22 && rawData[rawData.size - 2] != '\n'.code.toByte()
            
            val isSensorProtocol = rawData.size >= 22 && rawData[rawData.size - 11] == 'a'.code.toByte()
            
            val isExperimentalProtocol = rawData.size == 32 && 
                (java.nio.ByteBuffer.wrap(rawData).order(java.nio.ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF == 0xAABB)

            when {
                currentState.isRawStreamEnabled -> {
                    val bytes = rawData.map { it.toInt() and 0xFF }
                    _uiState.value = _uiState.value.copy(latestNumbers = bytes, lastReceivedType = "RAW")
                    addLog("Raw [${data.instrumentType ?: "Unknown"}]: ${bytes.take(8).joinToString(", ")}...")
                }

                isExperimentalProtocol || (currentState.isExperimentalDecodingEnabled && rawData.size == 32) -> {
                    val exp = decodeExperimentalBinary(rawData)
                    if (exp != null) {
                        val numbers = listOf(
                            0, // Seq dummy
                            (exp["ax"] as Float * 1000).toInt(),
                            (exp["ay"] as Float * 1000).toInt(),
                            (exp["az"] as Float * 1000).toInt(),
                            (exp["gx"] as Float * 100).toInt(),
                            (exp["gy"] as Float * 100).toInt(),
                            (exp["gz"] as Float * 100).toInt(),
                            (exp["mx"] as Float).toInt(),
                            (exp["my"] as Float).toInt(),
                            (exp["mz"] as Float).toInt()
                        )
                        _uiState.value = _uiState.value.copy(
                            latestGm1Data = exp,
                            latestNumbers = numbers,
                            lastReceivedType = "GM1"
                        )
                        addLog("Experimental Protocol Detected (32B)")
                    }
                }

                isGm1Protocol -> {
                    val gm1 = decodeGm1Binary(rawData)
                    // Hard-code the 10 values into our numeric stream
                    val numbers = listOf(
                        (gm1["seq"] as? Number ?: 0).toInt(),
                        ((gm1["ax_g"] as? Double ?: 0.0) * 1000).toInt(), // scaled for integer list
                        ((gm1["ay_g"] as? Double ?: 0.0) * 1000).toInt(),
                        ((gm1["az_g"] as? Double ?: 0.0) * 1000).toInt(),
                        ((gm1["gx_dps"] as? Double ?: 0.0) * 100).toInt(),
                        ((gm1["gy_dps"] as? Double ?: 0.0) * 100).toInt(),
                        ((gm1["gz_dps"] as? Double ?: 0.0) * 100).toInt(),
                        (gm1["mx_raw"] as? Int ?: 0),
                        (gm1["my_raw"] as? Int ?: 0),
                        (gm1["mz_raw"] as? Int ?: 0)
                    )
                    _uiState.value = _uiState.value.copy(
                        latestGm1Data = gm1, 
                        latestNumbers = numbers,
                        lastReceivedType = "GM1"
                    )
                    addLog(String.format(java.util.Locale.US, "GM1 Protocol Verified (Seq: %d)", gm1["seq"]))
                }

                isSensorProtocol -> {
                    val humidity = extractFloat(rawData, rawData.size - 10)
                    val temperature = extractFloat(rawData, rawData.size - 4)
                    if (humidity != null && temperature != null) {
                        _uiState.value = _uiState.value.copy(
                            humidity = humidity,
                            temperature = temperature,
                            latestNumbers = rawData.map { it.toInt() and 0xFF },
                            lastReceivedType = "SENSOR"
                        )
                        addLog(String.format(java.util.Locale.US, "Sensor Auto-Parsed: %.1f°C", temperature))
                    }
                }

                else -> {
                    addLog("Unknown Data: ${data.instrumentType ?: "Null"} (${rawData.size}B)")
                }
            }
        }
    }

    private fun decodeGm1Binary(data: ByteArray): Map<String, Any> {
        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val seq = buffer.int
        val ax = buffer.short
        val ay = buffer.short
        val az = buffer.short
        val gx = buffer.short
        val gy = buffer.short
        val gz = buffer.short
        val mx = buffer.short
        val my = buffer.short
        val mz = buffer.short
        
        return mapOf(
            "seq" to seq,
            "ax_g" to ax / 1000.0,
            "ay_g" to ay / 1000.0,
            "az_g" to az / 1000.0,
            "gx_dps" to gx / 100.0,
            "gy_dps" to gy / 100.0,
            "gz_dps" to gz / 100.0,
            "mx_raw" to mx.toInt(),
            "my_raw" to my.toInt(),
            "mz_raw" to mz.toInt()
        )
    }

    private fun extractFloat(bytes: ByteArray, offset: Int): Float? {
        if (offset + 4 > bytes.size) return null
        return try {
            java.nio.ByteBuffer.wrap(bytes, offset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .float
        } catch (e: Exception) { null }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLogs = (listOf("[$timestamp] $message") + _uiState.value.liveLogs).take(50)
        _uiState.value = _uiState.value.copy(liveLogs = newLogs)
    }

    override fun onCleared() {
        super.onCleared()
        disconnectBle()
    }
}
