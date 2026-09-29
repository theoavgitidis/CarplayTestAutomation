package com.example.adb_connection.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerTransferResultTest {

    private val archive = TriggerArchive(
        triggerNumber = 2,
        stem = "trigger_2_HU_20260701_113733_COREDUMP",
        archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP.tar.lz4",
        extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP"
    )

    // ── TriggerArchive ──────────────────────────────────────────────────────────

    @Test
    fun `displayLabel is T followed by triggerNumber`() {
        assertEquals("T2", archive.displayLabel)
        assertEquals("T10", archive.copy(triggerNumber = 10).displayLabel)
        assertEquals("T0", archive.copy(triggerNumber = 0).displayLabel)
    }

    @Test
    fun `extractedDirectoryPath is derived from stem`() {
        assertTrue(archive.extractedDirectoryPath.endsWith(archive.stem))
    }

    // ── Result type hierarchy ───────────────────────────────────────────────────

    @Test
    fun `Success is a TriggerTransferResult`() {
        val result: TriggerTransferResult = TriggerTransferResult.Success(archive, "/usb/dest", SourceType.ARCHIVE_EXTRACTED_TO_USB)
        assertTrue(result is TriggerTransferResult)
    }

    @Test
    fun `Failed is a TriggerTransferResult`() {
        val result: TriggerTransferResult = TriggerTransferResult.Failed(archive, FailureReason.LZ4_UNAVAILABLE)
        assertTrue(result is TriggerTransferResult)
    }

    @Test
    fun `Cancelled is a TriggerTransferResult`() {
        val result: TriggerTransferResult = TriggerTransferResult.Cancelled(archive, partialOutputRemoved = true)
        assertTrue(result is TriggerTransferResult)
    }

    @Test
    fun `AlreadyPresent is a TriggerTransferResult`() {
        val result: TriggerTransferResult = TriggerTransferResult.AlreadyPresent(archive, "/usb/existing")
        assertTrue(result is TriggerTransferResult)
    }

    @Test
    fun `AlreadyPresent is not Failed`() {
        val result: TriggerTransferResult = TriggerTransferResult.AlreadyPresent(archive, "/usb/existing")
        assertFalse(result is TriggerTransferResult.Failed)
    }

    @Test
    fun `AlreadyPresent is not Cancelled`() {
        val result: TriggerTransferResult = TriggerTransferResult.AlreadyPresent(archive, "/usb/existing")
        assertFalse(result is TriggerTransferResult.Cancelled)
    }

    // ── SourceType ──────────────────────────────────────────────────────────────

    @Test
    fun `Success with EXISTING_EXTRACTED_DIRECTORY sourceType`() {
        val result = TriggerTransferResult.Success(archive, "/usb/dest", SourceType.EXISTING_EXTRACTED_DIRECTORY)
        assertEquals(SourceType.EXISTING_EXTRACTED_DIRECTORY, result.sourceType)
    }

    @Test
    fun `Success with ARCHIVE_EXTRACTED_TO_USB sourceType`() {
        val result = TriggerTransferResult.Success(archive, "/usb/dest", SourceType.ARCHIVE_EXTRACTED_TO_USB)
        assertEquals(SourceType.ARCHIVE_EXTRACTED_TO_USB, result.sourceType)
    }

    // ── FailureReason ───────────────────────────────────────────────────────────

    @Test
    fun `Failed with LZ4_UNAVAILABLE reason`() {
        val result = TriggerTransferResult.Failed(archive, FailureReason.LZ4_UNAVAILABLE)
        assertEquals(FailureReason.LZ4_UNAVAILABLE, result.reason)
    }

    @Test
    fun `Failed with FIFO_CREATE_FAILED reason`() {
        val result = TriggerTransferResult.Failed(archive, FailureReason.FIFO_CREATE_FAILED)
        assertEquals(FailureReason.FIFO_CREATE_FAILED, result.reason)
    }

    @Test
    fun `FIFO_CREATE_FAILED is distinct from PARTIAL_DIR_CREATE_FAILED`() {
        assertFalse(FailureReason.FIFO_CREATE_FAILED == FailureReason.PARTIAL_DIR_CREATE_FAILED)
    }

    @Test
    fun `Failed message is optional`() {
        val withoutMsg = TriggerTransferResult.Failed(archive, FailureReason.UNKNOWN)
        val withMsg = TriggerTransferResult.Failed(archive, FailureReason.UNKNOWN, "detail")
        assertTrue(withoutMsg.message == null)
        assertNotNull(withMsg.message)
    }

    // ── Cancelled ──────────────────────────────────────────────────────────────

    @Test
    fun `Cancelled stores partialOutputRemoved true`() {
        val result = TriggerTransferResult.Cancelled(archive, partialOutputRemoved = true)
        assertTrue(result.partialOutputRemoved)
    }

    @Test
    fun `Cancelled stores partialOutputRemoved false`() {
        val result = TriggerTransferResult.Cancelled(archive, partialOutputRemoved = false)
        assertFalse(result.partialOutputRemoved)
    }

    // ── Duplicate prevention naming rules ──────────────────────────────────────

    @Test
    fun `partial dir name differs from final dir name`() {
        val stem = archive.stem
        val sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000"
        val partialDir = "$sessionDir/.$stem.partial"
        val finalDir = "$sessionDir/$stem"
        assertFalse(partialDir == finalDir)
        assertTrue(partialDir.contains(".$stem.partial"))
        assertFalse(partialDir == finalDir)
    }

    @Test
    fun `similar stem with different timestamp does not equal this stem`() {
        val otherStem = "trigger_2_HU_20260702_090000_COREDUMP"
        assertFalse(archive.stem == otherStem)
    }

    @Test
    fun `dlt_offlinetrace stem does not equal bare stem`() {
        val offlineStem = "${archive.stem}_dlt_offlinetrace"
        assertFalse(archive.stem == offlineStem)
    }

    // ── UsbTransferHandle ───────────────────────────────────────────────────────

    @Test
    fun `UsbTransferHandle stores all fields`() {
        val handle = UsbTransferHandle(
            jobId = "a1b2c3d4e5f60718",
            remotePid = 12345,
            statusPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/a1b2c3d4e5f60718/status",
            cancelPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/a1b2c3d4e5f60718/cancel"
        )
        assertEquals("a1b2c3d4e5f60718", handle.jobId)
        assertEquals(12345, handle.remotePid)
        assertTrue(handle.statusPath.endsWith("/status"))
        assertTrue(handle.cancelPath.endsWith("/cancel"))
    }
}
