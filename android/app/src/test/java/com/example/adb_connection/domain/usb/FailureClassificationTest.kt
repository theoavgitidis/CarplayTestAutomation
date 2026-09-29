package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.FailureReason
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Moved from `UsbCopyViewModelTest` alongside `isInfrastructure()` itself, which now lives in
 * `domain.usb.FailureClassification` (used by [DefaultUsbTransferCoordinator], not the ViewModel).
 */
class FailureClassificationTest {

    @Test
    fun `ADB_COMMUNICATION_ERROR is classified as infrastructure`() {
        assertTrue(FailureReason.ADB_COMMUNICATION_ERROR.isInfrastructure())
    }

    @Test
    fun `USB_DISCONNECTED is classified as infrastructure`() {
        assertTrue(FailureReason.USB_DISCONNECTED.isInfrastructure())
    }

    @Test
    fun `USB_NOT_FOUND is classified as infrastructure`() {
        assertTrue(FailureReason.USB_NOT_FOUND.isInfrastructure())
    }

    @Test
    fun `USB_NOT_WRITABLE is classified as infrastructure`() {
        assertTrue(FailureReason.USB_NOT_WRITABLE.isInfrastructure())
    }

    @Test
    fun `USB_MOUNT_CHANGED is classified as infrastructure`() {
        assertTrue(FailureReason.USB_MOUNT_CHANGED.isInfrastructure())
    }

    @Test
    fun `USB_FULL is classified as infrastructure`() {
        assertTrue(FailureReason.USB_FULL.isInfrastructure())
    }

    @Test
    fun `USB_IO_ERROR is classified as infrastructure`() {
        assertTrue(FailureReason.USB_IO_ERROR.isInfrastructure())
    }

    @Test
    fun `JOB_DIR_CREATE_FAILED is classified as infrastructure`() {
        assertTrue(FailureReason.JOB_DIR_CREATE_FAILED.isInfrastructure())
    }

    @Test
    fun `REMOTE_SCRIPT_LAUNCH_FAILED is classified as infrastructure`() {
        assertTrue(FailureReason.REMOTE_SCRIPT_LAUNCH_FAILED.isInfrastructure())
    }

    @Test
    fun `STATUS_FILE_UNREADABLE is classified as infrastructure`() {
        assertTrue(FailureReason.STATUS_FILE_UNREADABLE.isInfrastructure())
    }

    @Test
    fun `WORKER_LOST is classified as infrastructure`() {
        assertTrue(FailureReason.WORKER_LOST.isInfrastructure())
    }

    @Test
    fun `SOURCE_ARCHIVE_MISSING is not classified as infrastructure`() {
        assertFalse(FailureReason.SOURCE_ARCHIVE_MISSING.isInfrastructure())
    }

    @Test
    fun `LZ4_DECOMPRESS_FAILED is not classified as infrastructure`() {
        assertFalse(FailureReason.LZ4_DECOMPRESS_FAILED.isInfrastructure())
    }

    @Test
    fun `UNKNOWN is not classified as infrastructure`() {
        assertFalse(FailureReason.UNKNOWN.isInfrastructure())
    }
}
