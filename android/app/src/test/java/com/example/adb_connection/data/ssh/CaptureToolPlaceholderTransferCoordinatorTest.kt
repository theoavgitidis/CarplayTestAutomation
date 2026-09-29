package com.example.adb_connection.data.ssh

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CaptureToolPlaceholderTransferCoordinatorTest {
    @Test
    fun `second start does not create a duplicate running transfer`() = runTest {
        val runner = BlockingRunner()
        val store = InMemoryStore()
        val coordinator = DefaultCaptureToolPlaceholderTransferCoordinator(runner, store, backgroundScope)

        assertEquals(CaptureToolPlaceholderTransferStartResult.Started, coordinator.start(listOf("/captures/a.capture_tool_placeholder")))
        assertEquals(CaptureToolPlaceholderTransferStartResult.AlreadyRunning, coordinator.start(listOf("/captures/a.capture_tool_placeholder")))
        runCurrent()
        assertEquals(1, runner.calls)
        assertEquals(CaptureToolPlaceholderTransferJobStatus.RUNNING, coordinator.state.value?.status)
        coordinator.requestCancel()
    }

    @Test
    fun `empty start does not prevent a later valid transfer`() = runTest {
        val runner = BlockingRunner()
        val coordinator = DefaultCaptureToolPlaceholderTransferCoordinator(runner, InMemoryStore(), backgroundScope)

        try {
            coordinator.start(emptyList())
        } catch (_: IllegalArgumentException) {
        }

        assertEquals(CaptureToolPlaceholderTransferStartResult.Started, coordinator.start(listOf("/captures/a.capture_tool_placeholder")))
        runCurrent()
        assertEquals(1, runner.calls)
        coordinator.requestCancel()
    }

    @Test
    fun `cancel turns running job into terminal cancelled snapshot`() = runTest {
        val store = InMemoryStore()
        val coordinator = DefaultCaptureToolPlaceholderTransferCoordinator(BlockingRunner(), store, backgroundScope)

        coordinator.start(listOf("/captures/a.capture_tool_placeholder"))
        runCurrent()
        coordinator.requestCancel()
        runCurrent()

        assertEquals(CaptureToolPlaceholderTransferJobStatus.CANCELLED, coordinator.state.value?.status)
        assertEquals(CaptureToolPlaceholderUsbTransferPhase.CANCELLED, store.snapshot?.phase)
    }

    @Test
    fun `persisted running job becomes interrupted without automatic SCP restart`() = runTest {
        val runner = BlockingRunner()
        val store = InMemoryStore(
            CaptureToolPlaceholderTransferSnapshot(
                jobId = "job-1",
                status = CaptureToolPlaceholderTransferJobStatus.RUNNING,
                phase = CaptureToolPlaceholderUsbTransferPhase.UPLOADING_TO_HEADUNIT,
                remotePaths = listOf("/captures/a.capture_tool_placeholder"),
                fileName = "a.capture_tool_placeholder",
                transferredBytes = 15,
                totalBytes = 20
            )
        )
        val coordinator = DefaultCaptureToolPlaceholderTransferCoordinator(runner, store, backgroundScope)

        coordinator.recoverLastSnapshot()

        assertEquals(0, runner.calls)
        assertEquals(CaptureToolPlaceholderTransferJobStatus.INTERRUPTED, coordinator.state.value?.status)
        assertTrue(coordinator.state.value?.error?.contains("restart it explicitly") == true)
    }

    @Test
    fun `high frequency progress is coalesced while completion is persisted`() = runTest {
        val store = CountingStore()
        val coordinator = DefaultCaptureToolPlaceholderTransferCoordinator(
            repository = object : CaptureToolPlaceholderTransferRunner {
                override suspend fun transfer(
                    remotePaths: List<String>,
                    onState: (CaptureToolPlaceholderUsbTransferState) -> Unit
                ): Result<List<CaptureToolPlaceholderUsbTransferResult>> {
                    repeat(100) { index ->
                        onState(
                            CaptureToolPlaceholderUsbTransferState(
                                CaptureToolPlaceholderUsbTransferPhase.DOWNLOADING_FROM_MAC,
                                "a.capture_tool_placeholder",
                                ScpTransferProgress(index.toLong() * 8_192, 8_192_000)
                            )
                        )
                    }
                    return Result.success(emptyList())
                }
            },
            snapshotStore = store,
            coordinatorScope = backgroundScope
        )

        coordinator.start(listOf("/captures/a.capture_tool_placeholder"))
        runCurrent()

        assertEquals(CaptureToolPlaceholderTransferJobStatus.COMPLETED, coordinator.state.value?.status)
        assertEquals(CaptureToolPlaceholderTransferJobStatus.COMPLETED, store.snapshot?.status)
        assertTrue("Expected coalesced saves, got ${store.saveCount}", store.saveCount < 10)
    }

    private class BlockingRunner : CaptureToolPlaceholderTransferRunner {
        var calls = 0

        override suspend fun transfer(
            remotePaths: List<String>,
            onState: (CaptureToolPlaceholderUsbTransferState) -> Unit
        ): Result<List<CaptureToolPlaceholderUsbTransferResult>> {
            calls++
            onState(CaptureToolPlaceholderUsbTransferState(CaptureToolPlaceholderUsbTransferPhase.DOWNLOADING_FROM_MAC, "a.capture_tool_placeholder", ScpTransferProgress(1, 2)))
            awaitCancellation()
        }
    }

    private class InMemoryStore(var snapshot: CaptureToolPlaceholderTransferSnapshot? = null) : CaptureToolPlaceholderTransferSnapshotStore {
        override suspend fun save(snapshot: CaptureToolPlaceholderTransferSnapshot) {
            this.snapshot = snapshot
        }

        override suspend fun load(): CaptureToolPlaceholderTransferSnapshot? = snapshot
    }

    private class CountingStore : CaptureToolPlaceholderTransferSnapshotStore {
        var snapshot: CaptureToolPlaceholderTransferSnapshot? = null
        var saveCount = 0

        override suspend fun save(snapshot: CaptureToolPlaceholderTransferSnapshot) {
            saveCount++
            this.snapshot = snapshot
        }

        override suspend fun load(): CaptureToolPlaceholderTransferSnapshot? = snapshot
    }
}
