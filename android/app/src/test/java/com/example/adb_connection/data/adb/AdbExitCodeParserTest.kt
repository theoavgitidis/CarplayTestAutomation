package com.example.adb_connection.data.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbExitCodeParserTest {

    @Test
    fun `wrapCommandWithExitMarker appends marker echo`() {
        val wrapped = wrapCommandWithExitMarker("ls /tmp")
        assertEquals("ls /tmp; echo \"TRACEMATE_EXIT:\$?\"", wrapped)
    }

    @Test
    fun `parseShellOutput with exit code 0 returns success`() {
        val rawOutput = "file1.txt\nfile2.txt\nTRACEMATE_EXIT:0\n"
        val result = parseShellOutput("ls /tmp", rawOutput)

        assertTrue(result.success)
        assertEquals("ls /tmp", result.command)
        assertEquals("file1.txt\nfile2.txt", result.output)
        assertEquals(0, result.exitCode)
        assertNull(result.errorMessage)
    }

    @Test
    fun `parseShellOutput with non-zero exit code returns failure`() {
        val rawOutput = "No such file or directory\nTRACEMATE_EXIT:1\n"
        val result = parseShellOutput("ls /nonexistent", rawOutput)

        assertFalse(result.success)
        assertEquals("ls /nonexistent", result.command)
        assertEquals("No such file or directory", result.output)
        assertEquals(1, result.exitCode)
        assertNull(result.errorMessage)
    }

    @Test
    fun `parseShellOutput with exit code 127 returns failure`() {
        val rawOutput = "sh: unknown_cmd: not found\nTRACEMATE_EXIT:127\n"
        val result = parseShellOutput("unknown_cmd", rawOutput)

        assertFalse(result.success)
        assertEquals(127, result.exitCode)
        assertEquals("sh: unknown_cmd: not found", result.output)
    }

    @Test
    fun `parseShellOutput with empty output and exit code 0`() {
        val rawOutput = "TRACEMATE_EXIT:0\n"
        val result = parseShellOutput("true", rawOutput)

        assertTrue(result.success)
        assertEquals("true", result.command)
        assertEquals("", result.output)
        assertEquals(0, result.exitCode)
    }

    @Test
    fun `parseShellOutput with missing marker returns protocol error`() {
        val rawOutput = "some output without marker\n"
        val result = parseShellOutput("echo hello", rawOutput)

        assertFalse(result.success)
        assertEquals("echo hello", result.command)
        assertEquals("some output without marker", result.output)
        assertNull(result.exitCode)
        assertEquals("Protocol error: exit-code marker missing from output", result.errorMessage)
    }

    @Test
    fun `parseShellOutput with invalid exit code value returns protocol error`() {
        val rawOutput = "output\nTRACEMATE_EXIT:abc\n"
        val result = parseShellOutput("cmd", rawOutput)

        assertFalse(result.success)
        assertEquals("cmd", result.command)
        assertEquals("output", result.output)
        assertNull(result.exitCode)
        assertEquals("Protocol error: invalid exit code value 'abc'", result.errorMessage)
    }

    @Test
    fun `parseShellOutput with empty raw output returns protocol error`() {
        val rawOutput = ""
        val result = parseShellOutput("cmd", rawOutput)

        assertFalse(result.success)
        assertNull(result.exitCode)
        assertEquals("Protocol error: exit-code marker missing from output", result.errorMessage)
    }

    @Test
    fun `parseShellOutput uses last marker occurrence`() {
        val rawOutput = "echo TRACEMATE_EXIT:99\nreal output\nTRACEMATE_EXIT:0\n"
        val result = parseShellOutput("tricky", rawOutput)

        assertTrue(result.success)
        assertEquals(0, result.exitCode)
        assertEquals("echo TRACEMATE_EXIT:99\nreal output", result.output)
    }

    @Test
    fun `parseShellOutput preserves multiline output`() {
        val rawOutput = "line1\nline2\nline3\nTRACEMATE_EXIT:0\n"
        val result = parseShellOutput("multiline", rawOutput)

        assertTrue(result.success)
        assertEquals("line1\nline2\nline3", result.output)
    }

    @Test
    fun `parseShellOutput with only whitespace before marker`() {
        val rawOutput = "   \n\nTRACEMATE_EXIT:0\n"
        val result = parseShellOutput("whitespace", rawOutput)

        assertTrue(result.success)
        assertEquals(0, result.exitCode)
        assertEquals("", result.output)
    }
}
