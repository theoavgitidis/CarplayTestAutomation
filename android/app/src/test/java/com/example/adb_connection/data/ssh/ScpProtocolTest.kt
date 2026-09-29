package com.example.adb_connection.data.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException

class ScpProtocolTest {
    @Test
    fun `read ack accepts success byte`() {
        ScpProtocol.readAck(ByteArrayInputStream(byteArrayOf(0)))
    }

    @Test
    fun `read ack reports remote error`() {
        val error = expectIOException {
            ScpProtocol.readAck(ByteArrayInputStream("\u0001permission denied\n".toByteArray()))
        }

        assertEquals("SCP remote error: permission denied", error.message)
    }

    @Test
    fun `read ack rejects unexpected protocol byte`() {
        val error = expectIOException {
            ScpProtocol.readAck(ByteArrayInputStream(byteArrayOf(3)))
        }

        assertEquals("Invalid SCP acknowledgement byte: 3", error.message)
    }

    @Test
    fun `read file header parses binary transfer metadata`() {
        val header = ScpProtocol.readFileHeader(
            ByteArrayInputStream("C0644 42 capture.capture_tool_placeholder\n".toByteArray())
        )

        assertEquals(42, header.size)
        assertEquals("capture.capture_tool_placeholder", header.fileName)
    }

    @Test
    fun `read file header rejects an unsafe file name`() {
        val error = expectIOException {
            ScpProtocol.readFileHeader(ByteArrayInputStream("C0644 42 ../capture.capture_tool_placeholder\n".toByteArray()))
        }

        assertEquals("SCP file name contains an unsafe character", error.message)
    }

    @Test
    fun `shell quote protects apostrophes and rejects line breaks`() {
        assertEquals("'/tmp/it'\"'\"'s.capture_tool_placeholder'", ScpProtocol.shellQuote("/tmp/it's.capture_tool_placeholder"))
        expectIllegalArgument { ScpProtocol.shellQuote("/tmp/file\nother") }
    }

    @Test
    fun `upload header includes safe mode size and name`() {
        assertEquals(
            "C0644 123 capture.capture_tool_placeholder\n",
            ScpProtocol.header(123, "capture.capture_tool_placeholder").toString(Charsets.UTF_8)
        )
    }

    private fun expectIOException(block: () -> Unit): IOException = try {
        block()
        fail("Expected IOException")
        throw AssertionError("unreachable")
    } catch (error: IOException) {
        error
    }

    private fun expectIllegalArgument(block: () -> Unit) {
        try {
            block()
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }
}
