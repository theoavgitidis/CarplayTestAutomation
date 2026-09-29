package com.example.adb_connection.data.usb

import com.example.adb_connection.domain.model.ArchiveVariant
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.JobInspection
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbTransferRepositoryImplTest {

    private val repo = UsbTransferRepositoryImpl()

    private val archive = TriggerArchive(
        triggerNumber = 1,
        stem = "trigger_1_HU_20260701_113733_COREDUMP",
        archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_1_HU_20260701_113733_COREDUMP.tar.lz4",
        extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_1_HU_20260701_113733_COREDUMP"
    )
    private val sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710"
    private val jobId = "a1b2c3d4e5f60718"

    @Test
    fun `parseFailureReason maps FIFO_CREATE_FAILED`() {
        assertEquals(FailureReason.FIFO_CREATE_FAILED, repo.parseFailureReason("FIFO_CREATE_FAILED"))
    }

    @Test
    fun `parseFailureReason maps SOURCE_ARCHIVE_MISSING`() {
        assertEquals(FailureReason.SOURCE_ARCHIVE_MISSING, repo.parseFailureReason("SOURCE_ARCHIVE_MISSING"))
    }

    @Test
    fun `parseFailureReason maps PARTIAL_DIR_CREATE_FAILED`() {
        assertEquals(FailureReason.PARTIAL_DIR_CREATE_FAILED, repo.parseFailureReason("PARTIAL_DIR_CREATE_FAILED"))
    }

    @Test
    fun `parseFailureReason maps CP_FAILED`() {
        assertEquals(FailureReason.CP_FAILED, repo.parseFailureReason("CP_FAILED"))
    }

    @Test
    fun `parseFailureReason maps MV_RENAME_FAILED`() {
        assertEquals(FailureReason.MV_RENAME_FAILED, repo.parseFailureReason("MV_RENAME_FAILED"))
    }

    @Test
    fun `parseFailureReason maps LZ4_UNAVAILABLE`() {
        assertEquals(FailureReason.LZ4_UNAVAILABLE, repo.parseFailureReason("LZ4_UNAVAILABLE"))
    }

    @Test
    fun `parseFailureReason maps LZ4_DECOMPRESS_FAILED`() {
        assertEquals(FailureReason.LZ4_DECOMPRESS_FAILED, repo.parseFailureReason("LZ4_DECOMPRESS_FAILED"))
    }

    @Test
    fun `parseFailureReason maps TAR_EXTRACT_FAILED`() {
        assertEquals(FailureReason.TAR_EXTRACT_FAILED, repo.parseFailureReason("TAR_EXTRACT_FAILED"))
    }

    @Test
    fun `parseFailureReason maps unknown token to UNKNOWN`() {
        assertEquals(FailureReason.UNKNOWN, repo.parseFailureReason("SOME_UNKNOWN_TOKEN"))
    }

    @Test
    fun `parseFailureReason does not map FIFO_CREATE_FAILED to UNKNOWN`() {
        val result = repo.parseFailureReason("FIFO_CREATE_FAILED")
        assertEquals("FAILED:FIFO_CREATE_FAILED must not fall through to UNKNOWN", FailureReason.FIFO_CREATE_FAILED, result)
    }

    @Test
    fun `parseFailureReason maps JOB_DIR_CREATE_FAILED`() {
        assertEquals(FailureReason.JOB_DIR_CREATE_FAILED, repo.parseFailureReason("JOB_DIR_CREATE_FAILED"))
    }

    // ── parseFailureReason — mount revalidation tokens ─────────────────────────

    @Test
    fun `parseFailureReason maps USB_NOT_FOUND`() {
        assertEquals(FailureReason.USB_NOT_FOUND, repo.parseFailureReason("USB_NOT_FOUND"))
    }

    @Test
    fun `parseFailureReason maps USB_DISCONNECTED`() {
        assertEquals(FailureReason.USB_DISCONNECTED, repo.parseFailureReason("USB_DISCONNECTED"))
    }

    @Test
    fun `parseFailureReason maps USB_NOT_WRITABLE`() {
        assertEquals(FailureReason.USB_NOT_WRITABLE, repo.parseFailureReason("USB_NOT_WRITABLE"))
    }

    @Test
    fun `parseFailureReason maps USB_MOUNT_CHANGED`() {
        assertEquals(FailureReason.USB_MOUNT_CHANGED, repo.parseFailureReason("USB_MOUNT_CHANGED"))
    }

    // ── mapStatus ───────────────────────────────────────────────────────────────

    @Test
    fun `mapStatus SUCCESS returns Success with correct path and source type`() {
        val destPath = "$sessionDir/${archive.stem}"
        val result = repo.mapStatus("SUCCESS:$destPath", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Success)
        result as TriggerTransferResult.Success
        assertEquals(destPath, result.destinationPath)
        assertEquals(SourceType.ARCHIVE_EXTRACTED_TO_USB, result.sourceType)
    }

    @Test
    fun `mapStatus SUCCESS strategy A returns EXISTING_EXTRACTED_DIRECTORY source type`() {
        val destPath = "$sessionDir/${archive.stem}"
        val result = repo.mapStatus("SUCCESS:$destPath", archive, useStrategyA = true)
        result as TriggerTransferResult.Success
        assertEquals(SourceType.EXISTING_EXTRACTED_DIRECTORY, result.sourceType)
    }

    @Test
    fun `mapStatus SUCCESS does not return AlreadyPresent — no post-success duplicate check`() {
        // The remote worker already checked for duplicates; SUCCESS always maps to Success.
        val destPath = "$sessionDir/${archive.stem}"
        val result = repo.mapStatus("SUCCESS:$destPath", archive, useStrategyA = false)
        assertTrue("SUCCESS must map to Success, not AlreadyPresent", result is TriggerTransferResult.Success)
    }

    @Test
    fun `mapStatus ALREADY_PRESENT returns AlreadyPresent with correct path`() {
        val existingPath = "$sessionDir/${archive.stem}"
        val result = repo.mapStatus("ALREADY_PRESENT:$existingPath", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.AlreadyPresent)
        result as TriggerTransferResult.AlreadyPresent
        assertEquals(existingPath, result.existingPath)
    }

    @Test
    fun `mapStatus CANCELLED returns Cancelled with partialOutputRemoved true`() {
        val result = repo.mapStatus("CANCELLED", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Cancelled)
        result as TriggerTransferResult.Cancelled
        assertTrue(result.partialOutputRemoved)
    }

    @Test
    fun `mapStatus FAILED maps reason and formats message as reason colon status`() {
        val result = repo.mapStatus("FAILED:LZ4_DECOMPRESS_FAILED", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Failed)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.LZ4_DECOMPRESS_FAILED, result.reason)
        assertEquals("LZ4_DECOMPRESS_FAILED: FAILED:LZ4_DECOMPRESS_FAILED", result.message)
    }

    @Test
    fun `mapStatus FAILED USB_DISCONNECTED maps reason from worker mount revalidation`() {
        val result = repo.mapStatus("FAILED:USB_DISCONNECTED", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Failed)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.USB_DISCONNECTED, result.reason)
    }

    @Test
    fun `mapStatus FAILED USB_MOUNT_CHANGED maps reason from worker mount revalidation`() {
        val result = repo.mapStatus("FAILED:USB_MOUNT_CHANGED", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Failed)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.USB_MOUNT_CHANGED, result.reason)
    }

    @Test
    fun `mapStatus FAILED unknown token maps to UNKNOWN with message`() {
        val result = repo.mapStatus("FAILED:SOME_NEW_TOKEN", archive, useStrategyA = false)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.UNKNOWN, result.reason)
        assertEquals("UNKNOWN: FAILED:SOME_NEW_TOKEN", result.message)
    }

    @Test
    fun `mapStatus unexpected status returns UNEXPECTED_STATUS with raw status as message`() {
        val raw = "SOME_GARBAGE"
        val result = repo.mapStatus(raw, archive, useStrategyA = false)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.UNEXPECTED_STATUS, result.reason)
        assertEquals(raw, result.message)
    }

    // ── parseFailureReason — cp/tar/lz4 stderr classification tokens ───────────

    @Test
    fun `parseFailureReason maps USB_FULL`() {
        assertEquals(FailureReason.USB_FULL, repo.parseFailureReason("USB_FULL"))
    }

    @Test
    fun `parseFailureReason maps USB_IO_ERROR`() {
        assertEquals(FailureReason.USB_IO_ERROR, repo.parseFailureReason("USB_IO_ERROR"))
    }

    @Test
    fun `mapStatus FAILED USB_FULL maps reason from captured cp tar lz4 stderr`() {
        val result = repo.mapStatus("FAILED:USB_FULL", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Failed)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.USB_FULL, result.reason)
    }

    @Test
    fun `mapStatus FAILED USB_IO_ERROR maps reason from captured cp tar lz4 stderr`() {
        val result = repo.mapStatus("FAILED:USB_IO_ERROR", archive, useStrategyA = false)
        assertTrue(result is TriggerTransferResult.Failed)
        result as TriggerTransferResult.Failed
        assertEquals(FailureReason.USB_IO_ERROR, result.reason)
    }

    // ── appendWorkerLogDiagnostic — pure, best-effort message enrichment ───────

    @Test
    fun `appendWorkerLogDiagnostic returns failure unchanged when workerLogTail is null`() {
        val failed = TriggerTransferResult.Failed(archive, FailureReason.CP_FAILED, message = null)
        val result = repo.appendWorkerLogDiagnostic(failed, workerLogTail = null)
        assertEquals(failed, result)
    }

    @Test
    fun `appendWorkerLogDiagnostic returns failure unchanged when workerLogTail is blank`() {
        val failed = TriggerTransferResult.Failed(archive, FailureReason.CP_FAILED, message = null)
        val result = repo.appendWorkerLogDiagnostic(failed, workerLogTail = "   \n  \n")
        assertEquals(failed, result)
    }

    @Test
    fun `appendWorkerLogDiagnostic appends log tail when failure has no prior message`() {
        val failed = TriggerTransferResult.Failed(archive, FailureReason.CP_FAILED, message = null)
        val result = repo.appendWorkerLogDiagnostic(failed, workerLogTail = "cp: HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/foo: No space left on device")
        assertTrue(result.message?.contains("No space left on device") == true)
    }

    @Test
    fun `appendWorkerLogDiagnostic preserves an existing message and appends the diagnostic`() {
        val failed = TriggerTransferResult.Failed(archive, FailureReason.CP_FAILED, message = "UNKNOWN: FAILED:CP_FAILED")
        val result = repo.appendWorkerLogDiagnostic(failed, workerLogTail = "cp: HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/foo: No space left on device")
        assertTrue("Must keep the original message", result.message?.contains("UNKNOWN: FAILED:CP_FAILED") == true)
        assertTrue("Must append the diagnostic", result.message?.contains("No space left on device") == true)
    }

    // ── interpretLaunchRecovery — post-timeout job-state probe ────────────────

    @Test
    fun `interpretLaunchRecovery Alive returns RecoveredRunning with sentinel PID`() {
        val result = repo.interpretLaunchRecovery(JobInspection.Alive, jobId, originalError = "Read timed out")
        assertTrue(result is UsbTransferRepositoryImpl.LaunchResult.RecoveredRunning)
        result as UsbTransferRepositoryImpl.LaunchResult.RecoveredRunning
        assertEquals(jobId, result.handle.jobId)
        assertEquals(-1, result.handle.remotePid)
        assertEquals("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/status", result.handle.statusPath)
    }

    @Test
    fun `interpretLaunchRecovery Terminal Success returns RecoveredTerminal with the success result`() {
        val destPath = "$sessionDir/${archive.stem}"
        val terminalResult = TriggerTransferResult.Success(archive, destPath, SourceType.ARCHIVE_EXTRACTED_TO_USB)
        val result = repo.interpretLaunchRecovery(JobInspection.Terminal(terminalResult), jobId, originalError = null)
        assertTrue(result is UsbTransferRepositoryImpl.LaunchResult.RecoveredTerminal)
        result as UsbTransferRepositoryImpl.LaunchResult.RecoveredTerminal
        assertEquals(terminalResult, result.result)
    }

    @Test
    fun `interpretLaunchRecovery Terminal Failed returns RecoveredTerminal with the failure`() {
        val failed = TriggerTransferResult.Failed(archive, FailureReason.CP_FAILED, "copy error")
        val result = repo.interpretLaunchRecovery(JobInspection.Terminal(failed), jobId, originalError = null)
        assertTrue(result is UsbTransferRepositoryImpl.LaunchResult.RecoveredTerminal)
        result as UsbTransferRepositoryImpl.LaunchResult.RecoveredTerminal
        assertEquals(failed, result.result)
    }

    @Test
    fun `interpretLaunchRecovery Lost returns Failed with REMOTE_SCRIPT_LAUNCH_FAILED and preserves original error`() {
        val original = "Read timed out"
        val result = repo.interpretLaunchRecovery(JobInspection.Lost, jobId, originalError = original)
        assertTrue(result is UsbTransferRepositoryImpl.LaunchResult.Failed)
        result as UsbTransferRepositoryImpl.LaunchResult.Failed
        assertEquals(FailureReason.REMOTE_SCRIPT_LAUNCH_FAILED, result.reason)
        assertTrue("Must mention original error in message", result.message?.contains(original) == true)
    }

    @Test
    fun `interpretLaunchRecovery CommunicationFailure returns Failed with ADB_COMMUNICATION_ERROR`() {
        val probe = JobInspection.CommunicationFailure("connection refused")
        val result = repo.interpretLaunchRecovery(probe, jobId, originalError = "Read timed out")
        assertTrue(result is UsbTransferRepositoryImpl.LaunchResult.Failed)
        result as UsbTransferRepositoryImpl.LaunchResult.Failed
        assertEquals(FailureReason.ADB_COMMUNICATION_ERROR, result.reason)
    }

    @Test
    fun `interpretLaunchRecovery CommunicationFailure uses original error when present`() {
        val original = "Read timed out"
        val result = repo.interpretLaunchRecovery(
            JobInspection.CommunicationFailure("connection refused"), jobId, originalError = original
        )
        result as UsbTransferRepositoryImpl.LaunchResult.Failed
        assertEquals(original, result.message)
    }

    @Test
    fun `appendWorkerLogDiagnostic ignores blank lines and keeps only the last few non-blank lines`() {
        val failed = TriggerTransferResult.Failed(archive, FailureReason.TAR_EXTRACT_FAILED, message = null)
        val logTail = """
            some earlier noise line 1
            some earlier noise line 2

            tar: last relevant error line
        """.trimIndent()
        val result = repo.appendWorkerLogDiagnostic(failed, workerLogTail = logTail)
        assertTrue(result.message?.contains("tar: last relevant error line") == true)
    }
}
