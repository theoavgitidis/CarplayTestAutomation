package com.example.adb_connection.ui.usbcopy

import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [UsbCopyUiState.Ready.usbMount] is the single source of truth the "Begin" button's
 * enabled state (`canStart` in UsbCopyScreen) depends on — it must only be non-null
 * when the USB is actually writable, so a stale, read-only, or missing mount never
 * allows a transfer to start.
 */
class UsbCopyUiStateTest {

    private val writableMount = UsbMount(
        devicePath = "/dev/sdb1",
        mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/SWUP",
        fileSystem = "exfat",
        mountOptions = setOf("rw", "relatime")
    )
    private val readOnlyMount = UsbMount(
        devicePath = "/dev/sdb1",
        mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/SWUP",
        fileSystem = "exfat",
        mountOptions = setOf("ro", "relatime")
    )

    // ── Stale mount / no /proc/mounts entry ──────────────────────────────────────

    @Test
    fun `NotFound leaves usbMount null so Begin stays disabled`() {
        val ready = UsbCopyUiState.Ready(tiles = emptyList(), usbDetectionResult = UsbDetectionResult.NotFound)
        assertNull(ready.usbMount)
    }

    // ── Read-only mount ───────────────────────────────────────────────────────────

    @Test
    fun `ReadOnly leaves usbMount null so Begin stays disabled`() {
        val ready = UsbCopyUiState.Ready(
            tiles = emptyList(),
            usbDetectionResult = UsbDetectionResult.ReadOnly(readOnlyMount)
        )
        assertNull(ready.usbMount)
    }

    @Test
    fun `MultipleReadOnlyMounts leaves usbMount null so an arbitrary mount cannot be remounted`() {
        val ready = UsbCopyUiState.Ready(
            tiles = emptyList(),
            usbDetectionResult = UsbDetectionResult.MultipleReadOnlyMounts(listOf(readOnlyMount, readOnlyMount.copy(mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/OTHER")))
        )

        assertNull(ready.usbMount)
    }

    @Test
    fun `UnsupportedMountLayout leaves usbMount null so Begin stays disabled`() {
        val ready = UsbCopyUiState.Ready(
            tiles = emptyList(),
            usbDetectionResult = UsbDetectionResult.UnsupportedMountLayout(candidateCount = 1)
        )

        assertNull(ready.usbMount)
    }

    // ── Writable mount ────────────────────────────────────────────────────────────

    @Test
    fun `Writable exposes the mount so Begin can be enabled`() {
        val ready = UsbCopyUiState.Ready(
            tiles = emptyList(),
            usbDetectionResult = UsbDetectionResult.Writable(writableMount)
        )
        assertEquals(writableMount, ready.usbMount)
    }

    // ── Remount transitions ReadOnly -> Writable ─────────────────────────────────

    @Test
    fun `after a successful remount, usbMount becomes non-null`() {
        val beforeRemount = UsbCopyUiState.Ready(
            tiles = emptyList(),
            usbDetectionResult = UsbDetectionResult.ReadOnly(readOnlyMount)
        )
        assertNull(beforeRemount.usbMount)

        val verifiedRw = writableMount // same device + mount path, now rw
        val afterRemount = beforeRemount.copy(usbDetectionResult = UsbDetectionResult.Writable(verifiedRw))
        assertEquals(verifiedRw, afterRemount.usbMount)
    }
}
