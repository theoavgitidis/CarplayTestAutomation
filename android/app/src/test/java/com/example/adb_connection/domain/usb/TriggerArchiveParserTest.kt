package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.ArchiveVariant
import com.example.adb_connection.domain.model.TriggerArchive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerArchiveParserTest {

    private val validPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP.tar.lz4"
    private val validPath1 = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_1_HU_20260701_120000_COREDUMP.tar.lz4"
    private val validPath10 = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_10_HU_20260702_080000_COREDUMP.tar.lz4"
    private val validPath9 = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_9_HU_20260701_090000_COREDUMP.tar.lz4"

    // ── parseSinglePath ─────────────────────────────────────────────────────────

    @Test
    fun `valid path returns correct TriggerArchive`() {
        val result = TriggerArchiveParser.parseSinglePath(validPath)
        assertNotNull(result)
        assertEquals(2, result!!.triggerNumber)
        assertEquals("trigger_2_HU_20260701_113733_COREDUMP", result.stem)
        assertEquals(validPath, result.archivePath)
        assertEquals("HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP", result.extractedDirectoryPath)
        assertEquals("T2", result.displayLabel)
    }

    @Test
    fun `dlt_offlinetrace variant is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP_dlt_offlinetrace.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `JSON file is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP.json"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path not under mnt_trace_eel is rejected`() {
        val path = "/tmp/trigger_2_HU_20260701_113733_COREDUMP.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path with dot-dot is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/../etc/trigger_2_HU_20260701_113733_COREDUMP.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path with dollar sign is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP\$.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path with backtick is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP`.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path with pipe character is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP|.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path with semicolon is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP;.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `path with space is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger 2_HU_20260701_113733_COREDUMP.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `nested path below eel is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP/inner/trigger_3_HU_20260701_113733_COREDUMP.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `extracted directory path does not parse as archive`() {
        val dirPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP"
        assertNull(TriggerArchiveParser.parseSinglePath(dirPath))
    }

    @Test
    fun `malformed filename missing HU section is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_20260701_113733_COREDUMP.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `date with wrong digit count is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_2026070_113733_COREDUMP.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `extra suffix after COREDUMP is rejected`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP_extra.tar.lz4"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `trigger number zero is valid`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_0_HU_20260701_113733_COREDUMP.tar.lz4"
        val result = TriggerArchiveParser.parseSinglePath(path)
        assertNotNull(result)
        assertEquals(0, result!!.triggerNumber)
        assertEquals("T0", result.displayLabel)
    }

    // ── parseDiscoveryOutput ────────────────────────────────────────────────────

    @Test
    fun `empty output returns empty list`() {
        assertTrue(TriggerArchiveParser.parseDiscoveryOutput("").isEmpty())
    }

    @Test
    fun `blank lines in output are ignored`() {
        val output = "\n  \n$validPath\n\n"
        val result = TriggerArchiveParser.parseDiscoveryOutput(output)
        assertEquals(1, result.size)
    }

    @Test
    fun `single valid path returns one archive`() {
        val result = TriggerArchiveParser.parseDiscoveryOutput(validPath)
        assertEquals(1, result.size)
        assertEquals(2, result[0].triggerNumber)
    }

    @Test
    fun `duplicate paths produce single entry`() {
        val output = "$validPath\n$validPath"
        val result = TriggerArchiveParser.parseDiscoveryOutput(output)
        assertEquals(1, result.size)
    }

    @Test
    fun `results are sorted numerically not lexicographically`() {
        val output = "$validPath10\n$validPath9\n$validPath1"
        val result = TriggerArchiveParser.parseDiscoveryOutput(output)
        assertEquals(listOf(1, 9, 10), result.map { it.triggerNumber })
    }

    @Test
    fun `T2 sorts before T10`() {
        val output = "$validPath10\n$validPath"
        val result = TriggerArchiveParser.parseDiscoveryOutput(output)
        assertEquals(2, result[0].triggerNumber)
        assertEquals(10, result[1].triggerNumber)
    }

    @Test
    fun `mixed valid and invalid returns only valid`() {
        val invalid = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP_dlt_offlinetrace.tar.lz4"
        val output = "$validPath\n$invalid\n$validPath1"
        val result = TriggerArchiveParser.parseDiscoveryOutput(output)
        assertEquals(2, result.size)
        assertTrue(result.all { it.archivePath != invalid })
    }

    @Test
    fun `path outside mnt_trace_eel is excluded from discovery`() {
        val outside = "/tmp/trigger_5_HU_20260701_113733_COREDUMP.tar.lz4"
        val result = TriggerArchiveParser.parseDiscoveryOutput("$validPath\n$outside")
        assertEquals(1, result.size)
        assertEquals(validPath, result[0].archivePath)
    }

    // ── isPathSafe ──────────────────────────────────────────────────────────────

    @Test
    fun `safe path with allowed chars passes`() {
        assertTrue(TriggerArchiveParser.isPathSafe("HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/file.tar.lz4"))
    }

    @Test
    fun `path with dot-dot fails`() {
        assertFalse(TriggerArchiveParser.isPathSafe("HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/../etc/passwd"))
    }

    @Test
    fun `path with dollar sign fails`() {
        assertFalse(TriggerArchiveParser.isPathSafe("HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/file\$.lz4"))
    }

    @Test
    fun `empty path fails`() {
        assertFalse(TriggerArchiveParser.isPathSafe(""))
    }

    // ── isJobIdValid ────────────────────────────────────────────────────────────

    @Test
    fun `valid 16 hex lowercase job id passes`() {
        assertTrue(TriggerArchiveParser.isJobIdValid("a1b2c3d4e5f60718"))
    }

    @Test
    fun `uppercase hex job id fails`() {
        assertFalse(TriggerArchiveParser.isJobIdValid("A1B2C3D4E5F60718"))
    }

    @Test
    fun `15 char job id fails`() {
        assertFalse(TriggerArchiveParser.isJobIdValid("a1b2c3d4e5f6071"))
    }

    @Test
    fun `17 char job id fails`() {
        assertFalse(TriggerArchiveParser.isJobIdValid("a1b2c3d4e5f607180"))
    }

    @Test
    fun `job id with dashes fails`() {
        assertFalse(TriggerArchiveParser.isJobIdValid("a1b2c3d4-5f607180"))
    }

    @Test
    fun `empty job id fails`() {
        assertFalse(TriggerArchiveParser.isJobIdValid(""))
    }

    // ── offlineVariant ──────────────────────────────────────────────────────────

    @Test
    fun `offlineVariantBuildsCorrectPaths`() {
        val normal = TriggerArchive(
            triggerNumber = 10,
            stem = "trigger_10_HU_20260713_104602_COREDUMP",
            archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_10_HU_20260713_104602_COREDUMP.tar.lz4",
            extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_10_HU_20260713_104602_COREDUMP"
        )
        val offline = normal.offlineVariant()
        assertEquals("trigger_10_HU_20260713_104602_COREDUMP_dlt_offlinetrace", offline.stem)
        assertEquals(
            "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_10_HU_20260713_104602_COREDUMP_dlt_offlinetrace.tar.lz4",
            offline.archivePath
        )
        assertEquals(
            "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_10_HU_20260713_104602_COREDUMP_dlt_offlinetrace",
            offline.extractedDirectoryPath
        )
        assertEquals(ArchiveVariant.OFFLINE_TRACE, offline.variant)
        assertEquals(10, offline.triggerNumber)
        assertEquals("T10", offline.displayLabel)
    }

    @Test
    fun `isStemValid accepts offline stem`() {
        assertTrue(
            TriggerArchiveParser.isStemValid(
                "trigger_10_HU_20260713_104602_COREDUMP_dlt_offlinetrace"
            )
        )
    }

    @Test
    fun `JSON file is still rejected even with offline-like name`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP.json"
        assertNull(TriggerArchiveParser.parseSinglePath(path))
    }

    @Test
    fun `dlt_offlinetrace archive is not returned by parseSinglePath — discovery is normal-only`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP_dlt_offlinetrace.tar.lz4"
        assertNull("Offline archive must not be returned by discovery parser", TriggerArchiveParser.parseSinglePath(path))
    }

    // ── isUsbMountSafe ──────────────────────────────────────────────────────────

    @Test
    fun `valid usb mount path passes`() {
        assertTrue(TriggerArchiveParser.isUsbMountSafe("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E"))
    }

    @Test
    fun `run_media path passes`() {
        assertTrue(TriggerArchiveParser.isUsbMountSafe("UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER/user/USB_STICK"))
    }

    @Test
    fun `usb mount with dot-dot fails`() {
        assertFalse(TriggerArchiveParser.isUsbMountSafe("/mnt/../etc"))
    }

    @Test
    fun `usb mount with semicolon fails`() {
        assertFalse(TriggerArchiveParser.isUsbMountSafe("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb;rm -rf /"))
    }

    @Test
    fun `empty usb mount fails`() {
        assertFalse(TriggerArchiveParser.isUsbMountSafe(""))
    }

    // ── isUsbMountPathValid ─────────────────────────────────────────────────────

    @Test
    fun `valid mnt media subdirectory passes`() {
        assertTrue(TriggerArchiveParser.isUsbMountPathValid("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E"))
    }

    @Test
    fun `parent mnt media itself is rejected`() {
        assertFalse(TriggerArchiveParser.isUsbMountPathValid("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER"))
    }

    @Test
    fun `nested path below mnt media is rejected`() {
        assertFalse(TriggerArchiveParser.isUsbMountPathValid("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/USB/sub"))
    }

    @Test
    fun `run media path is rejected by mount path validator`() {
        assertFalse(TriggerArchiveParser.isUsbMountPathValid("UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER/user/USB"))
    }

    @Test
    fun `path traversal in mount path is rejected`() {
        assertFalse(TriggerArchiveParser.isUsbMountPathValid("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/../etc"))
    }

    @Test
    fun `empty mount path is rejected`() {
        assertFalse(TriggerArchiveParser.isUsbMountPathValid(""))
    }

    // ── isDevicePathSafe ────────────────────────────────────────────────────────

    @Test
    fun `valid device path passes`() {
        assertTrue(TriggerArchiveParser.isDevicePathSafe("/dev/sdb1"))
    }

    @Test
    fun `device path with semicolon is rejected`() {
        assertFalse(TriggerArchiveParser.isDevicePathSafe("/dev/sdb1;rm -rf /"))
    }

    @Test
    fun `device path with dot-dot is rejected`() {
        assertFalse(TriggerArchiveParser.isDevicePathSafe("/dev/../etc/sdb1"))
    }

    @Test
    fun `empty device path is rejected`() {
        assertFalse(TriggerArchiveParser.isDevicePathSafe(""))
    }
}
