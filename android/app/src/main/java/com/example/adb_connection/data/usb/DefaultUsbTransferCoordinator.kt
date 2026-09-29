package com.example.adb_connection.data.usb

import android.util.Log
import com.example.adb_connection.domain.model.ActiveJobSnapshot
import com.example.adb_connection.domain.model.ActiveTransferSnapshot
import com.example.adb_connection.domain.model.ArchiveTileState
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.JobInspection
import com.example.adb_connection.domain.model.LaunchOutcome
import com.example.adb_connection.domain.model.PrepareOutcome
import com.example.adb_connection.domain.model.RemoteJobLifecycle
import com.example.adb_connection.domain.model.TileResultSnapshot
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TransferProgressState
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbSessionDirResult
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.model.toResult
import com.example.adb_connection.domain.model.toSummary
import com.example.adb_connection.domain.usb.ActiveTransferSnapshotCodec
import com.example.adb_connection.domain.usb.CoordinatorStartResult
import com.example.adb_connection.domain.usb.UsbShellCommandBuilder
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import com.example.adb_connection.domain.usb.isInfrastructure
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "UsbTransferCoordinator"
private const val ADB_PORT = 5555
private const val RECOVERY_RETRY_DELAY_MS = 5_000L
private const val MAX_AUTOMATIC_RECOVERY_ATTEMPTS = 3

/**
 * Default, application-scoped [UsbTransferCoordinator]. Runs the transfer batch loop in its own
 * [CoroutineScope] (backed by [SupervisorJob], not tied to any ViewModel/Activity), so cancelling
 * a caller's coroutine (screen-off, ViewModel destruction, navigation) never cancels the batch —
 * only [requestCancel] does, by flipping [_cancelRequested], which the repository's blocking-wait
 * watches and reacts to by killing the remote worker's process group.
 *
 * Instantiate once per process via `ServiceLocator` (constructor takes an application-scoped
 * [CoroutineScope] so tests can inject a deterministic scope/dispatcher).
 */
class DefaultUsbTransferCoordinator(
    private val repository: UsbTransferRepository,
    private val snapshotStore: TransferSnapshotStore,
    private val coordinatorScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : UsbTransferCoordinator {

    private val _state = MutableStateFlow<UsbTransferCoordinatorState>(UsbTransferCoordinatorState.Idle)
    override val state: StateFlow<UsbTransferCoordinatorState> = _state.asStateFlow()

    private val _cancelRequested = MutableStateFlow(false)

    private var batchJob: Job? = null
    private var recoveryJob: Job? = null
    private var cancelPersistenceJob: Job? = null
    private val snapshotMutex = Mutex()

    // Last snapshot written to disk, kept in memory only so requestCancel() can flip the
    // cancelRequested bit and re-persist without needing to reconstruct the whole snapshot.
    @Volatile
    private var lastSnapshot: ActiveTransferSnapshot? = null

    override suspend fun start(
        host: String,
        usbMount: UsbMount,
        archives: List<TriggerArchive>
    ): CoordinatorStartResult {
        if (_state.value is UsbTransferCoordinatorState.Running || batchJob?.isActive == true) {
            return CoordinatorStartResult.AlreadyRunning
        }
        _cancelRequested.value = false

        // Re-detect the USB mount immediately before starting so we never use a mount
        // recorded during discovery that may have become stale (device path or options
        // may differ from what was seen at discovery time).
        val freshStatus = repository.detectUsbStatus(host, ADB_PORT)
        val freshMount = (freshStatus as? UsbDetectionResult.Writable)?.mount
        if (freshMount == null) {
            val reason = when (freshStatus) {
                is UsbDetectionResult.ReadOnly -> FailureReason.USB_NOT_WRITABLE
                is UsbDetectionResult.QueryFailed -> FailureReason.ADB_COMMUNICATION_ERROR
                else -> FailureReason.USB_NOT_FOUND
            }
            Log.e(TAG, "USB no longer writable before transfer start: $freshStatus")
            return CoordinatorStartResult.SessionDirFailed(reason, null)
        }
        // A mount path alone is not a stable device identity. Until the head unit exposes a
        // persisted volume UUID/PARTUUID, require the exact source device and mount path.
        if (freshMount.mountPath != usbMount.mountPath || freshMount.devicePath != usbMount.devicePath) {
            Log.e(TAG, "USB identity changed before transfer start: expected $usbMount, got $freshMount")
            return CoordinatorStartResult.SessionDirFailed(FailureReason.USB_MOUNT_CHANGED, null)
        }

        val sessionDirResult = repository.createSessionDir(host, ADB_PORT, freshMount)
        val sessionDir = when (sessionDirResult) {
            is UsbSessionDirResult.Success -> sessionDirResult.path
            is UsbSessionDirResult.Failed -> {
                Log.e(TAG, "Session dir creation failed: ${sessionDirResult.reason} ${sessionDirResult.message ?: ""}")
                return CoordinatorStartResult.SessionDirFailed(sessionDirResult.reason, sessionDirResult.message)
            }
        }

        persistSnapshot(freshMount, sessionDir, archives, currentIndex = 0, currentJob = null, tileResults = emptyList())

        val tiles = archives.map { ArchiveTileState(archive = it, isSelected = true) }
        _state.value = UsbTransferCoordinatorState.Running(
            tiles = tiles,
            progress = TransferProgressState.zero(archives.size),
            phase = null
        )

        batchJob = coordinatorScope.launch {
            runBatch(host, freshMount, sessionDir, archives, startIndex = 0, seedTileResults = emptyList(), resumeJob = null)
        }
        return CoordinatorStartResult.Started
    }

    override fun requestCancel() {
        val snapshot = lastSnapshot ?: return
        if (_cancelRequested.value || cancelPersistenceJob?.isActive == true) return
        cancelPersistenceJob = coordinatorScope.launch {
            if (_cancelRequested.value) return@launch
            // Persist intent before signalling the remote worker. If the process dies before
            // this completes, it has not asked the worker to stop; if it completes, recovery
            // will continue cancellation rather than accidentally resume copying.
            snapshotMutex.withLock {
                if (lastSnapshot !== snapshot) return@withLock
                val cancelledSnapshot = snapshot.copy(cancelRequested = true)
                snapshotStore.save(ActiveTransferSnapshotCodec.encode(cancelledSnapshot))
                lastSnapshot = cancelledSnapshot
                _cancelRequested.value = true
            }
        }
    }

    override fun dismissCompleted() {
        if (_state.value is UsbTransferCoordinatorState.Completed) {
            _state.value = UsbTransferCoordinatorState.Idle
        }
    }

    override suspend fun attachOrRecover(host: String): Boolean {
        if (batchJob?.isActive == true) return false

        val raw = snapshotStore.load() ?: return false
        val snapshot = ActiveTransferSnapshotCodec.decode(raw)
        if (snapshot == null) {
            Log.w(TAG, "Persisted transfer snapshot could not be decoded — discarding")
            clearSnapshot()
            return false
        }

        _cancelRequested.value = snapshot.cancelRequested

        val usbStatus = repository.detectUsbStatus(host, ADB_PORT)
        val currentMount = (usbStatus as? UsbDetectionResult.Writable)?.mount
        val mountValid = currentMount != null &&
            currentMount.devicePath == snapshot.expectedMount.devicePath &&
            currentMount.mountPath == snapshot.expectedMount.mountPath

        if (!mountValid) {
            Log.w(TAG, "Expected USB mount is no longer present/writable on recovery — cannot resume")
            val reason = if (currentMount == null) FailureReason.USB_DISCONNECTED else FailureReason.USB_MOUNT_CHANGED
            finishAsMountLost(snapshot, reason)
            return true
        }

        val tiles = snapshot.archives.map { a ->
            val seed = snapshot.tileResults.find { it.stem == a.stem }
            val normal = seed?.normalResult?.toResult(a)
            val offline = seed?.offlineResult?.toResult(a.offlineVariant())
            ArchiveTileState(
                archive = a,
                isSelected = true,
                normalResult = normal,
                offlineResult = offline,
                result = if (normal != null) aggregateResults(normal, offline) else null
            )
        }
        _state.value = UsbTransferCoordinatorState.Running(
            tiles = tiles,
            progress = tallyProgress(snapshot.archives.size, tiles),
            phase = null
        )

        batchJob = coordinatorScope.launch {
            runBatch(
                host, currentMount!!, snapshot.sessionDir, snapshot.archives,
                startIndex = snapshot.currentIndex, seedTileResults = snapshot.tileResults, resumeJob = snapshot.currentJob
            )
        }
        return true
    }

    private suspend fun finishAsMountLost(snapshot: ActiveTransferSnapshot, reason: FailureReason) {
        val tiles = snapshot.archives.mapIndexed { i, a ->
            val seed = snapshot.tileResults.find { it.stem == a.stem }
            val normal = seed?.normalResult?.toResult(a) ?: if (i == snapshot.currentIndex) {
                TriggerTransferResult.Failed(a, reason)
            } else null
            val offline = seed?.offlineResult?.toResult(a.offlineVariant())
            ArchiveTileState(
                archive = a,
                isSelected = true,
                normalResult = normal,
                offlineResult = offline,
                result = if (normal != null) aggregateResults(normal, offline) else null
            )
        }
        _state.value = UsbTransferCoordinatorState.Completed(
            tiles = tiles,
            progress = tallyProgress(snapshot.archives.size, tiles),
            sessionDir = snapshot.sessionDir
        )
        clearSnapshot()
    }

    private fun tallyProgress(total: Int, tiles: List<ArchiveTileState>): TransferProgressState {
        var processed = 0; var succeeded = 0; var alreadyPresent = 0; var failed = 0; var cancelled = 0
        tiles.forEach { tile ->
            when (tile.result) {
                is TriggerTransferResult.Success -> { processed++; succeeded++ }
                is TriggerTransferResult.AlreadyPresent -> { processed++; alreadyPresent++ }
                is TriggerTransferResult.Failed -> { processed++; failed++ }
                is TriggerTransferResult.Cancelled -> { cancelled++ }
                null -> {}
            }
        }
        return TransferProgressState(total, processed, succeeded, alreadyPresent, failed, cancelled)
    }

    private fun aggregateResults(
        normalResult: TriggerTransferResult,
        offlineResult: TriggerTransferResult?
    ): TriggerTransferResult = when {
        normalResult is TriggerTransferResult.Cancelled -> normalResult
        offlineResult is TriggerTransferResult.Cancelled -> offlineResult
        normalResult is TriggerTransferResult.Failed -> normalResult
        offlineResult is TriggerTransferResult.Failed -> offlineResult
        normalResult is TriggerTransferResult.AlreadyPresent &&
            offlineResult is TriggerTransferResult.AlreadyPresent -> normalResult
        normalResult is TriggerTransferResult.Success -> normalResult
        offlineResult is TriggerTransferResult.Success -> offlineResult
        else -> normalResult
    }

    private suspend fun persistSnapshot(
        expectedMount: UsbMount,
        sessionDir: String,
        archives: List<TriggerArchive>,
        currentIndex: Int,
        currentJob: ActiveJobSnapshot?,
        tileResults: List<TileResultSnapshot>
    ) {
        val snapshot = ActiveTransferSnapshot(
            expectedMount, sessionDir, archives, currentIndex, currentJob, tileResults, _cancelRequested.value
        )
        snapshotMutex.withLock {
            lastSnapshot = snapshot
            snapshotStore.save(ActiveTransferSnapshotCodec.encode(snapshot))
        }
    }

    /**
     * Runs (or resumes) one variant's transfer. When [resume] matches this exact archive's
     * stem+variant, reattaches to (or resolves) the already-in-flight job instead of launching
     * a new one — this only ever applies to the single tile/variant the batch was interrupted
     * on; every subsequent call in the same batch passes `resume = null`.
     */
    private suspend fun runVariant(
        host: String,
        usbMount: UsbMount,
        sessionDir: String,
        archive: TriggerArchive,
        resume: ActiveJobSnapshot?,
        archives: List<TriggerArchive>,
        currentIndex: Int,
        tileResultsSoFar: List<TileResultSnapshot>,
        onPhase: (TransferPhase) -> Unit
    ): TriggerTransferResult {
        if (resume != null && resume.stem == archive.stem && resume.variant == archive.variant) {
            return reattachVariant(host, usbMount, sessionDir, archive, resume, onPhase)
        }

        onPhase(TransferPhase.Checking(archive))
        return when (val prepared = repository.prepareTransfer(host, ADB_PORT, archive, usbMount, sessionDir)) {
            is PrepareOutcome.AlreadyPresent -> TriggerTransferResult.AlreadyPresent(archive, prepared.existingPath)
            is PrepareOutcome.Failed -> TriggerTransferResult.Failed(archive, prepared.reason, prepared.message)
            is PrepareOutcome.Ready -> {
                onPhase(
                    if (prepared.useStrategyA) TransferPhase.CopyingExisting(archive) else TransferPhase.Extracting(archive)
                )
                val jobId = UsbShellCommandBuilder.generateJobId()
                // Persist the "launching" record BEFORE actually launching, so an Android
                // death right at this instant leaves a recoverable trail (see reattachVariant's
                // LAUNCHING handling) instead of an untracked orphan job.
                persistSnapshot(
                    usbMount, sessionDir, archives, currentIndex,
                    ActiveJobSnapshot(jobId, archive.stem, archive.variant, prepared.useStrategyA, RemoteJobLifecycle.LAUNCHING),
                    tileResultsSoFar
                )
                when (val launch = repository.launchTransfer(host, ADB_PORT, jobId, archive, usbMount, sessionDir, prepared.useStrategyA)) {
                    is LaunchOutcome.Failed -> TriggerTransferResult.Failed(archive, launch.reason, launch.message)
                    is LaunchOutcome.Started -> {
                        persistSnapshot(
                            usbMount, sessionDir, archives, currentIndex,
                            ActiveJobSnapshot(jobId, archive.stem, archive.variant, prepared.useStrategyA, RemoteJobLifecycle.RUNNING),
                            tileResultsSoFar
                        )
                        repository.awaitTransfer(
                            host, ADB_PORT, jobId, archive, sessionDir, usbMount, prepared.useStrategyA, _cancelRequested, onPhase
                        )
                    }
                }
            }
        }
    }

    private suspend fun reattachVariant(
        host: String,
        usbMount: UsbMount,
        sessionDir: String,
        archive: TriggerArchive,
        resume: ActiveJobSnapshot,
        onPhase: (TransferPhase) -> Unit
    ): TriggerTransferResult {
        if (resume.phase == RemoteJobLifecycle.LAUNCHING) {
            // The launch was persisted but never confirmed (the process died between writing
            // the "launching" record and the launch script's ADB round-trip completing) — the
            // job may or may not actually exist on the head unit. Per the recovery contract we
            // must never blindly relaunch a possibly-already-running item.
            Log.w(TAG, "Job ${resume.jobId} was LAUNCHING when the process died — cannot safely resume")
            return TriggerTransferResult.Failed(archive, FailureReason.WORKER_LOST, "Launch state unknown after app restart")
        }
        return when (val inspection = repository.inspectJob(host, ADB_PORT, resume.jobId, archive, resume.useStrategyA)) {
            is JobInspection.Alive -> {
                Log.d(TAG, "Reattaching to still-running job ${resume.jobId}")
                repository.awaitTransfer(
                    host, ADB_PORT, resume.jobId, archive, sessionDir, usbMount, resume.useStrategyA, _cancelRequested, onPhase
                )
            }
            is JobInspection.Terminal -> {
                Log.d(TAG, "Job ${resume.jobId} already terminal on reattach: ${inspection.result}")
                inspection.result
            }
            is JobInspection.Lost -> {
                Log.w(TAG, "Job ${resume.jobId} is no longer running and left no terminal status")
                TriggerTransferResult.Failed(archive, FailureReason.WORKER_LOST, "Worker process no longer running")
            }
            is JobInspection.CommunicationFailure -> {
                Log.w(TAG, "Could not reach head unit while inspecting job ${resume.jobId}: ${inspection.message}")
                TriggerTransferResult.Failed(archive, FailureReason.ADB_COMMUNICATION_ERROR, inspection.message)
            }
        }
    }

    private suspend fun runBatch(
        host: String,
        usbMount: UsbMount,
        sessionDir: String,
        archives: List<TriggerArchive>,
        startIndex: Int,
        seedTileResults: List<TileResultSnapshot>,
        resumeJob: ActiveJobSnapshot?
    ) {
        val allTiles = archives.map { a ->
            val seed = seedTileResults.find { it.stem == a.stem }
            val normal = seed?.normalResult?.toResult(a)
            val offline = seed?.offlineResult?.toResult(a.offlineVariant())
            ArchiveTileState(
                archive = a,
                isSelected = true,
                normalResult = normal,
                offlineResult = offline,
                result = if (normal != null) aggregateResults(normal, offline) else null
            )
        }.toMutableList()

        var tileResults = seedTileResults.toMutableList()
        var processed = 0; var succeeded = 0; var alreadyPresent = 0; var failed = 0; var cancelled = 0
        for (i in 0 until startIndex) {
            when (allTiles[i].result) {
                is TriggerTransferResult.Success -> { processed++; succeeded++ }
                is TriggerTransferResult.AlreadyPresent -> { processed++; alreadyPresent++ }
                is TriggerTransferResult.Failed -> { processed++; failed++ }
                is TriggerTransferResult.Cancelled -> { cancelled++ }
                null -> {}
            }
        }

        fun pushRunning(phase: TransferPhase?) {
            _state.value = UsbTransferCoordinatorState.Running(
                tiles = allTiles.toList(),
                progress = TransferProgressState(archives.size, processed, succeeded, alreadyPresent, failed, cancelled),
                phase = phase
            )
        }
        pushRunning(null)

        fun recordTileResult(stem: String, normal: TriggerTransferResult?, offline: TriggerTransferResult?) {
            val existing = tileResults.find { it.stem == stem }
            val updated = TileResultSnapshot(
                stem = stem,
                normalResult = normal?.toSummary() ?: existing?.normalResult,
                offlineResult = offline?.toSummary() ?: existing?.offlineResult
            )
            tileResults = tileResults.filterNot { it.stem == stem }.toMutableList().apply { add(updated) }
        }

        var currentResumeJob = resumeJob
        var breakLoop = false
        // Set to true when the batch stopped due to an infrastructure failure (ADB loss, USB
        // disconnect). In that case we leave the snapshot on disk and do NOT transition to
        // Completed so that attachOrRecover() can resume after the connection is restored.
        var infrastructureBreak = false

        for (i in startIndex until archives.size) {
            if (breakLoop) break
            val normalArchive = archives[i]
            val offlineArchive = normalArchive.offlineVariant()

            // A cancellation restored from disk may have been requested before this variant
            // was launched. There is no remote worker to stop in that case, so never launch one.
            if (_cancelRequested.value && currentResumeJob == null) {
                val cancelledResult = TriggerTransferResult.Cancelled(normalArchive, partialOutputRemoved = true)
                recordTileResult(normalArchive.stem, cancelledResult, null)
                allTiles[i] = allTiles[i].copy(result = cancelledResult, normalResult = cancelledResult)
                cancelled++
                breakLoop = true
                persistSnapshot(usbMount, sessionDir, archives, i, null, tileResults)
                pushRunning(null)
                continue
            }

            // Advance the persisted position to this tile before doing any work on it, with no
            // job in flight yet — unless we are reattaching this exact iteration, in which case
            // currentResumeJob already reflects the in-flight job and persistSnapshot calls
            // inside runVariant will keep it accurate.
            if (currentResumeJob == null) {
                persistSnapshot(usbMount, sessionDir, archives, i, null, tileResults)
            }

            var normalResult = allTiles[i].normalResult
            if (normalResult == null) {
                val firstAttempt = runVariant(
                    host, usbMount, sessionDir, normalArchive, currentResumeJob, archives, i, tileResults
                ) { phase -> pushRunning(phase) }
                currentResumeJob = null
                normalResult = if (firstAttempt is TriggerTransferResult.Failed && !firstAttempt.reason.isInfrastructure() && !_cancelRequested.value) {
                    runVariant(
                        host, usbMount, sessionDir, normalArchive, null, archives, i, tileResults
                    ) { phase -> pushRunning(phase) }
                } else {
                    firstAttempt
                }
                // Don't record ADB_COMMUNICATION_ERROR in tileResults. This is the WiFi-loss
                // case: recording it would prevent attachOrRecover() from retrying this tile
                // after the connection is restored, because seeded results are treated as
                // already-done by the resume path. All other failures (including other
                // infrastructure failures that complete the batch normally) are recorded.
                if (normalResult !is TriggerTransferResult.Failed || normalResult.reason != FailureReason.ADB_COMMUNICATION_ERROR) {
                    recordTileResult(normalArchive.stem, normalResult, null)
                }
            }

            val normalIsInfra = normalResult is TriggerTransferResult.Failed && normalResult.reason.isInfrastructure()
            var offlineResult: TriggerTransferResult? = allTiles[i].offlineResult

            if (normalResult !is TriggerTransferResult.Cancelled && !normalIsInfra && offlineResult == null) {
                offlineResult = if (_cancelRequested.value) {
                    TriggerTransferResult.Cancelled(offlineArchive, partialOutputRemoved = true)
                } else {
                    val firstAttempt = runVariant(
                        host, usbMount, sessionDir, offlineArchive, currentResumeJob, archives, i, tileResults
                    ) { phase -> pushRunning(phase) }
                    if (firstAttempt is TriggerTransferResult.Failed && !firstAttempt.reason.isInfrastructure() && !_cancelRequested.value) {
                        runVariant(
                            host, usbMount, sessionDir, offlineArchive, null, archives, i, tileResults
                        ) { phase -> pushRunning(phase) }
                    } else {
                        firstAttempt
                    }
                }
                currentResumeJob = null
                if (offlineResult !is TriggerTransferResult.Failed || offlineResult.reason != FailureReason.ADB_COMMUNICATION_ERROR) {
                    recordTileResult(normalArchive.stem, null, offlineResult)
                }
            }

            val aggregate = aggregateResults(normalResult, offlineResult)
            allTiles[i] = allTiles[i].copy(result = aggregate, normalResult = normalResult, offlineResult = offlineResult)

            when (aggregate) {
                is TriggerTransferResult.Success -> { processed++; succeeded++ }
                is TriggerTransferResult.AlreadyPresent -> { processed++; alreadyPresent++ }
                is TriggerTransferResult.Failed -> {
                    if (aggregate.reason == FailureReason.ADB_COMMUNICATION_ERROR) {
                        // ADB connection lost — WiFi likely dropped. Suspend the batch so it can
                        // be resumed by attachOrRecover() once connectivity is restored.
                        breakLoop = true
                        infrastructureBreak = true
                        // Preserve the in-flight job ID so attachOrRecover can call inspectJob
                        // after reconnection instead of blindly relaunching.
                        persistSnapshot(usbMount, sessionDir, archives, i, lastSnapshot?.currentJob, tileResults)
                    } else {
                        processed++; failed++
                        if (aggregate.reason.isInfrastructure()) breakLoop = true
                    }
                }
                is TriggerTransferResult.Cancelled -> {
                    cancelled++
                    breakLoop = true
                }
            }
            // For every non-infrastructure outcome: clear the in-flight job marker now that
            // this tile's variant(s) are terminal. Infra break already called persistSnapshot
            // above with the preserved job ID.
            if (!infrastructureBreak) {
                persistSnapshot(usbMount, sessionDir, archives, i, null, tileResults)
            }
            pushRunning(null)
        }

        if (infrastructureBreak) {
            // Leave the snapshot on disk and the coordinator in Running state. The batch will
            // be resumed by attachOrRecover() once the connection is restored (triggered by
            // HomeViewModel's auto-validation success handler or by UsbTransferService on restart).
            Log.w(TAG, "Batch suspended at index ${lastSnapshot?.currentIndex} due to infrastructure failure — awaiting reconnect")
            scheduleRecovery(host)
            return
        }

        repository.syncAfterTransfer(host, ADB_PORT)
        repository.cleanupEmptySessionDir(host, ADB_PORT, sessionDir, usbMount)

        _state.value = UsbTransferCoordinatorState.Completed(
            tiles = allTiles.toList(),
            progress = TransferProgressState(archives.size, processed, succeeded, alreadyPresent, failed, cancelled),
            sessionDir = sessionDir
        )
        // Clear the persisted active-batch marker only now that the final terminal state has
        // been committed to the in-memory StateFlow above — an application-scoped singleton,
        // so it remains observable across ViewModel/UI recreation without needing its own
        // disk-durable "Completed" record.
        clearSnapshot()
    }

    private fun scheduleRecovery(host: String) {
        if (recoveryJob?.isActive == true) return
        recoveryJob = coordinatorScope.launch {
            repeat(MAX_AUTOMATIC_RECOVERY_ATTEMPTS) {
                if (_state.value !is UsbTransferCoordinatorState.Running) return@launch
                delay(RECOVERY_RETRY_DELAY_MS)
                // The suspended batch job returns after it requests recovery. Do not attach
                // while it is still unwinding, or attachOrRecover correctly rejects it.
                if (batchJob?.isActive == true) return@repeat
                try {
                    if (attachOrRecover(host)) return@launch
                } catch (e: Exception) {
                    Log.w(TAG, "Automatic USB transfer recovery attempt failed", e)
                }
            }
        }
    }

    private suspend fun clearSnapshot() {
        snapshotMutex.withLock {
            lastSnapshot = null
            snapshotStore.clear()
        }
    }
}
