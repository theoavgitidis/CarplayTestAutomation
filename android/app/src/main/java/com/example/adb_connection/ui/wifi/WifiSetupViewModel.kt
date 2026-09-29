package com.example.adb_connection.ui.wifi

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.adb_connection.data.debug.DebugLogger
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.data.wifi.ConnectionStatus
import com.example.adb_connection.data.wifi.WifiConnectionRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WifiSetupUiState(
    val currentSsid: String? = null,
    val connectionStatus: ConnectionStatus = ConnectionStatus.IDLE,
    val showConnectionErrorDialog: Boolean = false,
    val showPermissionDialog: Boolean = false,
    val showLocationServicesDialog: Boolean = false,
    val showSuccessAnimation: Boolean = false,
    val showDisconnectedAnimation: Boolean = false,
    val connectedNetworkName: String = "",
    val isDebugModeEnabled: Boolean = false,
    val isAutoNavEnabled: Boolean = false,
    val logs: List<String> = emptyList()
)

sealed interface WifiSetupEvent {
    data object OpenNativeCamera : WifiSetupEvent
    data object NavigateToHome : WifiSetupEvent
}

class WifiSetupViewModel(
    private val wifiRepository: WifiConnectionRepository,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(WifiSetupUiState())
    val uiState: StateFlow<WifiSetupUiState> = _uiState.asStateFlow()

    private val _events = Channel<WifiSetupEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    // Set to true when the user taps the scan button; cleared after animation is triggered.
    private var scanInitiated = false
    private var animationDismissJob: Job? = null
    private var autoNavigationJob: Job? = null

    init {
        // Observe Connection Status
        viewModelScope.launch {
            wifiRepository.connectionStatus.collect { status ->
                animationDismissJob?.cancel()
                autoNavigationJob?.cancel()
                _uiState.update { it.copy(connectionStatus = status) }

                when (status) {
                    ConnectionStatus.CONNECTED -> {
                        if (scanInitiated) {
                            scanInitiated = false
                            val ssid = wifiRepository.currentSsid.value ?: "WiFi"
                            DebugLogger.log("Successfully connected to: $ssid")
                            _uiState.update {
                                it.copy(
                                    connectedNetworkName = ssid,
                                    showSuccessAnimation = true
                                )
                            }
                            animationDismissJob = viewModelScope.launch {
                                delay(10_000)
                                dismissAnimations()
                            }
                        }

                        if (_uiState.value.isAutoNavEnabled) {
                            autoNavigationJob = viewModelScope.launch {
                                delay(2000)
                                _events.send(WifiSetupEvent.NavigateToHome)
                            }
                        }
                    }
                    ConnectionStatus.DISCONNECTED -> {
                        if (_uiState.value.isAutoNavEnabled) {
                            _uiState.update { it.copy(showDisconnectedAnimation = true) }
                            animationDismissJob = viewModelScope.launch {
                                delay(5000)
                                dismissAnimations()
                            }
                        }
                    }
                    ConnectionStatus.ERROR -> {
                        _uiState.update { it.copy(showConnectionErrorDialog = true) }
                    }
                    else -> {}
                }
            }
        }

        // Observe Debug Mode
        viewModelScope.launch {
            settingsRepository.isDebugModeEnabled.collect { enabled ->
                _uiState.update { it.copy(isDebugModeEnabled = enabled) }
            }
        }

        // Observe Auto Nav
        viewModelScope.launch {
            settingsRepository.isAutoNavigationEnabled.collect { enabled ->
                _uiState.update { it.copy(isAutoNavEnabled = enabled) }
            }
        }

        // Observe Logs
        viewModelScope.launch {
            DebugLogger.logs.collect { logs ->
                _uiState.update { it.copy(logs = logs) }
            }
        }

        // Observe SSID
        viewModelScope.launch {
            wifiRepository.currentSsid.collect { ssid ->
                _uiState.update { it.copy(currentSsid = ssid) }
            }
        }
    }

    fun onScanClick(hasWriteSettings: Boolean) {
        if (!hasWriteSettings) {
            _uiState.update { it.copy(showPermissionDialog = true) }
            return
        }
        viewModelScope.launch {
            scanInitiated = true
            wifiRepository.disconnect()
            _events.send(WifiSetupEvent.OpenNativeCamera)
        }
    }

    fun onSuccessAnimationClicked() {
        animationDismissJob?.cancel()
        autoNavigationJob?.cancel()
        _uiState.update { it.copy(showSuccessAnimation = false) }
        if (_uiState.value.isAutoNavEnabled) {
            viewModelScope.launch {
                _events.send(WifiSetupEvent.NavigateToHome)
            }
        }
    }

    fun dismissAnimations() {
        _uiState.update { it.copy(showSuccessAnimation = false, showDisconnectedAnimation = false) }
    }

    fun onSsidPermissionGranted() {
        wifiRepository.refreshSsid()
    }

    fun onLocationServicesReturned() {
        wifiRepository.refreshSsid()
    }

    fun showLocationServicesDialog() {
        _uiState.update { it.copy(showLocationServicesDialog = true) }
    }

    fun dismissLocationServicesDialog() {
        _uiState.update { it.copy(showLocationServicesDialog = false) }
    }

    fun dismissErrorDialog() = _uiState.update { it.copy(showConnectionErrorDialog = false) }
    fun dismissPermissionDialog() = _uiState.update { it.copy(showPermissionDialog = false) }
    fun clearLogs() = DebugLogger.clear()
}
