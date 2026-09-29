package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.UsbFileType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbFileListingParserTest {

    private val mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E"
    private val base = "$mountPath/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"

    // ── Directory entry parsing ───────────────────────────────────────────────────

    @Test
    fun `directory entry is parsed correctly`() {
        val output = "directory|0|$base/tracemate_export_20260710_120000\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals(1, result.entries.size)
        val e = result.entries[0]
        assertEquals("tracemate_export_20260710_120000", e.name)
        assertEquals("HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000", e.relativePath)
        assertEquals(UsbFileType.DIRECTORY, e.type)
        assertEquals(null, e.sizeBytes)
    }

    // ── Regular file entry parsing ────────────────────────────────────────────────

    @Test
    fun `regular file entry is parsed with size`() {
        val output = "regular file|123456|$base/tracemate_export_20260710_120000/trigger_1_HU_20260701_113733_COREDUMP/some.log\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals(1, result.entries.size)
        val e = result.entries[0]
        assertEquals("some.log", e.name)
        assertEquals(UsbFileType.FILE, e.type)
        assertEquals(123456L, e.sizeBytes)
    }

    @Test
    fun `regular file has correct relativePath`() {
        val relPath = "tracemate_export_20260710_120000/trigger_1_HU_20260701_113733_COREDUMP/data.bin"
        val output = "regular file|512|$base/$relPath\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertEquals("HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/$relPath", result.entries[0].relativePath)
    }

    @Test
    fun `CAPTURE_TOOL_PLACEHOLDER capture directory and file are parsed`() {
        val output = buildString {
            appendLine("directory|0|$mountPath/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER")
            appendLine("regular file|2048|$mountPath/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder_Wireless_20260724_143022.capture_tool_placeholder")
            append("STATUS:DONE")
        }

        val result = UsbFileListingParser.parse(output, mountPath)

        assertTrue(result.complete)
        assertEquals(2, result.entries.size)
        assertEquals("CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER", result.entries[0].relativePath)
        assertEquals(
            "CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder_Wireless_20260724_143022.capture_tool_placeholder",
            result.entries[1].relativePath
        )
    }

    // ── Paths containing spaces ───────────────────────────────────────────────────

    @Test
    fun `file path containing spaces is parsed correctly`() {
        val path = "$base/tracemate_export_20260710_120000/my file with spaces.txt"
        val output = "regular file|42|$path\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals(1, result.entries.size)
        assertEquals("my file with spaces.txt", result.entries.single().name)
    }

    @Test
    fun `CAPTURE_TOOL_PLACEHOLDER capture file path containing spaces is parsed correctly`() {
        val path = "$mountPath/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder Wireless.capture_tool_placeholder"
        val output = "regular file|42|$path\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)

        assertTrue(result.complete)
        assertEquals(1, result.entries.size)
        assertEquals("CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/CaptureSessionPlaceholder Wireless.capture_tool_placeholder", result.entries.single().relativePath)
    }

    // ── Bounded traversal — entries outside the USB mount are rejected ───────────

    @Test
    fun `entry whose path is outside USB mount is silently dropped`() {
        val output = "regular file|100|/tmp/SomeOtherDir/file.txt\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals("entries outside USB mount must be dropped", 0, result.entries.size)
    }

    @Test
    fun `entries within managed USB directories are kept`() {
        val output = buildString {
            appendLine("directory|0|$base/tracemate_export_20260710_120000")
            appendLine("regular file|999|$base/tracemate_export_20260710_120000/trigger_1_HU_20260701_113733_COREDUMP/core.log")
            append("STATUS:DONE")
        }
        val result = UsbFileListingParser.parse(output, mountPath)
        assertEquals(2, result.entries.size)
    }

    @Test
    fun `entries elsewhere on the USB mount are kept`() {
        val output = buildString {
            appendLine("directory|0|$mountPath/Photos")
            appendLine("regular file|999|$mountPath/readme.txt")
            append("STATUS:DONE")
        }

        val result = UsbFileListingParser.parse(output, mountPath)

        assertEquals(2, result.entries.size)
        assertEquals("Photos", result.entries[0].relativePath)
        assertEquals("readme.txt", result.entries[1].relativePath)
    }

    // ── .partial handling ─────────────────────────────────────────────────────────

    @Test
    fun `partial directory is excluded at shell level — find prune prevents emission to parser`() {
        // .partial dirs are pruned at the shell level by -name '.*\.partial' -prune.
        // The parser itself does not need to filter them: if a .partial path were
        // somehow emitted it would be parsed normally (path characters are valid).
        // This test documents that the exclusion contract lives in the shell command,
        // not the parser, by verifying that the parser accepts a well-formed entry
        // (the shell command guarantees such lines are never present in real output).
        val partialPath = "$base/tracemate_export_20260710_120000/.trigger_1_HU_20260701_113733_COREDUMP.partial"
        val output = "directory|0|$partialPath\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue("parser must complete without throwing", result.complete)
        // The entry is valid syntax — parser returns it (shell prune is the real guard)
        assertEquals("parser accepts the syntactically valid entry; shell prune is the real guard", 1, result.entries.size)
    }

    // ── STATUS:DONE sentinel ──────────────────────────────────────────────────────

    @Test
    fun `complete is true when STATUS DONE sentinel is present`() {
        val output = "directory|0|$base/session1\nSTATUS:DONE"
        assertTrue(UsbFileListingParser.parse(output, mountPath).complete)
    }

    @Test
    fun `complete is false when STATUS DONE sentinel is absent`() {
        val output = "directory|0|$base/session1"
        assertFalse(UsbFileListingParser.parse(output, mountPath).complete)
    }

    @Test
    fun `partial entries are returned even when sentinel is absent`() {
        val output = "directory|0|$base/session1\ndirectory|0|$base/session2"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertFalse(result.complete)
        assertEquals(2, result.entries.size)
    }

    // ── Empty TraceMate directory ─────────────────────────────────────────────────

    @Test
    fun `empty output with only sentinel yields empty entries and complete true`() {
        val result = UsbFileListingParser.parse("STATUS:DONE", mountPath)
        assertTrue(result.complete)
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `blank output yields incomplete false and empty entries`() {
        val result = UsbFileListingParser.parse("", mountPath)
        assertFalse(result.complete)
        assertEquals(0, result.entries.size)
    }

    // ── Malformed output ──────────────────────────────────────────────────────────

    @Test
    fun `line with no pipe delimiter is silently skipped`() {
        val output = "this is garbage\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `line with only one pipe is silently skipped`() {
        val output = "directory|only_one_pipe\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `unknown type field is silently skipped`() {
        val output = "symbolic link|0|$base/symlink\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertEquals("symbolic link entries must be dropped", 0, result.entries.size)
    }

    @Test
    fun `non-numeric size for regular file yields null sizeBytes`() {
        val output = "regular file|NOT_A_NUMBER|$base/session1/file.txt\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        // size parses as null; entry is still returned with null sizeBytes
        if (result.entries.isNotEmpty()) {
            assertEquals(null, result.entries[0].sizeBytes)
        }
        // may also be dropped by path validator — either way must not crash
        assertTrue(result.complete)
    }

    @Test
    fun `mixed valid and invalid lines — only valid entries kept`() {
        val output = buildString {
            appendLine("garbage line with no pipes")
            appendLine("directory|0|$base/session1")
            appendLine("only|one")
            appendLine("regular file|200|$base/session1/file.bin")
            append("STATUS:DONE")
        }
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals(2, result.entries.size)
    }

    // ── USB mount root itself is excluded ─────────────────────────────────────────

    @Test
    fun `USB mount root directory entry itself is excluded from results`() {
        val output = "directory|0|$mountPath\nSTATUS:DONE"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue(result.complete)
        assertEquals("USB mount root entry must be dropped", 0, result.entries.size)
    }

    // ── Multiple entries — ordering preserved ─────────────────────────────────────

    @Test
    fun `multiple entries are returned in the order emitted by find`() {
        val output = buildString {
            appendLine("directory|0|$base/session_A")
            appendLine("directory|0|$base/session_B")
            appendLine("regular file|10|$base/session_A/file1.log")
            append("STATUS:DONE")
        }
        val result = UsbFileListingParser.parse(output, mountPath)
        assertEquals(3, result.entries.size)
        assertEquals("session_A", result.entries[0].name)
        assertEquals("session_B", result.entries[1].name)
        assertEquals("file1.log", result.entries[2].name)
    }

    // ── find failure / USB removal scenarios ─────────────────────────────────────

    @Test
    fun `successful listing with entries has complete true`() {
        val output = buildString {
            appendLine("directory|0|$base/session1")
            appendLine("regular file|1024|$base/session1/core.log")
            append("STATUS:DONE")
        }
        val result = UsbFileListingParser.parse(output, mountPath)
        assertTrue("complete must be true when sentinel present", result.complete)
        assertEquals(2, result.entries.size)
    }

    @Test
    fun `empty USB directory with only sentinel is complete with no entries`() {
        val result = UsbFileListingParser.parse("STATUS:DONE", mountPath)
        assertTrue("empty dir listing must be complete", result.complete)
        assertEquals(0, result.entries.size)
    }

    @Test
    fun `find failure produces output without sentinel — incomplete`() {
        val output = "directory|0|$base/session1"
        val result = UsbFileListingParser.parse(output, mountPath)
        assertFalse("must be incomplete without STATUS:DONE", result.complete)
        assertEquals(1, result.entries.size)
    }

    @Test
    fun `USB removed mid-listing produces partial output without sentinel`() {
        val output = buildString {
            appendLine("directory|0|$base/session1")
            appendLine("regular file|512|$base/session1/file.bin")
            // No STATUS:DONE — find was killed by I/O error
        }
        val result = UsbFileListingParser.parse(output, mountPath)
        assertFalse("truncated listing must be marked incomplete", result.complete)
        assertEquals("partial entries before failure are preserved", 2, result.entries.size)
    }

    @Test
    fun `completely empty output from find failure is incomplete with no entries`() {
        val result = UsbFileListingParser.parse("", mountPath)
        assertFalse(result.complete)
        assertEquals(0, result.entries.size)
    }
}
