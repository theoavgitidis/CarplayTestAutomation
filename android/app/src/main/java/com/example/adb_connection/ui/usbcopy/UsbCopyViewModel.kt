package com.example.adb_connection.ui.usbcopy

import android.content.Context
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.adb_connection.ServiceLocator
import com.example.adb_connection.data.settings.SettingsRepository
import com.example.adb_connection.data.settings.SshHostProvider
import com.example.adb_connection.data.usb.UsbTransferRepository
import com.example.adb_connection.domain.model.ArchiveTileState
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbEjectResult
import com.example.adb_connection.domain.model.UsbRemountResult
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.usb.CoordinatorStartResult
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import com.example.adb_connection.service.UsbTransferService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "UsbCopyViewModel"
private const val ADB_PORT = 5555
private const val AUTO_DISMISS_DELAY_MS = 15_000L
private const val USB_DISCOVERY_RETRY_DELAY_MS = 1_000L
private const val USB_DISCOVERY_MAX_ATTEMPTS = 3

/** User-facing message for a session-directory-creation failure, shown in [UsbCopyUiState.DiscoveryFailed]. */
internal fun sessionDirFailureMessage(reason: FailureReason): String = when (reason) {
    FailureReason.USB_NOT_FOUND -> "No USB stick detected."
    FailureReason.USB_DISCONNECTED -> "USB stick was disconnected."
    FailureReason.USB_NOT_WRITABLE -> "USB stick is read-only."
    FailureReason.USB_MOUNT_CHANGED -> "A different USB stick is now connected. Please retry."
    FailureReason.ADB_COMMUNICATION_ERROR -> "Could not verify the USB stick. Check the ADB connection and retry."
    else -> "Failed to create export directory on USB"
}

/**
 * Owns only discovery/selection/eject/remount UI state. The transfer batch itself is owned by
 * the application-scoped [UsbTransferCoordinator] (obtained via `ServiceLocator`, backed by a
 * foreground [UsbTransferService]) so screen-off, activity recreation, navigation recomposition,
 * or this ViewModel's own destruction cannot cancel an in-flight transfer or start a duplicate
 * one — this ViewModel only requests start/cancel and observes the coordinator's state.
 */
class UsbCopyViewModel(
    private val settingsRepository: SshHostProvider,
    private val usbTransferRepository: UsbTransferRepository,
    private val coordinator: UsbTransferCoordinator,
    // Bound by the Factory to a real android.content.Context/Intent call (Prompt 2 Task A: the
    // foreground service is only ever started from this explicit, visible "Begin" action). Kept
    // as a zero-arg callback — rather than storing a Context on the ViewModel — so plain JUnit
    // tests (no Robolectric/mocking framework in this project) never need a real Context/Intent,
    // which throw in unit tests.
    private val startForegroundTransferService: () -> Unit
) : ViewModel() {

    // Local, ViewModel-owned discovery/selection state. Only meaningful while the coordinator
    // itself is Idle — once a batch is Running/Completed, `uiState` below reflects the
    // coordinator's state instead, regardless of what is left in here.
    private val _localState = MutableStateFlow<UsbCopyUiState>(UsbCopyUiState.Idle)

    val uiState: StateFlow<UsbCopyUiState> = combine(_localState, coordinator.state) { local, coordinatorState ->
        when (coordinatorState) {
            is UsbTransferCoordinatorState.Running -> UsbCopyUiState.Transferring(
                tiles = coordinatorState.tiles,
                progress = coordinatorState.progress,
                phase = coordinatorState.phase
            )
            is UsbTransferCoordinatorState.Completed -> UsbCopyUiState.Completed(
                tiles = coordinatorState.tiles,
                progress = coordinatorState.progress,
                sessionDir = coordinatorState.sessionDir
            )
            is UsbTransferCoordinatorState.Idle -> local
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UsbCopyUiState.Idle)

    private val _showLeaveDialog = MutableStateFlow(false)
    val showLeaveDialog: StateFlow<Boolean> = _showLeaveDialog.asStateFlow()

    // Emits exactly once when the overlay should be dismissed and the screen
    // navigated away. Idempotent: additional calls after the first are no-ops.
    // extraBufferCapacity = 0 so the event is NOT replayed to a new collector
    // (e.g. after screen rotation or returning from background). A buffered event
    // would immediately navigate the user away when they come back to the screen.
    private val _navigateHomeEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 0)
    val navigateHomeEvent: SharedFlow<Unit> = _navigateHomeEvent.asSharedFlow()

    private var autoDismissJob: Job? = null
    private var dismissFired = false

    fun loadArchives() {
        val local = _localState.value
        // Only start discovery from a clean slate or after a previous failure.
        // Every other local state (Discovering, Ready) must not be interrupted — this guards
        // against LaunchedEffect(Unit) re-firing when the screen is re-composed after
        // screen-off/on or process recreation.
        if (local !is UsbCopyUiState.Idle && local !is UsbCopyUiState.DiscoveryFailed) return
        // A batch already Running/Completed (in this process, or reattached from a persisted
        // snapshot below) takes priority over discovery — `uiState` will reflect it directly.
        if (coordinator.state.value !is UsbTransferCoordinatorState.Idle) return

        viewModelScope.launch {
            val host = settingsRepository.sshHost.first()
            // Defensive: in case the coordinator singleton was freshly (re)created (process
            // death took the whole app, including any previously-running foreground service)
            // but a batch snapshot was left on disk, try to reattach before falling back to a
            // normal discovery pass. The service's own null-intent restart path covers the
            // "service survives, process doesn't" case; this covers "everything was killed".
            if (coordinator.attachOrRecover(host)) return@launch
            performDiscovery(host)
        }
    }

    fun refreshArchives() {
        if (coordinator.state.value !is UsbTransferCoordinatorState.Idle) return
        if (_localState.value is UsbCopyUiState.Discovering) return
        _localState.value = UsbCopyUiState.Idle
        loadArchives()
    }

    fun toggleSelection(archive: TriggerArchive) {
        val state = _localState.value as? UsbCopyUiState.Ready ?: return
        _localState.value = state.copy(
            tiles = state.tiles.map { tile ->
                if (tile.archive.stem == archive.stem && !tile.isAlreadyOnUsb) {
                    tile.copy(isSelected = !tile.isSelected)
                } else {
                    tile
                }
            }
        )
    }

    fun startTransfer() {
        val state = _localState.value as? UsbCopyUiState.Ready ?: return
        val usbMount = state.usbMount ?: return
        if (coordinator.state.value is UsbTransferCoordinatorState.Running) return
        val selectedArchives = state.tiles.filter { it.isSelected && !it.isAlreadyOnUsb }.map { it.archive }
        if (selectedArchives.isEmpty()) return

        viewModelScope.launch {
            val host = settingsRepository.sshHost.first()
            when (val result = coordinator.start(host, usbMount, selectedArchives)) {
                is CoordinatorStartResult.SessionDirFailed -> {
                    Log.e(TAG, "Failed to create session directory: ${result.reason} ${result.message ?: ""}")
                    _localState.value = UsbCopyUiState.DiscoveryFailed(sessionDirFailureMessage(result.reason))
                }
                CoordinatorStartResult.AlreadyRunning -> { /* uiState already reflects Running */ }
                CoordinatorStartResult.Started -> {
                    // Started only from this explicit, visible user action, per the foreground
                    // service contract — never started implicitly in the background.
                    startForegroundTransferService()
                }
            }
        }
    }

    fun requestCancel() {
        coordinator.requestCancel()
    }

    fun isTransferActive(): Boolean = coordinator.state.value is UsbTransferCoordinatorState.Running

    fun ejectUsb() {
        val state = _localState.value as? UsbCopyUiState.Ready ?: return
        val mount = when (val d = state.usbDetectionResult) {
            is UsbDetectionResult.Writable -> d.mount
            is UsbDetectionResult.ReadOnly -> d.mount
            is UsbDetectionResult.MultipleWritableMounts,
            is UsbDetectionResult.MultipleReadOnlyMounts,
            is UsbDetectionResult.UnsupportedMountLayout,
            is UsbDetectionResult.QueryFailed,
            UsbDetectionResult.NotFound -> return
        }
        if (state.ejectState is UsbEjectState.Ejecting) return
        viewModelScope.launch {
            val host = settingsRepository.sshHost.first()
            _localState.value = state.copy(ejectState = UsbEjectState.Ejecting)
            val result = usbTransferRepository.ejectUsb(host, ADB_PORT, mount)
            val newEjectState = when (result) {
                is UsbEjectResult.Success -> UsbEjectState.Ejected
                is UsbEjectResult.Failed -> UsbEjectState.Failed(result.message)
            }
            val current = _localState.value as? UsbCopyUiState.Ready ?: return@launch
            _localState.value = current.copy(ejectState = newEjectState)
            delay(4_000)
            val after = _localState.value as? UsbCopyUiState.Ready ?: return@launch
            _localState.value = if (result is UsbEjectResult.Success) {
                after.copy(usbDetectionResult = UsbDetectionResult.NotFound, ejectState = UsbEjectState.Idle)
            } else {
                after.copy(ejectState = UsbEjectState.Idle)
            }
        }
    }

    /** Shows the confirmation dialog; no-op if not in ReadOnly state or already remounting. */
    fun requestRemount() {
        val state = _localState.value as? UsbCopyUiState.Ready ?: return
        if (state.usbDetectionResult !is UsbDetectionResult.ReadOnly) return
        if (state.remountState is RemountState.Remounting) return
        _localState.value = state.copy(remountState = RemountState.ConfirmPending)
    }

    fun dismissRemountConfirmation() {
        val state = _localState.value as? UsbCopyUiState.Ready ?: return
        if (state.remountState !is RemountState.ConfirmPending) return
        _localState.value = state.copy(remountState = RemountState.Idle)
    }

    fun confirmRemount() {
        val state = _localState.value as? UsbCopyUiState.Ready ?: return
        val readOnlyMount = (state.usbDetectionResult as? UsbDetectionResult.ReadOnly)?.mount ?: return
        if (state.remountState is RemountState.Remounting) return
        _localState.value = state.copy(remountState = RemountState.Remounting)
        viewModelScope.launch {
            val host = settingsRepository.sshHost.first()
            val result = usbTransferRepository.remountReadWrite(host, ADB_PORT, readOnlyMount)
            val current = _localState.value as? UsbCopyUiState.Ready ?: return@launch
            when (result) {
                is UsbRemountResult.Success -> {
                    _localState.value = current.copy(
                        usbDetectionResult = UsbDetectionResult.Writable(result.mount),
                        remountState = RemountState.Success
                    )
                }
                is UsbRemountResult.Failed -> {
                    _localState.value = current.copy(remountState = RemountState.Failed(result.message))
                }
            }
        }
    }

    fun requestLeave() {
        if (!isTransferActive()) return
        _showLeaveDialog.value = true
    }

    fun dismissLeaveDialog() {
        _showLeaveDialog.value = false
    }

    fun confirmLeaveAndCancel() {
        _showLeaveDialog.value = false
        requestCancel()
        viewModelScope.launch {
            // The batch itself keeps running in the coordinator's own scope even after we
            // navigate away — we only wait here so the leave-dialog's "Cancel transfer" action
            // feels immediate rather than racing the navigation.
            coordinator.state.first { it !is UsbTransferCoordinatorState.Running }
            _navigateHomeEvent.emit(Unit)
        }
    }

    /** Starts the 15-second auto-dismiss timer. Call this when the overlay becomes visible. */
    fun startAutoDismissTimer() {
        autoDismissJob?.cancel()
        autoDismissJob = viewModelScope.launch {
            delay(AUTO_DISMISS_DELAY_MS)
            dismissCompleted()
        }
    }

    /**
     * Idempotent dismissal. Cancels the auto-dismiss timer, clears the Completed
     * state, and fires the navigate-home event exactly once.
     */
    fun dismissCompleted() {
        if (dismissFired) return
        dismissFired = true
        autoDismissJob?.cancel()
        autoDismissJob = null
        coordinator.dismissCompleted()
        // Use emit (suspending) so the event is delivered to the active collector
        // rather than buffered. With extraBufferCapacity = 0, tryEmit would silently
        // drop the event if no collector is subscribed at this exact moment.
        viewModelScope.launch { _navigateHomeEvent.emit(Unit) }
    }

    /** Only meaningful when not Running/Completed (see [UsbCopyScreen]'s Retry button on DiscoveryFailed). */
    fun reset() {
        dismissFired = false
        _localState.value = UsbCopyUiState.Idle
    }

    private suspend fun performDiscovery(host: String) {
        _localState.value = UsbCopyUiState.Discovering

        val archives = try {
            usbTransferRepository.discoverArchives(host, ADB_PORT)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Archive discovery failed: ${e.message}")
            _localState.value = UsbCopyUiState.DiscoveryFailed(e.message ?: "Discovery failed")
            return
        }

        val usbStatus = detectUsbStatusWithRetry(host)
        val usbMount = if (usbStatus is UsbDetectionResult.Writable) usbStatus.mount else null
        val existingExports = if (usbMount != null) {
            usbTransferRepository.listExistingExportDirectories(host, ADB_PORT, usbMount)
                ?: run {
                    _localState.value = UsbCopyUiState.DiscoveryFailed("Could not verify existing USB exports. Check the ADB connection and retry.")
                    return
                }
        } else {
            emptyMap()
        }

        val tiles = archives.map { normalArchive ->
            val offlineArchive = normalArchive.offlineVariant()
            val normalPath = existingExports[normalArchive.stem]
            val offlinePath = existingExports[offlineArchive.stem]

            ArchiveTileState(
                archive = normalArchive,
                isSelected = false,
                isAlreadyOnUsb = normalPath != null && offlinePath != null,
                existingUsbPath = normalPath ?: offlinePath
            )
        }

        _localState.value = UsbCopyUiState.Ready(tiles = tiles, usbDetectionResult = usbStatus)
    }

    private suspend fun detectUsbStatusWithRetry(host: String): UsbDetectionResult {
        repeat(USB_DISCOVERY_MAX_ATTEMPTS - 1) {
            val status = usbTransferRepository.detectUsbStatus(host, ADB_PORT)
            if (status !is UsbDetectionResult.NotFound && status !is UsbDetectionResult.QueryFailed) return status
            delay(USB_DISCOVERY_RETRY_DELAY_MS)
        }
        return usbTransferRepository.detectUsbStatus(host, ADB_PORT)
    }
}

class UsbCopyViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        val appCtx = context.applicationContext
        @Suppress("UNCHECKED_CAST")
        return UsbCopyViewModel(
            settingsRepository = SettingsRepository(appCtx),
            usbTransferRepository = ServiceLocator.provideUsbTransferRepository(),
            coordinator = ServiceLocator.provideUsbTransferCoordinator(appCtx),
            startForegroundTransferService = {
                ContextCompat.startForegroundService(appCtx, UsbTransferService.startIntent(appCtx))
            }
        ) as T
    }
}
