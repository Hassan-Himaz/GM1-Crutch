package com.example.smartcrutch.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smartcrutch.data.model.InstrumentData
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
    val gaitPattern: String = "3-Point",
    val wristStrain: Float = 0.34f,
    val isSyncing: Boolean = false,
    val lastSyncTime: String = "Never",
    val syncStatus: String = "Disconnected",
    val recentData: List<InstrumentData> = emptyList()
)

class DashboardViewModel : ViewModel() {

    private val repository = HarvesterRepository()
    
    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    fun navigateTo(screen: Screen) {
        _uiState.value = _uiState.value.copy(currentScreen = screen)
    }

    fun syncData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true, syncStatus = "Syncing...")
            
            // Simulate network delay
            delay(2000)
            
            // In a real scenario, we would call repository.pollData here
            // For now, we simulate success
            val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                lastSyncTime = now,
                syncStatus = "Connected",
                // Simulate some new data
                steps = _uiState.value.steps + (10..50).random()
            )
        }
    }

    fun startLiveFeed() {
        viewModelScope.launch {
            repository.getLiveFeed("instrument_123").collect { data ->
                _uiState.value = _uiState.value.copy(
                    recentData = (_uiState.value.recentData + data).takeLast(10)
                )
            }
        }
    }
}
