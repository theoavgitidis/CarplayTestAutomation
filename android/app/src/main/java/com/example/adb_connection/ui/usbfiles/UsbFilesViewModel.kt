package com.example.adb_connection.ui.usbfiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.adb_connection.ServiceLocator
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.data.settings.SshHostProvider
import com.example.adb_connection.data.usb.UsbTransferRepository
import com.example.adb_connection.data.usb.UsbTransferRepositoryImpl
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbFileEntry
import com.example.adb_connection.domain.model.UsbFileType
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val ADB_PORT = 5555
private const val LISTING_TIMEOUT_MS = 60_000L
private const val DELETE_TIMEOUT_MS = 60_000L

class UsbFilesViewModel(
    private val settingsRepository: SshHostProvider,
    private val usbTransferRepository: UsbTransferRepository,
    private val transferCoordinator: UsbTransferCoordinator? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow<UsbFilesUiState>(UsbFilesUiState.Loading)
    val uiState: StateFlow<UsbFilesUiState> = _uiState.asStateFlow()

    private val _currentDirectory = MutableStateFlow("")
    val currentDirectory: StateFlow<String> = _currentDirectory.asStateFlow()

    private var listingJob: Job? = null

    init {
        loadCurrentDirectory()
    }

    fun openDirectory(entry: UsbFileEntry) {
        if (entry.type != UsbFileType.DIRECTORY) return
        _currentDirectory.value = entry.relativePath
        loadCurrentDirectory()
    }

    /** Returns true if navigation moved up one level; false if already at root. */
    fun navigateUp(): Boolean {
        val current = _currentDirectory.value
        if (current.isEmpty()) return false
        _currentDirectory.value = current.substringBeforeLast(delimiter = "/", missingDelimiterValue = "")
        loadCurrentDirectory()
        return true
    }

    fun refresh() {
        val current = _uiState.value
        if (current is UsbFilesUiState.Loading || current is UsbFilesUiState.Refreshing) return
        _uiState.value = UsbFilesUiState.Refreshing
        cancelAndRelaunchListing {
            fetchFiles()
        }
    }

    fun deleteAllFiles() {
        val current = _uiState.value as? UsbFilesUiState.Loaded ?: return
        if (isCopyActive()) {
            _uiState.value = UsbFilesUiState.CopyActive
            return
        }
        _uiState.value = UsbFilesUiState.Deleting
        viewModelScope.launch {
            val host = resolveHost() ?: return@launch
            val result = try {
                if (isCopyActive()) {
                    _uiState.value = UsbFilesUiState.CopyActive
                    return@launch
                }
                withTimeout(DELETE_TIMEOUT_MS) {
                    usbTransferRepository.deleteAllUsbFiles(host, ADB_PORT, current.mount)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: TimeoutCancellationException) {
                _uiState.value = UsbFilesUiState.DeleteError("USB deletion timed out")
                return@launch
            } catch (e: Exception) {
                _uiState.value = UsbFilesUiState.DeleteError(e.message ?: "Delete failed")
                return@launch
            }
            when (result) {
                is UsbDeleteResult.Success -> {
                    _currentDirectory.value = ""
                    loadCurrentDirectory()
                }
                is UsbDeleteResult.UsbNotConnected -> _uiState.value = UsbFilesUiState.UsbNotConnected
                is UsbDeleteResult.UsbMountChanged -> _uiState.value = UsbFilesUiState.DeleteError(result.message)
                is UsbDeleteResult.UsbNotWritable -> _uiState.value = UsbFilesUiState.DeleteError(result.message)
                is UsbDeleteResult.AdbCommandFailed -> _uiState.value = UsbFilesUiState.DeleteError(result.message)
            }
        }
    }

    private fun loadCurrentDirectory() {
        cancelAndRelaunchListing {
            _uiState.value = UsbFilesUiState.Loading
            if (isCopyActive()) {
                _uiState.value = UsbFilesUiState.CopyActive
                return@cancelAndRelaunchListing
            }
            val host = resolveHost() ?: return@cancelAndRelaunchListing
            val result = listWithRetry(host)
            applyListingResult(result)
        }
    }

    private suspend fun fetchFiles() {
        if (isCopyActive()) {
            _uiState.value = UsbFilesUiState.CopyActive
            return
        }
        val host = resolveHost() ?: return
        val result = listWithRetry(host)
        applyListingResult(result)
    }

    private suspend fun listWithRetry(host: String): UsbListingResult {
        val first = attemptListing(host)
        return if (first is UsbListingResult.ListingTimedOut) {
            attemptListing(host)
        } else {
            first
        }
    }

    private suspend fun attemptListing(host: String): UsbListingResult {
        return try {
            withTimeout(LISTING_TIMEOUT_MS) {
                usbTransferRepository.listUsbFiles(host, ADB_PORT, _currentDirectory.value)
            }
        } catch (e: TimeoutCancellationException) {
            UsbListingResult.ListingTimedOut
        }
    }

    private fun applyListingResult(result: UsbListingResult) {
        when (result) {
            is UsbListingResult.Success ->
                _uiState.value = UsbFilesUiState.Loaded(result.mount, result.entries)
            is UsbListingResult.DirectoryNotFound -> {
                _currentDirectory.value = ""
                loadCurrentDirectory()
            }
            is UsbListingResult.ListingTimedOut ->
                _uiState.value = UsbFilesUiState.ListingTimedOut
            else -> mapListingResult(result)
        }
    }

    private fun mapListingResult(result: UsbListingResult) {
        _uiState.value = when (result) {
            is UsbListingResult.Success ->
                UsbFilesUiState.Loaded(result.mount, result.entries)
            is UsbListingResult.UsbNotConnected ->
                UsbFilesUiState.UsbNotConnected
            is UsbListingResult.UsbDetectionFailed ->
                UsbFilesUiState.AdbError(result.message)
            is UsbListingResult.TraceMateDirectoryAbsent ->
                UsbFilesUiState.TraceMateDirectoryAbsent(result.mount)
            is UsbListingResult.NoExportedFiles ->
                UsbFilesUiState.NoExports(result.mount)
            is UsbListingResult.UsbRemovedDuringListing ->
                UsbFilesUiState.UsbRemovedDuringLoad(result.partialEntries)
            is UsbListingResult.AdbCommandFailed ->
                UsbFilesUiState.AdbError(result.message)
            is UsbListingResult.DirectoryNotFound ->
                UsbFilesUiState.AdbError("Directory not found: ${result.relativeDirectory}")
            is UsbListingResult.ListingTimedOut ->
                UsbFilesUiState.ListingTimedOut
        }
    }

    private fun cancelAndRelaunchListing(block: suspend () -> Unit) {
        listingJob?.cancel()
        listingJob = viewModelScope.launch {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.value = UsbFilesUiState.AdbError(e.message ?: "ADB command failed")
            }
        }
    }

    private fun isCopyActive(): Boolean =
        transferCoordinator?.state?.value is UsbTransferCoordinatorState.Running

    private suspend fun resolveHost(): String? = try {
        settingsRepository.sshHost.first()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        _uiState.value = UsbFilesUiState.AdbError(e.message ?: "Settings unavailable")
        null
    }
}

class UsbFilesViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appCtx = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        return UsbFilesViewModel(
            settingsRepository = SettingsRepository(appCtx),
            usbTransferRepository = ServiceLocator.provideUsbTransferRepository(),
            transferCoordinator = ServiceLocator.provideUsbTransferCoordinator(appCtx)
        ) as T
    }
}
