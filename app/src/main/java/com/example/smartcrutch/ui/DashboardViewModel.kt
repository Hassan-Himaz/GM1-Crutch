package com.example.smartcrutch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartcrutch.data.model.InstrumentData
import com.example.smartcrutch.data.remote.HarvesterClient
import com.example.smartcrutch.data.repository.HarvesterRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class Screen {
    Home, Progress, Sync, Profile
}

data class DashboardUiState(
    val currentScreen: Screen = Screen.Home,
    val weightBearing: Float = 0.78f,
    val steps: Int = 3241,
    val goalSteps: Int = 4000,
    val weightLimit: Float = 50f, // Target weight limit in kg
    val recoveryGoal: String = "Partial Weight Bearing",
    val organizationName: String = "St. Mary's Orthopedics",
    val gaitPattern: String = "3-Point",
    val wristStrain: Float = 0.34f,
    val humidity: Float = 45.0f,
    val temperature: Float = 22.5f,
    val isSyncing: Boolean = false,
    val lastSyncTime: String = "Never",
    val syncStatus: String = "Disconnected",
    val liveLogs: List<String> = emptyList(),
    val isLiveFeedActive: Boolean = false,
    val weightHistory: List<Float> = listOf(0.2f, 0.4f, 0.35f, 0.6f, 0.55f, 0.8f, 0.75f, 0.78f)
)

class DashboardViewModel : ViewModel() {

    private val repository = HarvesterRepository()
    
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        // Automatically start login and live feed
        startLiveFeed()
    }

    fun navigateTo(screen: Screen) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun syncData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncStatus = "Syncing...")
            
            // Perform actual login before syncing
            val loginSuccess = HarvesterClient.login()
            
            if (loginSuccess) {
                delay(1000) // Small delay for UX
                val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                _uiState.value = _uiState.value.copy(
                    isSyncing = false,
                    lastSyncTime = now,
                    syncStatus = "Connected",
                    steps = _uiState.value.steps + (5..20).random()
                )
                addLog("Manual sync successful at $now")
            } else {
                _uiState.value = _uiState.value.copy(isSyncing = false, syncStatus = "Auth Failed")
                addLog("Error: Authentication failed during sync.")
            }
        }
    }

    private fun startLiveFeed() {
        viewModelScope.launch {
            addLog("Starting initial login...")
            val loginSuccess = HarvesterClient.login()
            if (!loginSuccess) {
                addLog("Auth Failed. Retrying in 10s...")
                delay(10000)
                startLiveFeed()
                return@launch
            }
            
            addLog("Auth Successful. Live feed active.")
            _uiState.value = _uiState.value.copy(isLiveFeedActive = true, syncStatus = "Connected")

            // Monitor Live Feed with specific instrument identifier
            repository.getLiveFeed("4779fbb9b035ce55").collect { dataList ->
                if (dataList.isEmpty()) {
                    addLog("Poll complete: No new data.")
                } else {
                    addLog("Received ${dataList.size} new packets.")
                    processIncomingData(dataList)
                }
            }
        }
    }

    private fun processIncomingData(dataList: List<InstrumentData>) {
        dataList.sortedBy { it.deviceDataId }.forEach { data ->
            val bytes = android.util.Base64.decode(data.dataValue, android.util.Base64.DEFAULT)
            
            // Sensor data is at the end of the packet: 'a' [4B Hum] '\n' 'a' [4B Temp]
            // Header length varies, but the payload suffix is always 11 bytes.
            // Offset for Humidity: size - 10
            // Offset for Temperature: size - 4
            val humidity = extractFloat(bytes, bytes.size - 10)
            val temperature = extractFloat(bytes, bytes.size - 4)
            
            if (humidity != null && temperature != null) {
                addLog(String.format(java.util.Locale.US, "Packet (%dB): Hum=%.1f%%, Temp=%.1f°C", bytes.size, humidity, temperature))
                
                // Update UI metrics
                val normalizedWeight = (humidity / 100f).coerceIn(0f, 1f)
                val newHistory = (_uiState.value.weightHistory + normalizedWeight).takeLast(20)
                
                _uiState.value = _uiState.value.copy(
                    weightBearing = normalizedWeight,
                    weightHistory = newHistory,
                    humidity = humidity,
                    temperature = temperature,
                    steps = _uiState.value.steps + 1
                )
                
                runPythonAnalytics(listOf(humidity.toDouble(), temperature.toDouble()))
            } else {
                val hex = bytes.joinToString("") { String.format("%02X", it) }
                addLog("Invalid Packet (${bytes.size}B): $hex")
            }
        }
    }

    private fun extractFloat(bytes: ByteArray, offset: Int): Float? {
        if (offset + 4 > bytes.size) return null
        return try {
            java.nio.ByteBuffer.wrap(bytes, offset, 4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .float
        } catch (e: Exception) {
            null
        }
    }

    private fun runPythonAnalytics(values: List<Double>) {
        try {
            val py = com.chaquo.python.Python.getInstance()
            val module = py.getModule("analytics")
            val result = module.callAttr("process_sensor_data", values).asMap()
            
            val status = result[com.chaquo.python.PyObject.fromJava("status")]?.toString() ?: "Unknown"
            addLog("Python Analysis: $status")
        } catch (e: Exception) {
            // Python not ready or error
        }
    }

    private fun decodePayload(payload: String): String {
        return try {
            val data = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
            data.joinToString(" ") { String.format("%02X", it) }
        } catch (e: Exception) {
            "Error"
        }
    }

    private fun addLog(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val newLogs = (listOf("[$timestamp] $message") + _uiState.value.liveLogs).take(50)
        _uiState.value = _uiState.value.copy(liveLogs = newLogs)
    }
}

private fun ClosedRange<Float>.random() = 
    kotlin.random.Random.nextFloat() * (endInclusive - start) + start
