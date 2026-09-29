package com.example.adb_connection.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.adb_connection.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val SETTINGS_WRITE_DEBOUNCE_MS = 500L

class SettingsViewModel(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val pendingWrites = mutableMapOf<String, suspend () -> Unit>()
    private var sshHostWriteJob: Job? = null
    private var sshUserWriteJob: Job? = null
    private var sshPortWriteJob: Job? = null
    private var sshPasswordWriteJob: Job? = null
    private var macLanIpWriteJob: Job? = null
    private var macSshPortWriteJob: Job? = null
    private var macSshUserWriteJob: Job? = null
    private var macSshPasswordWriteJob: Job? = null

    val isDebugModeEnabled: StateFlow<Boolean> = settingsRepository.isDebugModeEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isAutoNavigationEnabled: StateFlow<Boolean> = settingsRepository.isAutoNavigationEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val sshHost: StateFlow<String> = settingsRepository.sshHost
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val sshUser: StateFlow<String> = settingsRepository.sshUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val sshPort: StateFlow<Int> = settingsRepository.sshPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 22)

    val sshPassword: StateFlow<String> = settingsRepository.sshPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val macLanIp: StateFlow<String> = settingsRepository.manualMacLanIp
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val macSshPort: StateFlow<Int> = settingsRepository.macSshPort
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 22)

    val macSshUser: StateFlow<String> = settingsRepository.macSshUser
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val macSshPassword: StateFlow<String> = settingsRepository.macSshPassword
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    fun setDebugModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDebugModeEnabled(enabled)
        }
    }

    fun setAutoNavigationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAutoNavigationEnabled(enabled)
        }
    }

    fun setSshHost(host: String) {
        sshHostWriteJob = debounceWrite("sshHost", sshHostWriteJob) { settingsRepository.setSshHost(host) }
    }

    fun setSshUser(user: String) {
        sshUserWriteJob = debounceWrite("sshUser", sshUserWriteJob) { settingsRepository.setSshUser(user) }
    }

    fun setSshPort(port: Int) {
        sshPortWriteJob = debounceWrite("sshPort", sshPortWriteJob) { settingsRepository.setSshPort(port) }
    }

    fun setSshPassword(password: String) {
        sshPasswordWriteJob = debounceWrite("sshPassword", sshPasswordWriteJob) { settingsRepository.setSshPassword(password) }
    }

    fun setMacLanIp(ip: String) {
        macLanIpWriteJob = debounceWrite("macLanIp", macLanIpWriteJob) { settingsRepository.setMacLanIp(ip) }
    }

    fun setMacSshPort(port: Int) {
        macSshPortWriteJob = debounceWrite("macSshPort", macSshPortWriteJob) { settingsRepository.setMacSshPort(port) }
    }

    fun setMacSshUser(user: String) {
        macSshUserWriteJob = debounceWrite("macSshUser", macSshUserWriteJob) { settingsRepository.setMacSshUser(user) }
    }

    fun setMacSshPassword(password: String) {
        macSshPasswordWriteJob = debounceWrite("macSshPassword", macSshPasswordWriteJob) { settingsRepository.setMacSshPassword(password) }
    }

    fun flushPendingWrites() {
        val writes = pendingWrites.values.toList()
        pendingWrites.clear()
        sshHostWriteJob?.cancel()
        sshUserWriteJob?.cancel()
        sshPortWriteJob?.cancel()
        sshPasswordWriteJob?.cancel()
        macLanIpWriteJob?.cancel()
        macSshPortWriteJob?.cancel()
        macSshUserWriteJob?.cancel()
        macSshPasswordWriteJob?.cancel()
        viewModelScope.launch { writes.forEach { it() } }
    }

    private fun debounceWrite(key: String, previous: Job?, write: suspend () -> Unit): Job {
        previous?.cancel()
        pendingWrites[key] = write
        return viewModelScope.launch {
            delay(SETTINGS_WRITE_DEBOUNCE_MS)
            pendingWrites.remove(key)?.invoke()
        }
    }
}

class SettingsViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return SettingsViewModel(SettingsRepository(context.applicationContext)) as T
    }
}
