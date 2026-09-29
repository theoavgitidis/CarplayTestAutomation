package com.example.adb_connection.data.ssh

import com.example.adb_connection.data.debug.DebugLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private const val PROGRESS_PERSIST_INTERVAL_MS = 1_000L
private const val PROGRESS_PERSIST_BYTE_INTERVAL = 1L * 1024 * 1024

sealed interface CaptureToolPlaceholderTransferStartResult {
    data object Started : CaptureToolPlaceholderTransferStartResult
    data object AlreadyRunning : CaptureToolPlaceholderTransferStartResult
}

/** Application-scoped owner of an CAPTURE_TOOL_PLACEHOLDER bridge job, independent of Activity and ViewModel scopes. */
interface CaptureToolPlaceholderTransferCoordinator {
    val state: StateFlow<CaptureToolPlaceholderTransferSnapshot?>
    suspend fun start(remotePaths: List<String>): CaptureToolPlaceholderTransferStartResult
    fun requestCancel()
    suspend fun recoverLastSnapshot()
}

class DefaultCaptureToolPlaceholderTransferCoordinator(
    private val repository: CaptureToolPlaceholderTransferRunner,
    private val snapshotStore: CaptureToolPlaceholderTransferSnapshotStore,
    private val coordinatorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : CaptureToolPlaceholderTransferCoordinator {
    private val _state = MutableStateFlow<CaptureToolPlaceholderTransferSnapshot?>(null)
    override val state: StateFlow<CaptureToolPlaceholderTransferSnapshot?> = _state.asStateFlow()
    private var transferJob: Job? = null
    private var starting = false
    private var lastLoggedPhase: CaptureToolPlaceholderUsbTransferPhase? = null
    private var lastPersistedProgressBytes = 0L
    private var lastProgressPersistAtMs = 0L
    private var pendingProgressPersistJob: Job? = null

    override suspend fun start(remotePaths: List<String>): CaptureToolPlaceholderTransferStartResult {
        if (starting || transferJob?.isActive == true || _state.value?.status == CaptureToolPlaceholderTransferJobStatus.RUNNING) {
            return CaptureToolPlaceholderTransferStartResult.AlreadyRunning
        }
        require(remotePaths.isNotEmpty()) { "No CAPTURE_TOOL_PLACEHOLDER captures selected" }
        starting = true
        val initial = CaptureToolPlaceholderTransferSnapshot(
            jobId = UUID.randomUUID().toString(),
            status = CaptureToolPlaceholderTransferJobStatus.RUNNING,
            phase = CaptureToolPlaceholderUsbTransferPhase.PREPARING,
            remotePaths = remotePaths
        )
        lastLoggedPhase = null
        lastPersistedProgressBytes = 0L
        lastProgressPersistAtMs = 0L
        publish(initial)
        transferJob = coordinatorScope.launch {
            try {
                val result = repository.transfer(remotePaths) { transferState ->
                    val current = _state.value ?: initial
                    val progress = transferState.progress
                    val next = current.copy(
                        status = CaptureToolPlaceholderTransferJobStatus.RUNNING,
                        phase = transferState.phase,
                        fileName = transferState.fileName,
                        transferredBytes = progress?.transferredBytes ?: current.transferredBytes,
                        totalBytes = progress?.totalBytes ?: current.totalBytes,
                        error = transferState.error
                    )
                    publishProgress(next)
                }
                result.fold(
                    onSuccess = { publish((_state.value ?: initial).copy(status = CaptureToolPlaceholderTransferJobStatus.COMPLETED, phase = CaptureToolPlaceholderUsbTransferPhase.COMPLETED, fileName = null, error = null)) },
                    onFailure = { publish((_state.value ?: initial).copy(status = CaptureToolPlaceholderTransferJobStatus.FAILED, error = it.message ?: "CAPTURE_TOOL_PLACEHOLDER transfer failed")) }
                )
            } catch (_: CancellationException) {
                withContext(NonCancellable) {
                    publish((_state.value ?: initial).copy(status = CaptureToolPlaceholderTransferJobStatus.CANCELLED, phase = CaptureToolPlaceholderUsbTransferPhase.CANCELLED, error = "Transfer cancelled"))
                }
            } finally {
                starting = false
            }
        }
        return CaptureToolPlaceholderTransferStartResult.Started
    }

    override fun requestCancel() {
        transferJob?.cancel(CancellationException("User cancelled CAPTURE_TOOL_PLACEHOLDER transfer"))
    }

    override suspend fun recoverLastSnapshot() {
        if (_state.value != null || transferJob?.isActive == true) return
        val snapshot = snapshotStore.load() ?: return
        if (snapshot.status == CaptureToolPlaceholderTransferJobStatus.RUNNING) {
            // SCP has no resume contract. Never silently relaunch after process death.
            publish(snapshot.copy(status = CaptureToolPlaceholderTransferJobStatus.INTERRUPTED, error = "Transfer interrupted; restart it explicitly"))
        } else {
            _state.value = snapshot
        }
    }

    private suspend fun publish(snapshot: CaptureToolPlaceholderTransferSnapshot) {
        pendingProgressPersistJob?.cancelAndJoin()
        pendingProgressPersistJob = null
        _state.value = snapshot
        snapshotStore.save(snapshot)
        log(snapshot)
    }

    private fun publishProgress(snapshot: CaptureToolPlaceholderTransferSnapshot) {
        val previous = _state.value
        val phaseChanged = previous?.phase != snapshot.phase
        _state.value = snapshot
        log(snapshot)
        val now = System.currentTimeMillis()
        val progressAdvanced = snapshot.transferredBytes - lastPersistedProgressBytes >= PROGRESS_PERSIST_BYTE_INTERVAL
        if (phaseChanged || progressAdvanced || now - lastProgressPersistAtMs >= PROGRESS_PERSIST_INTERVAL_MS) {
            lastPersistedProgressBytes = snapshot.transferredBytes
            lastProgressPersistAtMs = now
            pendingProgressPersistJob?.cancel()
            pendingProgressPersistJob = coordinatorScope.launch { snapshotStore.save(snapshot) }
        }
    }

    private fun log(snapshot: CaptureToolPlaceholderTransferSnapshot) {
        val shouldLog = snapshot.phase != lastLoggedPhase ||
            snapshot.status != CaptureToolPlaceholderTransferJobStatus.RUNNING || snapshot.error != null
        if (!shouldLog) return
        lastLoggedPhase = snapshot.phase
        val source = snapshot.fileName?.let { name ->
            snapshot.remotePaths.firstOrNull { it.substringAfterLast('/') == name }
        } ?: snapshot.remotePaths.firstOrNull()
        DebugLogger.log(
            "CAPTURE_TOOL_PLACEHOLDER job=${snapshot.jobId} status=${snapshot.status} phase=${snapshot.phase} " +
                "source=${source ?: "-"} target=Headunit USB error=${snapshot.error ?: "-"}"
        )
    }
}
