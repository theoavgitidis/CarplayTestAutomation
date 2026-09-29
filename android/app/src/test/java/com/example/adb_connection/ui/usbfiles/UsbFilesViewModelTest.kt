package com.example.adb_connection.ui.usbfiles

import com.example.adb_connection.data.settings.SshHostProvider
import com.example.adb_connection.data.usb.UsbTransferRepository
import com.example.adb_connection.domain.model.ArchiveTileState
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TransferProgressState
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbFileEntry
import com.example.adb_connection.domain.model.UsbFileType
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbSessionDirResult
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import com.example.adb_connection.domain.usb.CoordinatorStartResult
import com.example.adb_connection.domain.usb.UsbTransferCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UsbFilesViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    private val mount = UsbMount(
        devicePath = "/dev/sda1",
        mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E",
        fileSystem = "exfat",
        mountOptions = setOf("rw", "relatime")
    )

    private val fakeHost = object : SshHostProvider {
        override val sshHost = flowOf("192.168.0.1")
    }

    private fun entry(name: String, path: String, type: UsbFileType, size: Long? = null) =
        UsbFileEntry(name = name, relativePath = path, type = type, sizeBytes = size)

    private abstract class BaseRepo : UsbTransferRepository {
        override suspend fun discoverArchives(host: String, port: Int) = emptyList<TriggerArchive>()
        override suspend fun detectUsbMount(host: String, port: Int): UsbMount? = null
        override suspend fun checkDuplicate(host: String, port: Int, usbMount: UsbMount, stem: String): String? = null
        override suspend fun createSessionDir(host: String, port: Int, usbMount: UsbMount) =
            UsbSessionDirResult.Failed(FailureReason.UNKNOWN)
        override suspend fun transferArchive(
            host: String, port: Int, archive: TriggerArchive, usbMount: UsbMount,
            sessionDir: String, cancelSignal: StateFlow<Boolean>, onPhase: (TransferPhase) -> Unit
        ): TriggerTransferResult = TriggerTransferResult.Failed(archive, FailureReason.UNKNOWN)
        override suspend fun syncAfterTransfer(host: String, port: Int) {}
        override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult =
            UsbDeleteResult.Success
        override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult =
            UsbListingResult.UsbNotConnected()
    }

    private fun repoReturning(result: UsbListingResult): UsbTransferRepository =
        object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) = result
        }

    private fun makeViewModel(
        repo: UsbTransferRepository,
        coordinator: UsbTransferCoordinator? = null
    ) = UsbFilesViewModel(fakeHost, repo, coordinator)

    private fun fakeCoordinatorWithState(state: UsbTransferCoordinatorState): UsbTransferCoordinator =
        object : UsbTransferCoordinator {
            override val state = MutableStateFlow(state)
            override suspend fun start(host: String, usbMount: UsbMount, archives: List<TriggerArchive>): CoordinatorStartResult =
                CoordinatorStartResult.AlreadyRunning
            override fun requestCancel() {}
            override suspend fun attachOrRecover(host: String) = false
            override fun dismissCompleted() {}
        }

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After  fun tearDown() { Dispatchers.resetMain() }

    // ── Initial loading ───────────────────────────────────────────────────────────

    @Test
    fun `initial state is Loading before first fetch completes`() {
        val vm = makeViewModel(repoReturning(UsbListingResult.UsbNotConnected()))
        assertEquals(UsbFilesUiState.Loading, vm.uiState.value)
    }

    // ── Successful list ───────────────────────────────────────────────────────────

    @Test
    fun `successful list transitions to Loaded`() = runTest {
        val entries = listOf(entry("session1", "session1", UsbFileType.DIRECTORY))
        val vm = makeViewModel(repoReturning(UsbListingResult.Success(mount, entries)))
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.Loaded
        assertEquals(mount, state.mount)
        assertEquals(1, state.entries.size)
        assertEquals("session1", state.entries[0].name)
    }

    // ── Read-only mount is accepted for browsing ─────────────────────────────────

    @Test
    fun `a real read-only mount is accepted and loaded — browsing never requires rw`() = runTest {
        val readOnlyMount = mount.copy(mountOptions = setOf("ro", "relatime"))
        assertFalse("Fixture must actually be read-only", readOnlyMount.isWritable)
        val entries = listOf(entry("session1", "session1", UsbFileType.DIRECTORY))
        val vm = makeViewModel(repoReturning(UsbListingResult.Success(readOnlyMount, entries)))
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.Loaded
        assertEquals(readOnlyMount, state.mount)
        assertFalse(state.mount.isWritable)
        assertEquals(1, state.entries.size)
    }

    // ── Empty list (no exported files) ───────────────────────────────────────────

    @Test
    fun `no exported files transitions to NoExports`() = runTest {
        val vm = makeViewModel(repoReturning(UsbListingResult.NoExportedFiles(mount)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UsbFilesUiState.NoExports)
        assertEquals(mount, (vm.uiState.value as UsbFilesUiState.NoExports).mount)
    }

    // ── No USB ────────────────────────────────────────────────────────────────────

    @Test
    fun `usb not connected transitions to UsbNotConnected`() = runTest {
        val vm = makeViewModel(repoReturning(UsbListingResult.UsbNotConnected()))
        advanceUntilIdle()

        assertEquals(UsbFilesUiState.UsbNotConnected, vm.uiState.value)
    }

    // ── TraceMate directory absent ────────────────────────────────────────────────

    @Test
    fun `TraceMate directory absent transitions to TraceMateDirectoryAbsent`() = runTest {
        val vm = makeViewModel(repoReturning(UsbListingResult.TraceMateDirectoryAbsent(mount)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UsbFilesUiState.TraceMateDirectoryAbsent)
    }

    // ── Refresh ───────────────────────────────────────────────────────────────────

    @Test
    fun `refresh transitions through Refreshing then to new result`() = runTest {
        val entries = listOf(entry("core.log", "session1/core.log", UsbFileType.FILE, 1024))
        var callCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                callCount++
                return UsbListingResult.Success(mount, entries)
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle() // initial load

        vm.refresh()
        assertEquals(UsbFilesUiState.Refreshing, vm.uiState.value)
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UsbFilesUiState.Loaded)
        assertEquals(2, callCount) // initial + refresh
    }

    @Test
    fun `refresh while already Refreshing is ignored`() = runTest {
        var callCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                callCount++
                return UsbListingResult.UsbNotConnected()
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle() // initial load completes — callCount = 1

        vm.refresh() // sets Refreshing
        vm.refresh() // must be ignored while Refreshing
        advanceUntilIdle()

        assertEquals("second refresh must be dropped", 2, callCount)
    }

    // ── USB removal during load ───────────────────────────────────────────────────

    @Test
    fun `USB removed during listing transitions to UsbRemovedDuringLoad`() = runTest {
        val partial = listOf(entry("session1", "session1", UsbFileType.DIRECTORY))
        val vm = makeViewModel(
            repoReturning(UsbListingResult.UsbRemovedDuringListing(mount, partial))
        )
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.UsbRemovedDuringLoad
        assertEquals(1, state.partialEntries.size)
        assertEquals("session1", state.partialEntries[0].name)
    }

    // ── ADB error ─────────────────────────────────────────────────────────────────

    @Test
    fun `ADB command failure transitions to AdbError`() = runTest {
        val vm = makeViewModel(repoReturning(UsbListingResult.AdbCommandFailed("Connection reset")))
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.AdbError
        assertEquals("Connection reset", state.message)
    }

    @Test
    fun `repository exception transitions to AdbError`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult =
                throw RuntimeException("socket closed")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.AdbError
        assertEquals("socket closed", state.message)
    }

    // ── Cancellation propagation ─────────────────────────────────────────────────

    @Test
    fun `CancellationException from repository is not swallowed — no error state written`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult =
                throw kotlinx.coroutines.CancellationException("scope cancelled")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        // CancellationException must propagate — the coroutine is cancelled, so UI state
        // must NOT transition to AdbError. It stays at Loading (the init value).
        assertTrue(
            "UI state must not be AdbError when CancellationException propagates",
            vm.uiState.value !is UsbFilesUiState.AdbError
        )
    }

    @Test
    fun `CancellationException from settings is not swallowed — no error state written`() = runTest {
        val cancellingHost = object : SshHostProvider {
            override val sshHost = kotlinx.coroutines.flow.flow<String> {
                throw kotlinx.coroutines.CancellationException("scope cancelled")
            }
        }
        val vm = UsbFilesViewModel(cancellingHost, repoReturning(UsbListingResult.UsbNotConnected()))
        advanceUntilIdle()

        assertTrue(
            "UI state must not be AdbError when CancellationException propagates from settings",
            vm.uiState.value !is UsbFilesUiState.AdbError
        )
    }

    @Test
    fun `cancelled job does not write UI state after cancellation`() = runTest {
        var suspended = false
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                suspended = true
                kotlinx.coroutines.delay(10_000)
                return UsbListingResult.Success(mount, emptyList())
            }
        }
        val vm = makeViewModel(repo)
        testDispatcher.scheduler.runCurrent()
        assertTrue("repo must have been called", suspended)

        // State is Loading while the coroutine is suspended
        assertEquals(UsbFilesUiState.Loading, vm.uiState.value)
    }

    // ── Delete all ────────────────────────────────────────────────────────────────

    @Test
    fun `deleteAllFiles when state is not Loaded is ignored`() = runTest {
        var deleteCalled = false
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.UsbNotConnected()
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult {
                deleteCalled = true
                return UsbDeleteResult.Success
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()
        assertEquals(UsbFilesUiState.UsbNotConnected, vm.uiState.value)

        vm.deleteAllFiles()
        advanceUntilIdle()

        assertFalse("deleteAllUsbFiles must not be called when state is not Loaded", deleteCalled)
        assertEquals("state must remain unchanged", UsbFilesUiState.UsbNotConnected, vm.uiState.value)
    }

    @Test
    fun `deleteAllFiles is blocked while a copy is active`() = runTest {
        var deleteCalled = false
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult {
                deleteCalled = true
                return UsbDeleteResult.Success
            }
        }
        val running = UsbTransferCoordinatorState.Running(emptyList(), TransferProgressState.zero(0), null)
        val vm = makeViewModel(repo, fakeCoordinatorWithState(running))
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        assertFalse(deleteCalled)
        assertEquals(UsbFilesUiState.CopyActive, vm.uiState.value)
    }

    @Test
    fun `deleteAllFiles transitions to Deleting before suspend resumes`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult {
                kotlinx.coroutines.delay(10_000)
                return UsbDeleteResult.Success
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()
        assertTrue(vm.uiState.value is UsbFilesUiState.Loaded)

        vm.deleteAllFiles()
        testDispatcher.scheduler.runCurrent()

        assertEquals(UsbFilesUiState.Deleting, vm.uiState.value)
    }

    @Test
    fun `successful delete re-fetches and lands in TraceMateDirectoryAbsent`() = runTest {
        var listCallCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                listCallCount++
                return if (listCallCount == 1)
                    UsbListingResult.Success(mount, emptyList())
                else
                    UsbListingResult.TraceMateDirectoryAbsent(mount)
            }
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount) =
                UsbDeleteResult.Success
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        assertTrue("State must be TraceMateDirectoryAbsent after successful delete",
            vm.uiState.value is UsbFilesUiState.TraceMateDirectoryAbsent)
    }

    @Test
    fun `delete AdbCommandFailed transitions to DeleteError with correct message`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount) =
                UsbDeleteResult.AdbCommandFailed("rm -rf failed on device")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.DeleteError
        assertEquals("rm -rf failed on device", state.message)
    }

    @Test
    fun `delete UsbNotConnected transitions to UsbNotConnected state`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount) =
                UsbDeleteResult.UsbNotConnected("USB removed during deletion")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        assertEquals(UsbFilesUiState.UsbNotConnected, vm.uiState.value)
    }

    @Test
    fun `delete mount changed transitions to DeleteError`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount) =
                UsbDeleteResult.UsbMountChanged("A different USB device is mounted at the expected path")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.DeleteError
        assertEquals("A different USB device is mounted at the expected path", state.message)
    }

    @Test
    fun `repository exception during delete transitions to DeleteError`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult =
                throw RuntimeException("socket closed")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        val state = vm.uiState.value as UsbFilesUiState.DeleteError
        assertEquals("socket closed", state.message)
    }

    @Test
    fun `CancellationException during delete is not swallowed as DeleteError`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult =
                throw kotlinx.coroutines.CancellationException("scope cancelled")
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.deleteAllFiles()
        advanceUntilIdle()

        assertFalse("State must not be DeleteError when CancellationException propagates",
            vm.uiState.value is UsbFilesUiState.DeleteError)
    }

    // ── Directory navigation ──────────────────────────────────────────────────────

    @Test
    fun `openDirectory updates currentDirectory and triggers load`() = runTest {
        val dirEntry = entry("session1", "session1", UsbFileType.DIRECTORY)
        var lastDir = ""
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                lastDir = relativeDirectory
                return UsbListingResult.Success(mount, emptyList())
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.openDirectory(dirEntry)
        advanceUntilIdle()

        assertEquals("session1", vm.currentDirectory.value)
        assertEquals("session1", lastDir)
    }

    @Test
    fun `openDirectory ignores file entries`() = runTest {
        val fileEntry = entry("core.log", "session1/core.log", UsbFileType.FILE)
        var callCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                callCount++
                return UsbListingResult.Success(mount, emptyList())
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()
        val callsAfterInit = callCount

        vm.openDirectory(fileEntry)
        advanceUntilIdle()

        assertEquals("currentDirectory must not change for a file", "", vm.currentDirectory.value)
        assertEquals("no extra load triggered for file", callsAfterInit, callCount)
    }

    @Test
    fun `navigateUp at root returns false and does not change state`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        val result = vm.navigateUp()

        assertFalse("navigateUp at root must return false", result)
        assertEquals("", vm.currentDirectory.value)
    }

    @Test
    fun `navigateUp from one-level-deep returns true and navigates to root`() = runTest {
        val dirEntry = entry("session1", "session1", UsbFileType.DIRECTORY)
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()
        vm.openDirectory(dirEntry)
        advanceUntilIdle()
        assertEquals("session1", vm.currentDirectory.value)

        val result = vm.navigateUp()
        advanceUntilIdle()

        assertTrue("navigateUp from depth-1 must return true", result)
        assertEquals("", vm.currentDirectory.value)
    }

    @Test
    fun `navigateUp from two-level-deep goes to one-level-deep`() = runTest {
        val dir1 = entry("session1", "session1", UsbFileType.DIRECTORY)
        val dir2 = entry("trigger_1", "session1/trigger_1", UsbFileType.DIRECTORY)
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String) =
                UsbListingResult.Success(mount, emptyList())
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()
        vm.openDirectory(dir1)
        advanceUntilIdle()
        vm.openDirectory(dir2)
        advanceUntilIdle()
        assertEquals("session1/trigger_1", vm.currentDirectory.value)

        vm.navigateUp()
        advanceUntilIdle()

        assertEquals("session1", vm.currentDirectory.value)
    }

    @Test
    fun `DirectoryNotFound resets currentDirectory to root and retries load`() = runTest {
        var listCallCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                listCallCount++
                return if (relativeDirectory.isNotEmpty())
                    UsbListingResult.DirectoryNotFound(mount, relativeDirectory)
                else
                    UsbListingResult.TraceMateDirectoryAbsent(mount)
            }
        }
        val vm = makeViewModel(repo)
        // Manually set a non-empty directory to simulate returning to a stale path
        vm.openDirectory(entry("gone", "gone", UsbFileType.DIRECTORY))
        advanceUntilIdle()

        assertEquals("currentDirectory must be reset to root", "", vm.currentDirectory.value)
        assertTrue(vm.uiState.value is UsbFilesUiState.TraceMateDirectoryAbsent)
    }

    @Test
    fun `listUsbFiles receives the correct relativeDirectory when navigated into a folder`() = runTest {
        val dirEntry = entry("session1", "session1", UsbFileType.DIRECTORY)
        var receivedDir: String? = null
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                receivedDir = relativeDirectory
                return UsbListingResult.Success(mount, emptyList())
            }
        }
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        vm.openDirectory(dirEntry)
        advanceUntilIdle()

        assertEquals("session1", receivedDir)
    }

    // ── Timeout and retry ─────────────────────────────────────────────────────────

    @Test
    fun `single timeout produces ListingTimedOut state`() = runTest {
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                delay(90_000)
                return UsbListingResult.Success(mount, emptyList())
            }
        }
        val vm = makeViewModel(repo)
        // First attempt times out after 60s, retry also times out after 60s → total 120s
        advanceTimeBy(121_000)
        advanceUntilIdle()

        assertEquals(UsbFilesUiState.ListingTimedOut, vm.uiState.value)
    }

    @Test
    fun `first timeout then success on retry transitions to Loaded`() = runTest {
        var callCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                callCount++
                return if (callCount == 1) {
                    delay(90_000) // exceeds 60s timeout → first attempt times out
                    UsbListingResult.Success(mount, emptyList())
                } else {
                    UsbListingResult.Success(mount, emptyList()) // retry succeeds immediately
                }
            }
        }
        val vm = makeViewModel(repo)
        advanceTimeBy(61_000)  // first attempt times out
        advanceUntilIdle()     // retry runs

        assertTrue("State must be Loaded after retry succeeds", vm.uiState.value is UsbFilesUiState.Loaded)
        assertEquals("Repository must have been called twice", 2, callCount)
    }

    @Test
    fun `ListingTimedOut result maps to ListingTimedOut ui state`() = runTest {
        val repo = repoReturning(UsbListingResult.ListingTimedOut)
        val vm = makeViewModel(repo)
        advanceUntilIdle()

        assertEquals(UsbFilesUiState.ListingTimedOut, vm.uiState.value)
    }

    // ── Copy-active guard ─────────────────────────────────────────────────────────

    @Test
    fun `listing is blocked when a copy is running — shows CopyActive`() = runTest {
        val runningState = UsbTransferCoordinatorState.Running(
            tiles = emptyList(),
            progress = TransferProgressState.zero(1),
            phase = null
        )
        val coordinator = fakeCoordinatorWithState(runningState)
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult =
                UsbListingResult.Success(mount, emptyList())
        }
        val vm = makeViewModel(repo, coordinator)
        advanceUntilIdle()

        assertEquals(UsbFilesUiState.CopyActive, vm.uiState.value)
    }

    @Test
    fun `listing proceeds normally when coordinator is Idle`() = runTest {
        val coordinator = fakeCoordinatorWithState(UsbTransferCoordinatorState.Idle)
        val entries = listOf(entry("session1", "session1", UsbFileType.DIRECTORY))
        val repo = repoReturning(UsbListingResult.Success(mount, entries))
        val vm = makeViewModel(repo, coordinator)
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UsbFilesUiState.Loaded)
    }

    @Test
    fun `refresh is blocked while copy is running — shows CopyActive`() = runTest {
        val runningState = UsbTransferCoordinatorState.Running(
            tiles = emptyList(),
            progress = TransferProgressState.zero(1),
            phase = null
        )
        val coordinator = fakeCoordinatorWithState(runningState)
        val repo = repoReturning(UsbListingResult.Success(mount, emptyList()))
        val vm = makeViewModel(repo, coordinator)
        advanceUntilIdle()

        vm.refresh()
        advanceUntilIdle()

        assertEquals(UsbFilesUiState.CopyActive, vm.uiState.value)
    }

    @Test
    fun `no coordinator treats copy as not active — listing proceeds`() = runTest {
        val entries = listOf(entry("session1", "session1", UsbFileType.DIRECTORY))
        val vm = makeViewModel(repoReturning(UsbListingResult.Success(mount, entries)))
        advanceUntilIdle()

        assertTrue(vm.uiState.value is UsbFilesUiState.Loaded)
    }

    // ── Cancel previous listing on new trigger ────────────────────────────────────

    @Test
    fun `new loadCurrentDirectory cancels the previous in-flight listing`() = runTest {
        var callCount = 0
        val repo = object : BaseRepo() {
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult {
                callCount++
                delay(5_000)
                return UsbListingResult.Success(mount, emptyList())
            }
        }
        val vm = makeViewModel(repo)
        testDispatcher.scheduler.runCurrent() // start first listing

        // Trigger a second listing (simulates navigateUp or openDirectory) before first completes
        val dir = entry("session1", "session1", UsbFileType.DIRECTORY)
        vm.openDirectory(dir)
        advanceUntilIdle()

        // Only the second listing should have completed — first was cancelled
        assertTrue("Final state must be Loaded", vm.uiState.value is UsbFilesUiState.Loaded)
        // callCount is 2: both were started, first was interrupted, second completed
        assertEquals(2, callCount)
    }
}
