package com.example.adb_connection.ui.usbcopy

import com.example.adb_connection.data.settings.SshHostProvider
import com.example.adb_connection.data.usb.UsbTransferRepository
import com.example.adb_connection.domain.model.ArchiveTileState
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TransferProgressState
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbRemountResult
import com.example.adb_connection.domain.model.UsbSessionDirResult
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.usb.CoordinatorStartResult
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * Covers only the state this ViewModel itself owns post-Prompt-2: discovery/selection,
 * eject/remount, and delegation of start/cancel to a [UsbTransferCoordinator], plus reflecting
 * the coordinator's state into [UsbCopyUiState]. Batch-execution correctness (progress
 * accounting, variant ordering, cancellation propagation, crash-recovery/reattachment) has moved
 * to `DefaultUsbTransferCoordinatorTest` alongside the coordinator implementation, since the
 * ViewModel no longer runs that loop itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UsbCopyViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    // ── Shared fake infrastructure ───────────────────────────────────────────────

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

    private val fakeHost = object : SshHostProvider {
        override val sshHost = flowOf("192.168.0.1")
    }

    /**
     * Base stub covering discovery/eject/remount plumbing only — the transfer batch itself is
     * owned by [FakeCoordinator] in these tests, not this repository (see
     * `DefaultUsbTransferCoordinatorTest` for batch-execution behavior via the real repository
     * contract).
     */
    private abstract class BaseRepo(
        private val archives: List<TriggerArchive>,
        private val mount: UsbMount,
        private val session: String
    ) : UsbTransferRepository {
        override suspend fun discoverArchives(host: String, port: Int) = archives
        override suspend fun detectUsbMount(host: String, port: Int) = mount
        override suspend fun checkDuplicate(host: String, port: Int, usbMount: UsbMount, stem: String): String? = null
        override suspend fun createSessionDir(host: String, port: Int, usbMount: UsbMount) =
            UsbSessionDirResult.Success(session)
        override suspend fun syncAfterTransfer(host: String, port: Int) { /* batch execution owned by the coordinator */ }
        override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult =
            UsbDeleteResult.Success
        override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult =
            UsbListingResult.UsbNotConnected()
    }

    private fun basicRepo(archives: List<TriggerArchive>): UsbTransferRepository =
        object : BaseRepo(archives, usbMount, sessionDir) {}

    /**
     * Fake application-scoped coordinator. Only lets these ViewModel tests drive/observe
     * start/cancel/state deterministically — the real batch-execution logic (what happens
     * *inside* Running, recovery, etc.) is exercised by `DefaultUsbTransferCoordinatorTest`.
     */
    private class FakeCoordinator(
        private val startResult: CoordinatorStartResult = CoordinatorStartResult.Started
    ) : UsbTransferCoordinator {
        private val _state = MutableStateFlow<UsbTransferCoordinatorState>(UsbTransferCoordinatorState.Idle)
        override val state: StateFlow<UsbTransferCoordinatorState> = _state.asStateFlow()

        var startCalls = 0
        var cancelCalls = 0
        var attachOrRecoverCalls = 0
        var attachOrRecoverResult = false

        override suspend fun start(host: String, usbMount: UsbMount, archives: List<TriggerArchive>): CoordinatorStartResult {
            startCalls++
            if (_state.value is UsbTransferCoordinatorState.Running) return CoordinatorStartResult.AlreadyRunning
            if (startResult is CoordinatorStartResult.Started) {
                _state.value = UsbTransferCoordinatorState.Running(
                    tiles = archives.map { ArchiveTileState(archive = it, isSelected = true) },
                    progress = TransferProgressState.zero(archives.size),
                    phase = null
                )
            }
            return startResult
        }

        override fun requestCancel() {
            cancelCalls++
        }

        override suspend fun attachOrRecover(host: String): Boolean {
            attachOrRecoverCalls++
            return attachOrRecoverResult
        }

        override fun dismissCompleted() {
            if (_state.value is UsbTransferCoordinatorState.Completed) {
                _state.value = UsbTransferCoordinatorState.Idle
            }
        }

        fun setState(newState: UsbTransferCoordinatorState) {
            _state.value = newState
        }
    }

    private fun completedState(
        tiles: List<ArchiveTileState>,
        session: String? = sessionDir
    ) = UsbTransferCoordinatorState.Completed(
        tiles = tiles,
        progress = TransferProgressState(
            totalSelected = tiles.size,
            processed = tiles.count { it.result != null && it.result !is TriggerTransferResult.Cancelled },
            succeeded = tiles.count { it.result is TriggerTransferResult.Success },
            alreadyPresent = tiles.count { it.result is TriggerTransferResult.AlreadyPresent },
            failed = tiles.count { it.result is TriggerTransferResult.Failed },
            cancelled = tiles.count { it.result is TriggerTransferResult.Cancelled }
        ),
        sessionDir = session
    )

    private fun makeViewModel(
        repo: UsbTransferRepository,
        coordinator: FakeCoordinator = FakeCoordinator(),
        onStartService: () -> Unit = {}
    ) = UsbCopyViewModel(fakeHost, repo, coordinator, onStartService)

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Duplicate detection during discovery ─────────────────────────────────────

    @Test
    fun `tile is marked already-on-USB when batched listing returns both variants during discovery`() = runTest {
        val normalPath = "$sessionDir/${archive(2).stem}"
        val offlinePath = "$sessionDir/${archive(2).offlineVariant().stem}"
        val repo = object : BaseRepo(listOf(archive(2)), usbMount, sessionDir) {
            override suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount) =
                mapOf(archive(2).stem to normalPath, archive(2).offlineVariant().stem to offlinePath)
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        val tile = ready.tiles.first { it.archive.triggerNumber == 2 }
        assertTrue("Tile must be flagged as already on USB when both variants are present", tile.isAlreadyOnUsb)
        assertEquals(normalPath, tile.existingUsbPath)
    }

    @Test
    fun `tile disabled for selection when already on USB`() = runTest {
        val existingPath = "$sessionDir/${archive(2).stem}"
        val repo = object : BaseRepo(listOf(archive(2)), usbMount, sessionDir) {
            override suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount) =
                mapOf(archive(2).stem to existingPath, archive(2).offlineVariant().stem to "${sessionDir}/${archive(2).offlineVariant().stem}")
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        vm.toggleSelection(archive(2))

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        val tile = ready.tiles.first { it.archive.triggerNumber == 2 }
        assertFalse("Toggle must be blocked for an already-on-USB tile", tile.isSelected)
    }

    @Test
    fun `tile in older session is still detected as duplicate`() = runTest {
        val olderSession = "${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260601_100000"
        val existingPath = "$olderSession/${archive(3).stem}"
        val repo = object : BaseRepo(listOf(archive(3)), usbMount, sessionDir) {
            override suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount) =
                mapOf(archive(3).stem to existingPath, archive(3).offlineVariant().stem to "$olderSession/${archive(3).offlineVariant().stem}")
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertTrue("Duplicate in an older session must still mark tile as already on USB",
            ready.tiles.first().isAlreadyOnUsb)
    }

    @Test
    fun `tile is already-on-USB only when both variants are present`() = runTest {
        val a1 = archive(1)
        val offlineA1 = a1.offlineVariant()
        val repo = object : BaseRepo(listOf(a1), usbMount, sessionDir) {
            override suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount) =
                mapOf(a1.stem to "$sessionDir/${a1.stem}", offlineA1.stem to "$sessionDir/${offlineA1.stem}")
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertTrue("Tile must be disabled when both variants are present", ready.tiles.first().isAlreadyOnUsb)
    }

    @Test
    fun `tile is not already-on-USB when only one variant is present`() = runTest {
        val a1 = archive(1)
        val repo = object : BaseRepo(listOf(a1), usbMount, sessionDir) {
            override suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount) =
                mapOf(a1.stem to "$sessionDir/${a1.stem}")
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertFalse("Tile must be selectable when only normal variant is present", ready.tiles.first().isAlreadyOnUsb)
    }

    // ── Begin disabled when nothing is selected ───────────────────────────────────

    @Test
    fun `Begin is disabled when nothing is selected`() = runTest {
        val vm = makeViewModel(basicRepo(listOf(archive(1))))
        vm.loadArchives()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertFalse("No tile should be selected initially",
            ready.tiles.any { it.isSelected && !it.isAlreadyOnUsb })
    }

    // ── startTransfer / coordinator delegation ────────────────────────────────────

    @Test
    fun `startTransfer delegates to coordinator start with selected archives only`() = runTest {
        val a1 = archive(1); val a2 = archive(2)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1, a2)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        assertEquals(1, coordinator.startCalls)
        assertTrue(vm.uiState.value is UsbCopyUiState.Transferring)
    }

    @Test
    fun `startTransfer is a no-op when nothing is selected`() = runTest {
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(archive(1))), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.startTransfer()
        advanceUntilIdle()

        assertEquals(0, coordinator.startCalls)
    }

    @Test
    fun `no transfer started when tile is already on USB at discovery time`() = runTest {
        val a1 = archive(1)
        val existingPath = "$sessionDir/${a1.stem}"
        val coordinator = FakeCoordinator()
        val repo = object : BaseRepo(listOf(a1), usbMount, sessionDir) {
            override suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount) =
                mapOf(a1.stem to existingPath, a1.offlineVariant().stem to "$sessionDir/${a1.offlineVariant().stem}")
        }
        val vm = makeViewModel(repo, coordinator)
        vm.loadArchives()
        advanceUntilIdle()

        // Tile is already on USB, so toggleSelection is a no-op and startTransfer has nothing to send.
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        assertEquals("coordinator must never be started for an already-on-USB-only selection", 0, coordinator.startCalls)
    }

    @Test
    fun `duplicate startTransfer while Running is ignored by the ViewModel`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UsbCopyUiState.Transferring)

        vm.startTransfer() // duplicate — the ViewModel's own guard must skip calling coordinator.start again
        advanceUntilIdle()

        assertEquals(1, coordinator.startCalls)
    }

    @Test
    fun `startTransfer starts the foreground service when the coordinator accepts the batch`() = runTest {
        val a1 = archive(1)
        var serviceStarts = 0
        val vm = makeViewModel(basicRepo(listOf(a1)), FakeCoordinator(), onStartService = { serviceStarts++ })
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        assertEquals(1, serviceStarts)
    }

    @Test
    fun `startTransfer does not start the foreground service when coordinator reports AlreadyRunning`() = runTest {
        val a1 = archive(1)
        var serviceStarts = 0
        val coordinator = FakeCoordinator(startResult = CoordinatorStartResult.AlreadyRunning)
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator, onStartService = { serviceStarts++ })
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        assertEquals(0, serviceStarts)
    }

    @Test
    fun `SessionDirFailed from coordinator surfaces DiscoveryFailed with a friendly message`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator(
            startResult = CoordinatorStartResult.SessionDirFailed(FailureReason.USB_NOT_WRITABLE, "read-only")
        )
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        val failed = vm.uiState.value as UsbCopyUiState.DiscoveryFailed
        assertEquals("USB stick is read-only.", failed.message)
    }

    @Test
    fun `requestCancel delegates to the coordinator`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        vm.requestCancel()
        assertEquals(1, coordinator.cancelCalls)
    }

    @Test
    fun `isTransferActive reflects the coordinator's Running state`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        assertFalse(vm.isTransferActive())

        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        assertTrue(vm.isTransferActive())

        coordinator.setState(completedState(coordinator.state.value.let {
            (it as UsbTransferCoordinatorState.Running).tiles
        }))
        assertFalse(vm.isTransferActive())
    }

    // ── loadArchives: reattach-before-discovery ───────────────────────────────────

    @Test
    fun `loadArchives attempts attachOrRecover before falling back to discovery`() = runTest {
        val coordinator = FakeCoordinator()
        var discoverCalls = 0
        val repo = object : BaseRepo(listOf(archive(1)), usbMount, sessionDir) {
            override suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive> {
                discoverCalls++
                return super.discoverArchives(host, port)
            }
        }
        val vm = makeViewModel(repo, coordinator)
        vm.loadArchives()
        advanceUntilIdle()

        assertEquals(1, coordinator.attachOrRecoverCalls)
        assertEquals("discovery must run when there is nothing to reattach to", 1, discoverCalls)
    }

    @Test
    fun `loadArchives skips discovery when attachOrRecover finds a persisted batch`() = runTest {
        val coordinator = FakeCoordinator().apply { attachOrRecoverResult = true }
        var discoverCalls = 0
        val repo = object : BaseRepo(listOf(archive(1)), usbMount, sessionDir) {
            override suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive> {
                discoverCalls++
                return super.discoverArchives(host, port)
            }
        }
        val vm = makeViewModel(repo, coordinator)
        vm.loadArchives()
        advanceUntilIdle()

        assertEquals("discovery must be skipped once a persisted batch is reattached", 0, discoverCalls)
    }

    @Test
    fun `loadArchives does not restart discovery when a batch is already Running`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        var discoverCalls = 0
        val repo = object : BaseRepo(listOf(a1), usbMount, sessionDir) {
            override suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive> {
                discoverCalls++
                return super.discoverArchives(host, port)
            }
        }
        val vm = makeViewModel(repo, coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        assertEquals(1, discoverCalls)

        // Simulates the screen being recomposed (e.g. LaunchedEffect(Unit) re-firing) while a
        // batch is already Running — must not kick off a redundant discovery pass.
        vm.loadArchives()
        advanceUntilIdle()
        assertEquals(1, discoverCalls)
    }

    // ── Navigation guard: leave dialog ───────────────────────────────────────────

    @Test
    fun `requestLeave during active transfer shows leave dialog`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        assertTrue("Transfer must be active", vm.isTransferActive())
        assertFalse("Dialog must not show before requestLeave", vm.showLeaveDialog.value)

        vm.requestLeave()

        assertTrue("Dialog must show after requestLeave", vm.showLeaveDialog.value)
    }

    @Test
    fun `requestLeave when no transfer is active does not show dialog`() = runTest {
        val vm = makeViewModel(basicRepo(listOf(archive(1))))
        vm.loadArchives()
        advanceUntilIdle()

        assertFalse(vm.isTransferActive())
        vm.requestLeave()

        assertFalse("Dialog must not show when no transfer active", vm.showLeaveDialog.value)
    }

    @Test
    fun `dismissLeaveDialog hides the dialog without cancelling`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        vm.requestLeave()
        assertTrue(vm.showLeaveDialog.value)

        vm.dismissLeaveDialog()
        assertFalse("Dialog must be hidden after dismiss", vm.showLeaveDialog.value)
        assertEquals("dismissing the dialog must not cancel", 0, coordinator.cancelCalls)
        assertTrue("Transfer must still be active", vm.isTransferActive())
    }

    @Test
    fun `confirmLeaveAndCancel requests cancel and navigates once the coordinator leaves Running`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()

        var navigated = false
        val collectJob = launch { vm.navigateHomeEvent.collect { navigated = true } }
        testDispatcher.scheduler.runCurrent()

        // Simulates the coordinator/repository completing the remote cancellation asynchronously,
        // after some delay — the ViewModel must wait for it rather than navigating immediately.
        launch {
            delay(500)
            coordinator.setState(completedState((coordinator.state.value as UsbTransferCoordinatorState.Running).tiles))
        }

        vm.requestLeave()
        vm.confirmLeaveAndCancel()
        testDispatcher.scheduler.runCurrent()

        assertEquals(1, coordinator.cancelCalls)
        assertFalse("Dialog must be hidden immediately", vm.showLeaveDialog.value)
        assertFalse("Must not navigate before the coordinator leaves Running", navigated)

        advanceUntilIdle()

        assertTrue("Navigation must fire once the coordinator is no longer Running", navigated)
        collectJob.cancel()
    }

    @Test
    fun `isTransferActive returns false when no transfer running`() = runTest {
        val vm = makeViewModel(basicRepo(listOf(archive(1))))
        vm.loadArchives()
        advanceUntilIdle()

        assertFalse(vm.isTransferActive())
    }

    // ── Dismissal ─────────────────────────────────────────────────────────────────

    @Test
    fun `dismissCompleted fires navigateHomeEvent and clears Completed state`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        coordinator.setState(completedState((coordinator.state.value as UsbTransferCoordinatorState.Running).tiles))
        testDispatcher.scheduler.runCurrent()

        assertTrue(vm.uiState.value is UsbCopyUiState.Completed)

        var eventFired = false
        val collectJob = launch { vm.navigateHomeEvent.collect { eventFired = true } }
        testDispatcher.scheduler.runCurrent()

        vm.dismissCompleted()
        advanceUntilIdle()

        assertTrue("navigateHomeEvent must fire on dismiss", eventFired)
        assertEquals("coordinator state must be reset to Idle after dismiss", UsbTransferCoordinatorState.Idle, coordinator.state.value)
        collectJob.cancel()
    }

    @Test
    fun `dismissCompleted is idempotent — navigateHomeEvent fires only once`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        coordinator.setState(completedState((coordinator.state.value as UsbTransferCoordinatorState.Running).tiles))

        var eventCount = 0
        val collectJob = launch { vm.navigateHomeEvent.collect { eventCount++ } }
        testDispatcher.scheduler.runCurrent()

        vm.dismissCompleted()
        vm.dismissCompleted()
        vm.dismissCompleted()
        advanceUntilIdle()

        assertEquals("navigateHomeEvent must fire exactly once regardless of repeat calls", 1, eventCount)
        collectJob.cancel()
    }

    @Test
    fun `startAutoDismissTimer fires navigateHomeEvent after 15 seconds`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        coordinator.setState(completedState((coordinator.state.value as UsbTransferCoordinatorState.Running).tiles))

        var eventFired = false
        val collectJob = launch { vm.navigateHomeEvent.collect { eventFired = true } }
        testDispatcher.scheduler.runCurrent()

        vm.startAutoDismissTimer()
        testDispatcher.scheduler.advanceTimeBy(15_001L)
        advanceUntilIdle()

        assertTrue("navigateHomeEvent must fire after 15 seconds", eventFired)
        collectJob.cancel()
    }

    @Test
    fun `timer does not fire navigateHomeEvent after manual dismissal`() = runTest {
        val a1 = archive(1)
        val coordinator = FakeCoordinator()
        val vm = makeViewModel(basicRepo(listOf(a1)), coordinator)
        vm.loadArchives()
        advanceUntilIdle()
        vm.toggleSelection(a1)
        vm.startTransfer()
        advanceUntilIdle()
        coordinator.setState(completedState((coordinator.state.value as UsbTransferCoordinatorState.Running).tiles))

        var eventCount = 0
        val collectJob = launch { vm.navigateHomeEvent.collect { eventCount++ } }
        testDispatcher.scheduler.runCurrent()

        vm.startAutoDismissTimer()
        vm.dismissCompleted() // manual dismiss cancels the timer
        advanceUntilIdle()
        testDispatcher.scheduler.advanceTimeBy(20_000L)
        advanceUntilIdle()

        assertEquals("timer must not fire after manual dismiss", 1, eventCount)
        collectJob.cancel()
    }

    // ── CancellationException during discovery is not swallowed as DiscoveryFailed ─

    @Test
    fun `CancellationException during discovery is not swallowed as DiscoveryFailed`() = runTest {
        val repo = object : BaseRepo(emptyList(), usbMount, sessionDir) {
            override suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive> {
                throw kotlinx.coroutines.CancellationException("scope cancelled")
            }
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        assertFalse(
            "UI state must not be DiscoveryFailed when CancellationException propagates",
            vm.uiState.value is UsbCopyUiState.DiscoveryFailed
        )
    }

    // ── Prepare USB for export (remount) — verifies the same mount comes back rw ──

    private val readOnlyMount = usbMount.copy(mountOptions = setOf("ro", "relatime"))

    private fun readOnlyRepo(
        remountResult: (UsbMount) -> UsbRemountResult
    ): UsbTransferRepository = object : BaseRepo(emptyList(), usbMount, sessionDir) {
        override suspend fun detectUsbStatus(host: String, port: Int) = UsbDetectionResult.ReadOnly(readOnlyMount)
        override suspend fun remountReadWrite(host: String, port: Int, usbMount: UsbMount) =
            remountResult(usbMount)
    }

    @Test
    fun `discovery on a ro mount produces ReadOnly state with usbMount null (Begin disabled)`() = runTest {
        val vm = makeViewModel(readOnlyRepo { UsbRemountResult.Failed("unused") })
        vm.loadArchives()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertTrue(ready.usbDetectionResult is UsbDetectionResult.ReadOnly)
        assertNull(ready.usbMount)
    }

    @Test
    fun `discovery retries NotFound until the USB mount becomes available`() = runTest {
        var calls = 0
        val repo = object : BaseRepo(emptyList(), usbMount, sessionDir) {
            override suspend fun detectUsbStatus(host: String, port: Int): UsbDetectionResult {
                calls++
                return if (calls < 3) UsbDetectionResult.NotFound else UsbDetectionResult.Writable(usbMount)
            }
        }
        val vm = makeViewModel(repo)

        vm.loadArchives()
        testDispatcher.scheduler.runCurrent()
        assertEquals(1, calls)

        advanceTimeBy(2_000)
        advanceUntilIdle()

        assertEquals(3, calls)
        assertTrue((vm.uiState.value as UsbCopyUiState.Ready).usbDetectionResult is UsbDetectionResult.Writable)
    }

    @Test
    fun `multiple read-only mounts do not offer remount for an arbitrary mount`() = runTest {
        val otherReadOnlyMount = readOnlyMount.copy(mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/OTHER")
        val repo = object : BaseRepo(emptyList(), usbMount, sessionDir) {
            override suspend fun detectUsbStatus(host: String, port: Int) =
                UsbDetectionResult.MultipleReadOnlyMounts(listOf(readOnlyMount, otherReadOnlyMount))
        }
        val vm = makeViewModel(repo)
        vm.loadArchives()
        advanceUntilIdle()

        vm.requestRemount()
        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertEquals(RemountState.Idle, ready.remountState)
    }

    @Test
    fun `requestRemount shows the confirmation dialog only when ReadOnly`() = runTest {
        val vm = makeViewModel(readOnlyRepo { UsbRemountResult.Failed("unused") })
        vm.loadArchives()
        advanceUntilIdle()

        vm.requestRemount()
        testDispatcher.scheduler.runCurrent()
        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertEquals(RemountState.ConfirmPending, ready.remountState)
    }

    @Test
    fun `requestRemount is a no-op when the mount is already writable`() = runTest {
        val vm = makeViewModel(basicRepo(emptyList()))
        vm.loadArchives()
        advanceUntilIdle()

        vm.requestRemount()
        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertEquals(RemountState.Idle, ready.remountState)
    }

    @Test
    fun `confirmRemount success verifies the same mount is now rw and updates state to Writable`() = runTest {
        val verifiedRw = readOnlyMount.copy(mountOptions = setOf("rw", "relatime"))
        val vm = makeViewModel(readOnlyRepo { UsbRemountResult.Success(verifiedRw) })
        vm.loadArchives()
        advanceUntilIdle()

        vm.requestRemount()
        vm.confirmRemount()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertEquals(RemountState.Success, ready.remountState)
        assertTrue(ready.usbDetectionResult is UsbDetectionResult.Writable)
        assertEquals(verifiedRw, ready.usbMount)
        assertEquals(readOnlyMount.devicePath, ready.usbMount?.devicePath)
        assertEquals(readOnlyMount.mountPath, ready.usbMount?.mountPath)
    }

    @Test
    fun `confirmRemount failure keeps Begin disabled and surfaces a message`() = runTest {
        val vm = makeViewModel(readOnlyRepo { UsbRemountResult.Failed("mount is still read-only after remount") })
        vm.loadArchives()
        advanceUntilIdle()

        vm.requestRemount()
        vm.confirmRemount()
        advanceUntilIdle()

        val ready = vm.uiState.value as UsbCopyUiState.Ready
        assertTrue(ready.remountState is RemountState.Failed)
        assertEquals("mount is still read-only after remount", (ready.remountState as RemountState.Failed).message)
        assertNull(ready.usbMount)
    }

    @Test
    fun `confirmRemount while already remounting is a no-op`() = runTest {
        var remountCalls = 0
        val vm = makeViewModel(readOnlyRepo {
            remountCalls++
            UsbRemountResult.Success(readOnlyMount.copy(mountOptions = setOf("rw")))
        })
        vm.loadArchives()
        advanceUntilIdle()
        vm.requestRemount()
        vm.confirmRemount()
        vm.confirmRemount() // duplicate call before the first completes
        advanceUntilIdle()

        assertEquals(1, remountCalls)
    }

    // ── Progress calculation (pure) ───────────────────────────────────────────────

    @Test
    fun `2 of 5 processed is 40 percent`() {
        val p = TransferProgressState(
            totalSelected = 5, processed = 2,
            succeeded = 1, alreadyPresent = 0, failed = 1, cancelled = 0
        )
        assertEquals(40, p.percentComplete)
    }

    @Test
    fun `2 of 5 fraction is exactly 0_4`() {
        val p = TransferProgressState(
            totalSelected = 5, processed = 2,
            succeeded = 1, alreadyPresent = 0, failed = 1, cancelled = 0
        )
        assertEquals(0.4f, p.fraction, 0.0001f)
    }

    // ── Operation label (pure) ────────────────────────────────────────────────────

    @Test
    fun `operation label for directory copy is correct`() {
        val label = operationLabel(TransferPhase.CopyingExisting(archive(3)))
        assertEquals("Copying T3 normal", label)
    }

    @Test
    fun `operation label for offline trace directory copy is correct`() {
        val label = operationLabel(TransferPhase.CopyingExisting(archive(3).offlineVariant()))
        assertEquals("Copying T3 offline trace", label)
    }

    @Test
    fun `operation label for archive extraction is correct`() {
        val label = operationLabel(TransferPhase.Extracting(archive(3)))
        assertEquals("Extracting T3 normal", label)
    }

    @Test
    fun `operation label for offline trace extraction is correct`() {
        val label = operationLabel(TransferPhase.Extracting(archive(3).offlineVariant()))
        assertEquals("Extracting T3 offline trace", label)
    }

    // ── headline() — pure function tests ─────────────────────────────────────────

    private fun progress(
        total: Int = 1, processed: Int = 0,
        succeeded: Int = 0, alreadyPresent: Int = 0,
        failed: Int = 0, cancelled: Int = 0
    ) = TransferProgressState(
        totalSelected = total, processed = processed,
        succeeded = succeeded, alreadyPresent = alreadyPresent,
        failed = failed, cancelled = cancelled
    )

    @Test
    fun `headline is Copy completed when all items succeed`() {
        assertEquals("Copy completed", headline(progress(total = 3, processed = 3, succeeded = 3)))
    }

    @Test
    fun `headline is Copy completed when mix of succeeded and already present`() {
        assertEquals("Copy completed", headline(progress(total = 2, processed = 2, succeeded = 1, alreadyPresent = 1)))
    }

    @Test
    fun `headline is Copy completed with errors when some items failed alongside successes`() {
        assertEquals("Copy completed with errors", headline(progress(total = 3, processed = 3, succeeded = 2, failed = 1)))
    }

    @Test
    fun `headline is Copy failed when all items failed with no successes`() {
        assertEquals("Copy failed", headline(progress(total = 2, processed = 2, failed = 2)))
    }

    @Test
    fun `headline is Copy stopped when any item was cancelled`() {
        assertEquals("Copy stopped", headline(progress(total = 3, processed = 1, succeeded = 1, cancelled = 1)))
    }

    @Test
    fun `already present does not count as error for headline`() {
        val p = progress(total = 2, processed = 2, alreadyPresent = 2)
        assertEquals("already-present-only must not trigger error headline",
            "Copy completed", headline(p))
    }

    // ── variantResultText — pure function tests ──────────────────────────────────

    @Test
    fun `variantResultText for null is Not started`() {
        assertEquals("Not started", variantResultText(null))
    }

    @Test
    fun `variantResultText for Success is Copied`() {
        val a = archive(1)
        assertEquals("Copied", variantResultText(
            TriggerTransferResult.Success(a, "/usb/dest", SourceType.ARCHIVE_EXTRACTED_TO_USB)
        ))
    }

    @Test
    fun `variantResultText for AlreadyPresent is Already on USB`() {
        assertEquals("Already on USB", variantResultText(
            TriggerTransferResult.AlreadyPresent(archive(1), "/usb/existing")
        ))
    }

    @Test
    fun `variantResultText for Cancelled is Cancelled`() {
        assertEquals("Cancelled", variantResultText(
            TriggerTransferResult.Cancelled(archive(1), partialOutputRemoved = true)
        ))
    }

    @Test
    fun `variantResultText for Failed with message shows the message`() {
        val msg = "LZ4_DECOMPRESS_FAILED: FAILED:LZ4_DECOMPRESS_FAILED"
        val result = TriggerTransferResult.Failed(archive(1), FailureReason.LZ4_DECOMPRESS_FAILED, msg)
        assertEquals(msg, variantResultText(result))
    }

    @Test
    fun `variantResultText for Failed without message falls back to reason name`() {
        val result = TriggerTransferResult.Failed(archive(1), FailureReason.SOURCE_ARCHIVE_MISSING)
        assertEquals("Failed: SOURCE_ARCHIVE_MISSING", variantResultText(result))
    }
}
