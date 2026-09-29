package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.ActiveJobSnapshot
import com.example.adb_connection.domain.model.ActiveTransferSnapshot
import com.example.adb_connection.domain.model.ArchiveVariant
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.RemoteJobLifecycle
import com.example.adb_connection.domain.model.ResultKind
import com.example.adb_connection.domain.model.ResultSummary
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TileResultSnapshot
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.UsbMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveTransferSnapshotCodecTest {

    private val mount = UsbMount(
        devicePath = "/dev/sda1",
        mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E",
        fileSystem = "exfat",
        mountOptions = setOf("rw", "relatime")
    )

    private fun archive(stem: String, trigger: Int, variant: ArchiveVariant = ArchiveVariant.NORMAL) = TriggerArchive(
        triggerNumber = trigger,
        stem = stem,
        archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/$stem.tar.lz4",
        extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/$stem",
        variant = variant
    )

    @Test
    fun `round trips a minimal snapshot with no job and no results`() {
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = listOf(archive("trigger_1_HU_20260701_113733_COREDUMP", 1)),
            currentIndex = 0,
            currentJob = null,
            tileResults = emptyList(),
            cancelRequested = false
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips a snapshot with an in-flight job`() {
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = listOf(
                archive("trigger_1_HU_20260701_113733_COREDUMP", 1),
                archive("trigger_2_HU_20260701_120000_COREDUMP", 2)
            ),
            currentIndex = 1,
            currentJob = ActiveJobSnapshot(
                jobId = "a1b2c3d4e5f60718",
                stem = "trigger_2_HU_20260701_120000_COREDUMP",
                variant = ArchiveVariant.OFFLINE_TRACE,
                useStrategyA = false,
                phase = RemoteJobLifecycle.RUNNING
            ),
            tileResults = listOf(
                TileResultSnapshot(
                    stem = "trigger_1_HU_20260701_113733_COREDUMP",
                    normalResult = ResultSummary(kind = ResultKind.SUCCESS, path = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/x", sourceType = SourceType.ARCHIVE_EXTRACTED_TO_USB),
                    offlineResult = null
                )
            ),
            cancelRequested = false
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips a snapshot with launching-phase job`() {
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = listOf(archive("trigger_1_HU_20260701_113733_COREDUMP", 1)),
            currentIndex = 0,
            currentJob = ActiveJobSnapshot(
                jobId = "0000111122223333",
                stem = "trigger_1_HU_20260701_113733_COREDUMP",
                variant = ArchiveVariant.NORMAL,
                useStrategyA = true,
                phase = RemoteJobLifecycle.LAUNCHING
            ),
            tileResults = emptyList(),
            cancelRequested = false
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips cancelRequested true`() {
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = emptyList(),
            currentIndex = 0,
            currentJob = null,
            tileResults = emptyList(),
            cancelRequested = true
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(true, decoded?.cancelRequested)
    }

    @Test
    fun `round trips all four result kinds`() {
        val stems = listOf("s-success", "s-failed", "s-cancelled", "s-already")
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = stems.mapIndexed { i, s -> archive(s, i + 1) },
            currentIndex = 4,
            currentJob = null,
            tileResults = listOf(
                TileResultSnapshot("s-success", normalResult = ResultSummary(ResultKind.SUCCESS, path = "/p1", sourceType = SourceType.ARCHIVE_EXTRACTED_TO_USB)),
                TileResultSnapshot("s-failed", normalResult = ResultSummary(ResultKind.FAILED, reason = FailureReason.USB_FULL, message = "No space left on device")),
                TileResultSnapshot("s-cancelled", normalResult = ResultSummary(ResultKind.CANCELLED, partialOutputRemoved = true)),
                TileResultSnapshot("s-already", normalResult = ResultSummary(ResultKind.ALREADY_PRESENT, path = "/p2"))
            ),
            cancelRequested = false
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `round trips both normal and offline results for the same stem`() {
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = listOf(archive("trigger_1_HU_20260701_113733_COREDUMP", 1)),
            currentIndex = 1,
            currentJob = null,
            tileResults = listOf(
                TileResultSnapshot(
                    stem = "trigger_1_HU_20260701_113733_COREDUMP",
                    normalResult = ResultSummary(ResultKind.SUCCESS, path = "/a", sourceType = SourceType.ARCHIVE_EXTRACTED_TO_USB),
                    offlineResult = ResultSummary(ResultKind.FAILED, reason = FailureReason.TAR_EXTRACT_FAILED, message = "bad archive")
                )
            ),
            cancelRequested = false
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `special characters in paths and messages round trip via percent-encoding`() {
        val trickyStem = "trigger_1_HU 2026-07-01 & co|de=100%"
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount.copy(mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/my usb (2)"),
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/my usb (2)/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/session with spaces & symbols=1",
            archives = listOf(
                TriggerArchive(
                    triggerNumber = 1,
                    stem = trickyStem,
                    archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/$trickyStem.tar.lz4",
                    extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/$trickyStem"
                )
            ),
            currentIndex = 0,
            currentJob = null,
            tileResults = listOf(
                TileResultSnapshot(
                    stem = trickyStem,
                    normalResult = ResultSummary(
                        kind = ResultKind.FAILED,
                        reason = FailureReason.CP_FAILED,
                        message = "line1\nline2 with 'quotes' and \"double quotes\" | pipe = equals"
                    )
                )
            ),
            cancelRequested = false
        )

        val decoded = ActiveTransferSnapshotCodec.decode(ActiveTransferSnapshotCodec.encode(snapshot))

        assertEquals(snapshot, decoded)
    }

    @Test
    fun `decode returns null for blank input`() {
        assertNull(ActiveTransferSnapshotCodec.decode(""))
    }

    @Test
    fun `decode returns null for garbage input missing required fields`() {
        assertNull(ActiveTransferSnapshotCodec.decode("SOME_RANDOM_LINE=1|2|3"))
    }

    @Test
    fun `decode returns null when mount is malformed`() {
        val raw = "MOUNT=onlyonepart\nSESSION=%2Fmnt%2Ffoo\n"
        assertNull(ActiveTransferSnapshotCodec.decode(raw))
    }

    @Test
    fun `decode is defensive against exceptions and never throws`() {
        // Invalid enum name for RemoteJobLifecycle should be caught and yield null, not throw.
        val raw = "MOUNT=%2Fdev%2Fsda1|%2Fmnt%2Fx|exfat|rw\n" +
            "SESSION=%2Fmnt%2Fx%2FHEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER%2Fsession\n" +
            "JOB=jobid|stem|NORMAL|true|NOT_A_REAL_PHASE\n"
        assertNull(ActiveTransferSnapshotCodec.decode(raw))
    }

    @Test
    fun `encoded output is stable line based text with expected tags`() {
        val snapshot = ActiveTransferSnapshot(
            expectedMount = mount,
            sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000",
            archives = listOf(archive("trigger_1_HU_20260701_113733_COREDUMP", 1)),
            currentIndex = 0,
            currentJob = null,
            tileResults = emptyList(),
            cancelRequested = false
        )
        val encoded = ActiveTransferSnapshotCodec.encode(snapshot)
        val lines = encoded.lines()

        assertTrue(lines.any { it.startsWith("MOUNT=") })
        assertTrue(lines.any { it.startsWith("SESSION=") })
        assertTrue(lines.any { it.startsWith("CANCEL=") })
        assertTrue(lines.any { it.startsWith("INDEX=") })
        assertTrue(lines.any { it.startsWith("ARCHIVE=") })
    }
}
