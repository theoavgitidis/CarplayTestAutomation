package com.example.adb_connection.data.usb

import com.example.adb_connection.domain.model.ActiveJobSnapshot
import com.example.adb_connection.domain.model.ActiveTransferSnapshot
import com.example.adb_connection.domain.model.ArchiveVariant
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.JobInspection
import com.example.adb_connection.domain.model.LaunchOutcome
import com.example.adb_connection.domain.model.PrepareOutcome
import com.example.adb_connection.domain.model.RemoteJobLifecycle
import com.example.adb_connection.domain.model.ResultKind
import com.example.adb_connection.domain.model.ResultSummary
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TileResultSnapshot
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbSessionDirResult
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.model.UsbTransferHandle
import com.example.adb_connection.domain.usb.ActiveTransferSnapshotCodec
import com.example.adb_connection.domain.usb.CoordinatorStartResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Covers the batch-execution behavior that used to live in `UsbCopyViewModel` (now moved to
 * [DefaultUsbTransferCoordinator]) plus the Prompt-2 recovery/reattachment acceptance scenarios:
 * same jobId preserved across recreation, RUNNING reattachment without relaunch, terminal status
 * on restart consumed exactly once with the queue continuing, explicit cancel vs. ordinary
 * teardown, and Completed staying observable across a fresh collector.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultUsbTransferCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var coordinatorScope: CoroutineScope

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        // Reuses the same StandardTestDispatcher scheduler as `runTest`'s own TestScope (runTest
        // picks up Dispatchers.Main when it has been overridden by a TestDispatcher), so
        // advanceUntilIdle()/runCurrent() drive coroutines launched in this scope too — exactly
        // like the real coordinatorScope is independent of any single caller's lifecycle, but
        // still deterministically steppable in tests.
        coordinatorScope = CoroutineScope(testDispatcher + SupervisorJob())
    }

    @After
    fun tearDown() {
        coordinatorScope.cancel()
        Dispatchers.resetMain()
    }

    // ── Shared fixtures ───────────────────────────────────────────────────────────

    private fun archive(n: Int) = TriggerArchive(
        triggerNumber = n,
        stem = "trigger_${n}_HU_20260701_113733_COREDUMP",
        archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_${n}_HU_20260701_113733_COREDUMP.tar.lz4",
        extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_${n}_HU_20260701_113733_COREDUMP"
    )

    private val usbMount = UsbMount(
        devicePath = "/dev/sda1",
        mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E",
        fileSystem = "exfat",
        mountOptions = setOf("rw", "relatime")
    )
    private val sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710"

    /** In-memory [TransferSnapshotStore] fake — no Android/DataStore dependency needed. */
    private class FakeSnapshotStore : TransferSnapshotStore {
        var raw: String? = null
        var saveCount = 0
        var clearCount = 0
        override suspend fun save(raw: String) {
            this.raw = raw
            saveCount++
        }
        override suspend fun load(): String? = raw
        override suspend fun clear() {
            raw = null
            clearCount++
        }
    }

    /**
     * Configurable fake driving the split [prepareTransfer]/[launchTransfer]/[awaitTransfer]/
     * [inspectJob] path the coordinator actually calls (unlike `UsbCopyViewModelTest`'s older
     * fakes, which only override the composed [transferArchive] convenience method that the
     * coordinator no longer uses).
     */
    private class FakeRepo(
        private val mount: UsbMount,
        private val session: String,
        private val sessionDirResult: UsbSessionDirResult = UsbSessionDirResult.Success(session),
        private val detectStatus: UsbDetectionResult? = null,
        private val prepareFn: (TriggerArchive) -> PrepareOutcome = { PrepareOutcome.Ready(useStrategyA = false) },
        private val launchFn: (TriggerArchive) -> LaunchOutcome = {
            LaunchOutcome.Started(UsbTransferHandle("job", 1, "/tmp/p", "/tmp/c"))
        },
        private val awaitFn: suspend (TriggerArchive, StateFlow<Boolean>, (TransferPhase) -> Unit) -> TriggerTransferResult = { a, cancelSignal, _ ->
            cancelSignal.first { it }
            TriggerTransferResult.Cancelled(a, partialOutputRemoved = true)
        },
        private val inspectFn: (String, TriggerArchive) -> JobInspection = { _, _ -> JobInspection.Lost }
    ) : UsbTransferRepository {
        var launchCount = 0
        var awaitCount = 0
        var inspectCount = 0
        var cleanupCalls = 0
        val launchedJobIds = mutableListOf<String>()

        override suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive> = emptyList()
        override suspend fun detectUsbMount(host: String, port: Int): UsbMount? =
            (detectStatus ?: UsbDetectionResult.Writable(mount)).let { it as? UsbDetectionResult.Writable }?.mount
        override suspend fun detectUsbStatus(host: String, port: Int): UsbDetectionResult =
            detectStatus ?: UsbDetectionResult.Writable(mount)
        override suspend fun checkDuplicate(host: String, port: Int, usbMount: UsbMount, stem: String): String? = null
        override suspend fun createSessionDir(host: String, port: Int, usbMount: UsbMount): UsbSessionDirResult = sessionDirResult
        override suspend fun prepareTransfer(
            host: String, port: Int, archive: TriggerArchive, usbMount: UsbMount, sessionDir: String
        ): PrepareOutcome = prepareFn(archive)
        override suspend fun launchTransfer(
            host: String, port: Int, jobId: String, archive: TriggerArchive,
            usbMount: UsbMount, sessionDir: String, useStrategyA: Boolean
        ): LaunchOutcome {
            launchCount++
            launchedJobIds += jobId
            return launchFn(archive)
        }
        override suspend fun awaitTransfer(
            host: String, port: Int, jobId: String, archive: TriggerArchive, sessionDir: String,
            usbMount: UsbMount, useStrategyA: Boolean, cancelSignal: StateFlow<Boolean>, onPhase: (TransferPhase) -> Unit
        ): TriggerTransferResult {
            awaitCount++
            return awaitFn(archive, cancelSignal, onPhase)
        }
        override suspend fun inspectJob(
            host: String, port: Int, jobId: String, archive: TriggerArchive, useStrategyA: Boolean
        ): JobInspection {
            inspectCount++
            return inspectFn(jobId, archive)
        }
        override suspend fun syncAfterTransfer(host: String, port: Int) { /* no-op */ }
        override suspend fun cleanupEmptySessionDir(host: String, port: Int, sessionDir: String, usbMount: UsbMount) {
            cleanupCalls++
        }
        override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult =
            UsbDeleteResult.Success
        override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult =
            UsbListingResult.UsbNotConnected()
    }

    /** Instantly resolves every await call to [resultFn]'s result — no cancellation handling. */
    private fun instantRepo(
        resultFn: (TriggerArchive) -> TriggerTransferResult
    ) = FakeRepo(usbMount, sessionDir, awaitFn = { a, _, _ -> resultFn(a) })

    /** Suspends until cancelSignal flips true, then returns Cancelled. */
    private fun hangingRepo() = FakeRepo(usbMount, sessionDir, awaitFn = { a, cancelSignal, _ ->
        cancelSignal.first { it }
        TriggerTransferResult.Cancelled(a, partialOutputRemoved = true)
    })

    private fun makeCoordinator(repo: UsbTransferRepository, store: TransferSnapshotStore = FakeSnapshotStore()) =
        DefaultUsbTransferCoordinator(repo, store, coordinatorScope)

    // ── Basic start/duplicate-start/session-dir-failure ──────────────────────────

    @Test
    fun `start begins Running and returns Started`() = runTest {
        val coordinator = makeCoordinator(instantRepo {
            TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
        })
        val result = coordinator.start("host", usbMount, listOf(archive(1)))
        assertEquals(CoordinatorStartResult.Started, result)
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Running)
        advanceUntilIdle()
    }

    @Test
    fun `duplicate start while Running returns AlreadyRunning and does not add work`() = runTest {
        val repo = hangingRepo()
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()

        val second = coordinator.start("host", usbMount, listOf(archive(2)))
        assertEquals(CoordinatorStartResult.AlreadyRunning, second)

        coordinator.requestCancel()
        advanceUntilIdle()
        // Only archive(1)'s normal variant should ever have been launched.
        assertEquals(1, repo.launchCount)
    }

    @Test
    fun `session dir failure returns SessionDirFailed and never enters Running`() = runTest {
        val repo = FakeRepo(
            usbMount, sessionDir,
            sessionDirResult = UsbSessionDirResult.Failed(FailureReason.USB_NOT_WRITABLE, "read-only")
        )
        val coordinator = makeCoordinator(repo)
        val result = coordinator.start("host", usbMount, listOf(archive(1)))
        assertTrue(result is CoordinatorStartResult.SessionDirFailed)
        assertEquals(FailureReason.USB_NOT_WRITABLE, (result as CoordinatorStartResult.SessionDirFailed).reason)
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Idle)
        assertEquals(0, repo.launchCount)
    }

    // ── Pre-transfer USB revalidation ──────────────────────────────────────────────

    @Test
    fun `start rejects a mount whose device identity changed`() = runTest {
        val freshMount = usbMount.copy(devicePath = "/dev/sdb")
        val repo = FakeRepo(
            usbMount, sessionDir,
            detectStatus = UsbDetectionResult.Writable(freshMount)
        )
        val coordinator = makeCoordinator(repo)
        val result = coordinator.start("host", usbMount, listOf(archive(1)))

        assertEquals(
            FailureReason.USB_MOUNT_CHANGED,
            (result as CoordinatorStartResult.SessionDirFailed).reason
        )
    }

    @Test
    fun `start returns USB_NOT_FOUND when USB is no longer present at transfer time`() = runTest {
        val repo = FakeRepo(usbMount, sessionDir, detectStatus = UsbDetectionResult.NotFound)
        val coordinator = makeCoordinator(repo)
        val result = coordinator.start("host", usbMount, listOf(archive(1)))
        assertTrue(result is CoordinatorStartResult.SessionDirFailed)
        assertEquals(FailureReason.USB_NOT_FOUND, (result as CoordinatorStartResult.SessionDirFailed).reason)
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Idle)
        assertEquals(0, repo.launchCount)
    }

    @Test
    fun `start returns USB_NOT_WRITABLE when USB is read-only at transfer time`() = runTest {
        val readOnlyMount = usbMount.copy(mountOptions = setOf("ro", "relatime"))
        val repo = FakeRepo(usbMount, sessionDir, detectStatus = UsbDetectionResult.ReadOnly(readOnlyMount))
        val coordinator = makeCoordinator(repo)
        val result = coordinator.start("host", usbMount, listOf(archive(1)))
        assertTrue(result is CoordinatorStartResult.SessionDirFailed)
        assertEquals(FailureReason.USB_NOT_WRITABLE, (result as CoordinatorStartResult.SessionDirFailed).reason)
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Idle)
        assertEquals(0, repo.launchCount)
    }

    @Test
    fun `start returns USB_MOUNT_CHANGED when a different mount path is present`() = runTest {
        // A completely different USB label (mount path) means a different stick was inserted.
        val differentLabelMount = usbMount.copy(mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/OTHERXXX", devicePath = "/dev/sdb1")
        val repo = FakeRepo(usbMount, sessionDir, detectStatus = UsbDetectionResult.Writable(differentLabelMount))
        val coordinator = makeCoordinator(repo)
        val result = coordinator.start("host", usbMount, listOf(archive(1)))
        assertTrue(result is CoordinatorStartResult.SessionDirFailed)
        assertEquals(FailureReason.USB_MOUNT_CHANGED, (result as CoordinatorStartResult.SessionDirFailed).reason)
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Idle)
        assertEquals(0, repo.launchCount)
    }

    @Test
    fun `start rejects device path re-enumeration without a stable volume identifier`() = runTest {
        val freshMount = usbMount.copy(devicePath = "/dev/sdb")
        val repo = FakeRepo(
            usbMount, sessionDir,
            detectStatus = UsbDetectionResult.Writable(freshMount),
            awaitFn = { a, _, _ -> TriggerTransferResult.Success(a, "$sessionDir/${a.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        )
        val coordinator = makeCoordinator(repo)
        val result = coordinator.start("host", usbMount, listOf(archive(1)))
        assertEquals(FailureReason.USB_MOUNT_CHANGED, (result as CoordinatorStartResult.SessionDirFailed).reason)
    }

    // ── Batch progress/completion semantics (ported from the old ViewModel-owned loop) ─

    @Test
    fun `success increments processed and succeeded`() = runTest {
        val repo = instantRepo { TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()

        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(1, completed.progress.processed)
        assertEquals(1, completed.progress.succeeded)
    }

    @Test
    fun `already present counts as processed and not failed`() = runTest {
        val repo = instantRepo { TriggerTransferResult.AlreadyPresent(it, "$sessionDir/${it.stem}") }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()

        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(1, completed.progress.alreadyPresent)
        assertEquals(0, completed.progress.failed)
    }

    @Test
    fun `archive-specific failure retries once then continues with offline variant and next archive`() = runTest {
        var callCount = 0
        val repo = instantRepo {
            callCount++
            TriggerTransferResult.Failed(it, FailureReason.SOURCE_ARCHIVE_MISSING)
        }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1), archive(2)))
        testDispatcher.scheduler.runCurrent()

        // Non-infrastructure: 2 attempts (first + retry) × 2 variants × 2 archives = 8
        assertEquals("each non-infra variant gets one retry before moving on", 8, callCount)
        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(2, completed.progress.failed)
        assertEquals(0, completed.progress.notStarted)
    }

    @Test
    fun `ADB_COMMUNICATION_ERROR suspends the batch in Running for later resume`() = runTest {
        var callCount = 0
        val repo = instantRepo {
            callCount++
            TriggerTransferResult.Failed(it, FailureReason.ADB_COMMUNICATION_ERROR)
        }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1), archive(2)))
        testDispatcher.scheduler.runCurrent()

        // ADB_COMMUNICATION_ERROR is the WiFi-drop case — batch suspends, not completes.
        assertEquals("must stop after the first variant fails with ADB_COMMUNICATION_ERROR", 1, callCount)
        assertTrue(
            "coordinator must remain Running so the batch can be resumed after reconnect",
            coordinator.state.value is UsbTransferCoordinatorState.Running
        )
    }

    @Test
    fun `non-ADB infrastructure failure (WORKER_LOST) stops the batch as Completed`() = runTest {
        var callCount = 0
        val repo = instantRepo {
            callCount++
            TriggerTransferResult.Failed(it, FailureReason.WORKER_LOST)
        }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1), archive(2)))
        advanceUntilIdle()

        // WORKER_LOST is infrastructure but not recoverable via reconnect — batch completes normally.
        assertEquals("must stop after the first call (no retry for infra failures)", 1, callCount)
        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(1, completed.progress.failed)
        assertEquals(1, completed.progress.notStarted)
    }

    @Test
    fun `cancel stops processing remaining items`() = runTest {
        var callCount = 0
        val repo = FakeRepo(usbMount, sessionDir, awaitFn = { a, cancelSignal, _ ->
            callCount++
            cancelSignal.first { it }
            TriggerTransferResult.Cancelled(a, partialOutputRemoved = true)
        })
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1), archive(2)))
        testDispatcher.scheduler.runCurrent()

        coordinator.requestCancel()
        advanceUntilIdle()

        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals("only the first item's normal variant should have started", 1, callCount)
        assertEquals(1, completed.progress.cancelled)
        assertEquals(2, completed.progress.notStarted)
    }

    @Test
    fun `transfer order is normal then offline variant`() = runTest {
        val calledStems = mutableListOf<String>()
        val repo = instantRepo {
            calledStems += it.stem
            TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
        }
        val coordinator = makeCoordinator(repo)
        val a1 = archive(1)
        coordinator.start("host", usbMount, listOf(a1))
        advanceUntilIdle()

        assertEquals(listOf(a1.stem, a1.offlineVariant().stem), calledStems)
    }

    @Test
    fun `cleanupEmptySessionDir is invoked exactly once after the batch completes`() = runTest {
        val repo = instantRepo { TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()

        assertEquals(1, repo.cleanupCalls)
    }

    @Test
    fun `phase transitions are surfaced while Running`() = runTest {
        val phases = mutableListOf<TransferPhase>()
        val repo = FakeRepo(usbMount, sessionDir, awaitFn = { a, _, onPhase ->
            onPhase(TransferPhase.Extracting(a))
            // Yield so the StateFlow collector below (running on the same TestDispatcher) gets
            // a chance to observe the Extracting phase before the batch continues — without this,
            // the whole batch runs synchronously to Completed in one dispatcher task and the
            // conflated StateFlow would only ever deliver the final state to a slow collector.
            kotlinx.coroutines.yield()
            TriggerTransferResult.Success(a, "$sessionDir/${a.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
        })
        val coordinator = makeCoordinator(repo)
        val collectJob = launch {
            coordinator.state.collect { s ->
                if (s is UsbTransferCoordinatorState.Running) s.phase?.let { phases += it }
            }
        }
        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()

        assertTrue("Extracting phase must have been observed", phases.any { it is TransferPhase.Extracting })
        collectJob.cancel()
    }

    @Test
    fun `dismissCompleted resets Completed to Idle and is a no-op otherwise`() = runTest {
        val repo = instantRepo { TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Completed)

        coordinator.dismissCompleted()
        assertEquals(UsbTransferCoordinatorState.Idle, coordinator.state.value)

        // No-op when already Idle.
        coordinator.dismissCompleted()
        assertEquals(UsbTransferCoordinatorState.Idle, coordinator.state.value)
    }

    // ── Prompt 2 acceptance: recovery / reattachment ─────────────────────────────

    @Test
    fun `same jobId is persisted at LAUNCHING then RUNNING before and after launch`() = runTest {
        val store = FakeSnapshotStore()
        val repo = hangingRepo()
        val coordinator = makeCoordinator(repo, store)
        coordinator.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()

        val snapshot = ActiveTransferSnapshotCodec.decode(store.raw!!)!!
        assertEquals(RemoteJobLifecycle.RUNNING, snapshot.currentJob?.phase)
        assertEquals(repo.launchedJobIds.single(), snapshot.currentJob?.jobId)

        coordinator.requestCancel()
        advanceUntilIdle()
    }

    @Test
    fun `service recreation with RUNNING status reattaches without relaunching`() = runTest {
        val store = FakeSnapshotStore()
        val repo1 = FakeRepo(usbMount, sessionDir, awaitFn = { a, cancelSignal, _ ->
            // Suspend forever from this coordinator's point of view — simulates the process
            // dying while the remote worker is still running on the head unit.
            cancelSignal.first { it }
            TriggerTransferResult.Cancelled(a, partialOutputRemoved = true)
        })
        val coordinatorA = makeCoordinator(repo1, store)
        coordinatorA.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, repo1.launchCount)
        val originalJobId = repo1.launchedJobIds.single()
        // Simulate the process (and this coordinator instance) dying: abandon coordinatorA
        // without ever cancelling/completing its batch — the RUNNING snapshot is already on disk.

        // A fresh coordinator (as if the service/process were recreated) shares only the
        // persisted snapshot and the repository — reattach must resolve the in-flight normal
        // variant via inspectJob/awaitTransfer (never a fresh launchTransfer for that same job).
        // The offline variant was never started before the "crash", so it legitimately proceeds
        // as a brand-new job once the normal variant resolves.
        val repo2 = FakeRepo(
            usbMount, sessionDir,
            inspectFn = { _, _ -> JobInspection.Alive },
            awaitFn = { a, _, _ -> TriggerTransferResult.Success(a, "$sessionDir/${a.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        )
        // Re-use the same persisted raw snapshot text (jobId, sessionDir, etc.) written by coordinatorA.
        val recoveredStore = FakeSnapshotStore().apply { raw = store.raw }
        val coordinatorB = makeCoordinator(repo2, recoveredStore)

        val recovered = coordinatorB.attachOrRecover("host")
        advanceUntilIdle()

        assertTrue("A persisted batch must be found and recovery attempted", recovered)
        assertEquals(1, repo2.inspectCount)
        assertEquals(
            "the resumed normal-variant job must never be relaunched",
            false,
            repo2.launchedJobIds.contains(originalJobId)
        )
        assertEquals(
            "only the not-yet-started offline variant should be launched fresh",
            1, repo2.launchCount
        )
        assertTrue(coordinatorB.state.value is UsbTransferCoordinatorState.Completed)
    }

    @Test
    fun `terminal status on restart is consumed once and the queue continues exactly once`() = runTest {
        val a1 = archive(1)
        val a2 = archive(2)
        // a1's normal variant already succeeded before the crash; a1's offline variant was
        // RUNNING and, per the head unit, actually finished successfully while we were dead.
        val snapshot = ActiveTransferSnapshot(
            expectedMount = usbMount,
            sessionDir = sessionDir,
            archives = listOf(a1, a2),
            currentIndex = 0,
            currentJob = ActiveJobSnapshot(
                jobId = "job-offline-a1", stem = a1.offlineVariant().stem, variant = ArchiveVariant.OFFLINE_TRACE,
                useStrategyA = false, phase = RemoteJobLifecycle.RUNNING
            ),
            tileResults = listOf(
                TileResultSnapshot(
                    stem = a1.stem,
                    normalResult = ResultSummary(ResultKind.SUCCESS, path = "$sessionDir/${a1.stem}", sourceType = SourceType.ARCHIVE_EXTRACTED_TO_USB)
                )
            ),
            cancelRequested = false
        )
        val store = FakeSnapshotStore().apply { raw = ActiveTransferSnapshotCodec.encode(snapshot) }

        var terminalConsumedCount = 0
        val a2Calls = mutableListOf<String>()
        val repo = FakeRepo(
            usbMount, sessionDir,
            inspectFn = { _, _ ->
                terminalConsumedCount++
                JobInspection.Terminal(TriggerTransferResult.Success(a1.offlineVariant(), "$sessionDir/${a1.offlineVariant().stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB))
            },
            awaitFn = { a, _, _ ->
                a2Calls += a.stem
                TriggerTransferResult.Success(a, "$sessionDir/${a.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
            }
        )
        val coordinator = makeCoordinator(repo, store)
        val recovered = coordinator.attachOrRecover("host")
        advanceUntilIdle()

        assertTrue(recovered)
        assertEquals("the terminal status must be inspected exactly once", 1, terminalConsumedCount)
        assertEquals("the queue must continue with a2's two variants exactly once",
            listOf(a2.stem, a2.offlineVariant().stem), a2Calls)
        // a1's offline variant is resolved via inspectJob (terminal, consumed once) rather than a
        // fresh launch; a2's normal + offline variants were never started before the "crash" and
        // legitimately proceed as two brand-new launches.
        assertEquals("only a2's two not-yet-started variants should be launched fresh", 2, repo.launchCount)

        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(2, completed.progress.succeeded)
        assertEquals(0, completed.progress.failed)
    }

    @Test
    fun `LAUNCHING phase on restart is reported as WORKER_LOST without inspecting the job`() = runTest {
        val a1 = archive(1)
        val snapshot = ActiveTransferSnapshot(
            expectedMount = usbMount,
            sessionDir = sessionDir,
            archives = listOf(a1),
            currentIndex = 0,
            currentJob = ActiveJobSnapshot(
                jobId = "job-launching", stem = a1.stem, variant = ArchiveVariant.NORMAL,
                useStrategyA = false, phase = RemoteJobLifecycle.LAUNCHING
            ),
            tileResults = emptyList(),
            cancelRequested = false
        )
        val store = FakeSnapshotStore().apply { raw = ActiveTransferSnapshotCodec.encode(snapshot) }
        val repo = FakeRepo(usbMount, sessionDir, inspectFn = { _, _ -> JobInspection.Alive })
        val coordinator = makeCoordinator(repo, store)

        coordinator.attachOrRecover("host")
        advanceUntilIdle()

        assertEquals("must never contact the head unit for a launch that was never confirmed", 0, repo.inspectCount)
        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        val result = completed.tiles.first { it.archive.stem == a1.stem }.normalResult as TriggerTransferResult.Failed
        assertEquals(FailureReason.WORKER_LOST, result.reason)
    }

    @Test
    fun `dead PID with no terminal status reports WORKER_LOST and does not relaunch`() = runTest {
        val a1 = archive(1)
        val snapshot = ActiveTransferSnapshot(
            expectedMount = usbMount,
            sessionDir = sessionDir,
            archives = listOf(a1),
            currentIndex = 0,
            currentJob = ActiveJobSnapshot(
                jobId = "job-dead", stem = a1.stem, variant = ArchiveVariant.NORMAL,
                useStrategyA = false, phase = RemoteJobLifecycle.RUNNING
            ),
            tileResults = emptyList(),
            cancelRequested = false
        )
        val store = FakeSnapshotStore().apply { raw = ActiveTransferSnapshotCodec.encode(snapshot) }
        val repo = FakeRepo(usbMount, sessionDir, inspectFn = { _, _ -> JobInspection.Lost })
        val coordinator = makeCoordinator(repo, store)

        coordinator.attachOrRecover("host")
        advanceUntilIdle()

        assertEquals("a Lost job must never be relaunched", 0, repo.launchCount)
        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        val normalResult = completed.tiles.first().normalResult as TriggerTransferResult.Failed
        assertEquals(FailureReason.WORKER_LOST, normalResult.reason)
    }

    @Test
    fun `recovery when expected USB mount is missing reports USB_DISCONNECTED and clears the snapshot`() = runTest {
        val a1 = archive(1)
        val snapshot = ActiveTransferSnapshot(
            expectedMount = usbMount, sessionDir = sessionDir, archives = listOf(a1),
            currentIndex = 0, currentJob = null, tileResults = emptyList(), cancelRequested = false
        )
        val store = FakeSnapshotStore().apply { raw = ActiveTransferSnapshotCodec.encode(snapshot) }
        val repo = FakeRepo(usbMount, sessionDir, detectStatus = UsbDetectionResult.NotFound)
        val coordinator = makeCoordinator(repo, store)

        val recovered = coordinator.attachOrRecover("host")
        advanceUntilIdle()

        assertTrue(recovered)
        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        val result = completed.tiles.first().normalResult as TriggerTransferResult.Failed
        assertEquals(FailureReason.USB_DISCONNECTED, result.reason)
        assertNull("snapshot must be cleared once resolved as unresumable", store.raw)
    }

    @Test
    fun `recovery when a different USB stick is present reports USB_MOUNT_CHANGED`() = runTest {
        val a1 = archive(1)
        val snapshot = ActiveTransferSnapshot(
            expectedMount = usbMount, sessionDir = sessionDir, archives = listOf(a1),
            currentIndex = 0, currentJob = null, tileResults = emptyList(), cancelRequested = false
        )
        val store = FakeSnapshotStore().apply { raw = ActiveTransferSnapshotCodec.encode(snapshot) }
        val differentMount = usbMount.copy(devicePath = "/dev/sdb1")
        val repo = FakeRepo(usbMount, sessionDir, detectStatus = UsbDetectionResult.Writable(differentMount))
        val coordinator = makeCoordinator(repo, store)

        coordinator.attachOrRecover("host")
        advanceUntilIdle()

        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        val result = completed.tiles.first().normalResult as TriggerTransferResult.Failed
        assertEquals(FailureReason.USB_MOUNT_CHANGED, result.reason)
    }

    @Test
    fun `attachOrRecover with no persisted snapshot is a no-op`() = runTest {
        val repo = instantRepo { TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        val coordinator = makeCoordinator(repo)
        val recovered = coordinator.attachOrRecover("host")
        assertFalse(recovered)
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Idle)
    }

    // ── WiFi-disconnect / infrastructure-break resumption ────────────────────────

    @Test
    fun `infrastructure failure leaves snapshot on disk and coordinator in Running`() = runTest {
        val store = FakeSnapshotStore()
        val repo = instantRepo { TriggerTransferResult.Failed(it, FailureReason.ADB_COMMUNICATION_ERROR) }
        val coordinator = makeCoordinator(repo, store)

        coordinator.start("host", usbMount, listOf(archive(1), archive(2)))
        testDispatcher.scheduler.runCurrent()

        // The coordinator must still be Running (not Completed) so the UI shows an in-progress
        // state and the user is not prompted to dismiss a result overlay.
        assertTrue(
            "coordinator must remain Running after an infrastructure failure",
            coordinator.state.value is UsbTransferCoordinatorState.Running
        )
        assertFalse(
            "snapshot must NOT be cleared after an infrastructure failure",
            store.raw == null
        )
    }

    @Test
    fun `infrastructure failure preserves job ID in snapshot for reconnect`() = runTest {
        val store = FakeSnapshotStore()
        val repo = FakeRepo(usbMount, sessionDir,
            awaitFn = { a, _, _ -> TriggerTransferResult.Failed(a, FailureReason.ADB_COMMUNICATION_ERROR) }
        )
        val coordinator = makeCoordinator(repo, store)

        coordinator.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()

        val snapshot = ActiveTransferSnapshotCodec.decode(store.raw!!)!!
        // The exact job ID is generated internally; what matters is that it is non-null so
        // attachOrRecover can call inspectJob on reconnect.
        assertFalse(
            "snapshot must retain a non-null currentJob so attachOrRecover can call inspectJob",
            snapshot.currentJob == null
        )
        assertEquals(
            "snapshot must be at the interrupted archive's index",
            archive(1).stem,
            snapshot.currentJob?.stem
        )
    }

    @Test
    fun `infrastructure failure does NOT record the tile result so attachOrRecover retries it`() = runTest {
        val store = FakeSnapshotStore()
        val repo = instantRepo { TriggerTransferResult.Failed(it, FailureReason.ADB_COMMUNICATION_ERROR) }
        val coordinator = makeCoordinator(repo, store)

        coordinator.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()

        val snapshot = ActiveTransferSnapshotCodec.decode(store.raw!!)!!
        assertTrue(
            "tileResults must be empty so the resume path retries the failed tile",
            snapshot.tileResults.isEmpty()
        )
    }

    @Test
    fun `attachOrRecover after infrastructure failure resumes and completes the batch`() = runTest {
        // Phase 1: start a transfer; the await call fails with ADB_COMMUNICATION_ERROR.
        val store = FakeSnapshotStore()
        val repo1 = FakeRepo(usbMount, sessionDir,
            launchFn = { LaunchOutcome.Started(UsbTransferHandle("job-abc", 1, "/tmp/p", "/tmp/c")) },
            awaitFn = { a, _, _ -> TriggerTransferResult.Failed(a, FailureReason.ADB_COMMUNICATION_ERROR) }
        )
        val coordinatorA = makeCoordinator(repo1, store)
        coordinatorA.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()
        assertTrue(coordinatorA.state.value is UsbTransferCoordinatorState.Running)

        // Phase 2: WiFi restores. A new coordinator instance (same as process-restart) finds the
        // snapshot, calls inspectJob (worker finished successfully), and completes the batch.
        val repo2 = FakeRepo(usbMount, sessionDir,
            inspectFn = { _, a -> JobInspection.Terminal(
                TriggerTransferResult.Success(a, "$sessionDir/${a.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
            )},
            awaitFn = { a, _, _ ->
                TriggerTransferResult.Success(a, "$sessionDir/${a.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
            }
        )
        val storeB = FakeSnapshotStore().apply { raw = store.raw }
        val coordinatorB = makeCoordinator(repo2, storeB)

        val recovered = coordinatorB.attachOrRecover("host")
        advanceUntilIdle()

        assertTrue("attachOrRecover must find and resume the snapshot", recovered)
        // inspectJob called for the normal variant (was in-flight); offline variant launched fresh.
        assertEquals(1, repo2.inspectCount)
        assertTrue(coordinatorB.state.value is UsbTransferCoordinatorState.Completed)
        val completed = coordinatorB.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(0, completed.progress.failed)
    }

    @Test
    fun `ADB communication failure is retried automatically without recreating the service`() = runTest {
        val store = FakeSnapshotStore()
        var awaitCalls = 0
        val repo = FakeRepo(
            usbMount, sessionDir,
            inspectFn = { _, archive ->
                JobInspection.Terminal(
                    TriggerTransferResult.Success(archive, "$sessionDir/${archive.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
                )
            },
            awaitFn = { archive, _, _ ->
                awaitCalls++
                if (awaitCalls == 1) {
                    TriggerTransferResult.Failed(archive, FailureReason.ADB_COMMUNICATION_ERROR)
                } else {
                    TriggerTransferResult.Success(archive, "$sessionDir/${archive.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB)
                }
            }
        )
        val coordinator = makeCoordinator(repo, store)

        coordinator.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()
        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Running)

        advanceTimeBy(5_000)
        advanceUntilIdle()

        assertTrue(coordinator.state.value is UsbTransferCoordinatorState.Completed)
        assertEquals(1, repo.inspectCount)
    }

    @Test
    fun `non-infrastructure failure still completes the batch and clears the snapshot`() = runTest {
        val store = FakeSnapshotStore()
        val repo = instantRepo { TriggerTransferResult.Failed(it, FailureReason.SOURCE_ARCHIVE_MISSING) }
        val coordinator = makeCoordinator(repo, store)

        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()

        assertTrue(
            "non-infrastructure failure must still complete the batch",
            coordinator.state.value is UsbTransferCoordinatorState.Completed
        )
        assertNull(
            "snapshot must be cleared after a normal (non-infrastructure) completion",
            store.raw
        )
    }

    // ── Explicit cancel vs. ordinary teardown ─────────────────────────────────────

    @Test
    fun `explicit requestCancel is observed by the in-flight await and is idempotent`() = runTest {
        var trueFlips = 0
        val repo = FakeRepo(usbMount, sessionDir, awaitFn = { a, cancelSignal, _ ->
            cancelSignal.collect { if (it) trueFlips++ }
            @Suppress("UNREACHABLE_CODE")
            TriggerTransferResult.Cancelled(a, partialOutputRemoved = true)
        })
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        testDispatcher.scheduler.runCurrent()

        coordinator.requestCancel()
        coordinator.requestCancel()
        coordinator.requestCancel()
        advanceUntilIdle()

        assertEquals("cancelSignal must flip to true exactly once regardless of repeat calls", 1, trueFlips)
    }

    @Test
    fun `cancelling an external caller scope does not cancel the coordinator's own batch`() = runTest {
        val repo = instantRepo { TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        val coordinator = makeCoordinator(repo)

        // Simulate a ViewModel/Activity scope that calls start() and is then torn down
        // immediately afterward (screen-off, recomposition, ViewModel destruction).
        val callerScope = CoroutineScope(testDispatcher + SupervisorJob())
        callerScope.launch { coordinator.start("host", usbMount, listOf(archive(1))) }
        testDispatcher.scheduler.runCurrent()
        callerScope.cancel() // ordinary teardown — must NOT reach requestCancel()

        advanceUntilIdle()

        // The batch must have continued to completion in the coordinator's own scope, entirely
        // unaffected by the caller scope's cancellation.
        val completed = coordinator.state.value as UsbTransferCoordinatorState.Completed
        assertEquals(1, completed.progress.succeeded)
    }

    // ── Completed remains observable across a fresh collector ("UI recreation") ──

    @Test
    fun `Completed state remains observable to a newly-attached collector`() = runTest {
        val repo = instantRepo { TriggerTransferResult.Success(it, "$sessionDir/${it.stem}", SourceType.ARCHIVE_EXTRACTED_TO_USB) }
        val coordinator = makeCoordinator(repo)
        coordinator.start("host", usbMount, listOf(archive(1)))
        advanceUntilIdle()

        // A fresh collector (simulating a new UI subscribing after recreation) must immediately
        // observe the already-Completed state via StateFlow's replay-latest semantics.
        var observed: UsbTransferCoordinatorState? = null
        val collectJob = launch { coordinator.state.collect { observed = it } }
        testDispatcher.scheduler.runCurrent()

        assertTrue(observed is UsbTransferCoordinatorState.Completed)
        collectJob.cancel()
    }
}
