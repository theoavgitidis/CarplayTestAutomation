package com.example.adb_connection.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureToolPlaceholderCaptureFilesTest {

    @Test
    fun `CAPTURE_TOOL_PLACEHOLDER listing preserves every CAPTURE_TOOL_PLACEHOLDER capture path`() {
        val output = "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/drive.capture_tool_placeholder\n" +
            "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/parking.capture_tool_placeholder\n"

        assertEquals(
            listOf(
                "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/drive.capture_tool_placeholder",
                "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/parking.capture_tool_placeholder"
            ),
            parseCaptureToolPlaceholderCaptureFiles(output)
        )
    }

    @Test
    fun `CAPTURE_TOOL_PLACEHOLDER listing ignores shell marker and non capture output`() {
        val output = "NO_FILES\npermission denied\n/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/readme.txt\n"

        assertTrue(parseCaptureToolPlaceholderCaptureFiles(output).isEmpty())
    }

    @Test
    fun `CAPTURE_TOOL_PLACEHOLDER listing filters non CAPTURE_TOOL_PLACEHOLDER files without dropping captures`() {
        val output = "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/first.capture_tool_placeholder\n" +
            "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/notes.log\n" +
            "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/second.capture_tool_placeholder\n"

        assertEquals(
            listOf(
                "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/first.capture_tool_placeholder",
                "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/second.capture_tool_placeholder"
            ),
            parseCaptureToolPlaceholderCaptureFiles(output)
        )
    }

    @Test
    fun `CAPTURE_TOOL_PLACEHOLDER listing only accepts the extension used by the export command`() {
        val output = "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/upper.CAPTURE_TOOL_PLACEHOLDER\n" +
            "/Users/CAPTURE_TOOL_PLACEHOLDER_USER_PLACEHOLDER/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/capture.capture_tool_placeholder.bak\n"

        assertTrue(parseCaptureToolPlaceholderCaptureFiles(output).isEmpty())
    }
}
