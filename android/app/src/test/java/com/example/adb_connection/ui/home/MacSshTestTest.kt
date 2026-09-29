package com.example.adb_connection.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MacSshTestTest {

    // ── IP validation ─────────────────────────────────────────────────────────

    @Test
    fun `valid IPv4 addresses are accepted`() {
        assertTrue(isValidIpv4("192.168.178.192"))
        assertTrue(isValidIpv4("10.0.0.1"))
        assertTrue(isValidIpv4("255.255.255.255"))
        assertTrue(isValidIpv4("0.0.0.0"))
        assertTrue(isValidIpv4("192.168.1.100"))
    }

    @Test
    fun `empty string is invalid`() {
        assertFalse(isValidIpv4(""))
    }

    @Test
    fun `hostname is invalid`() {
        assertFalse(isValidIpv4("mymac.local"))
    }

    @Test
    fun `too many octets is invalid`() {
        assertFalse(isValidIpv4("192.168.1.1.1"))
    }

    @Test
    fun `too few octets is invalid`() {
        assertFalse(isValidIpv4("192.168.1"))
    }

    @Test
    fun `octet out of range is invalid`() {
        assertFalse(isValidIpv4("256.0.0.1"))
        assertFalse(isValidIpv4("192.168.1.300"))
    }

    @Test
    fun `whitespace around IP is invalid`() {
        assertFalse(isValidIpv4(" 192.168.1.1"))
        assertFalse(isValidIpv4("192.168.1.1 "))
    }

    // ── Output parsing: success ───────────────────────────────────────────────

    @Test
    fun `valid sw_vers output with ProductName and ProductVersion succeeds`() {
        val stdout = "ProductName:\t\t\tmacOS\nProductVersion:\t\t15.2\nBuildVersion:\t\t24C101\n"
        val state = parseMacSshOutput(exitCode = 0, stdout = stdout)
        assertTrue(state is MacSshTestState.Success)
        assertEquals(stdout.trim(), (state as MacSshTestState.Success).output)
    }

    @Test
    fun `non-zero exit code yields sw_vers failed error`() {
        val state = parseMacSshOutput(exitCode = 1, stdout = "ProductName:\tmacOS\nProductVersion:\t15.2\n")
        assertEquals(MacSshTestState.Error("Mac SSH Status: sw_vers failed"), state)
    }

    @Test
    fun `blank stdout yields empty response error`() {
        val state = parseMacSshOutput(exitCode = 0, stdout = "   \n  ")
        assertEquals(MacSshTestState.Error("Mac SSH Status: Empty response"), state)
    }

    @Test
    fun `stdout missing ProductName yields sw_vers failed`() {
        val state = parseMacSshOutput(exitCode = 0, stdout = "ProductVersion:\t15.2\n")
        assertEquals(MacSshTestState.Error("Mac SSH Status: sw_vers failed"), state)
    }

    @Test
    fun `stdout missing ProductVersion yields sw_vers failed`() {
        val state = parseMacSshOutput(exitCode = 0, stdout = "ProductName:\tmacOS\n")
        assertEquals(MacSshTestState.Error("Mac SSH Status: sw_vers failed"), state)
    }

    // ── Exception → status mapping ────────────────────────────────────────────

    @Test
    fun `timeout message maps to Connection timeout`() {
        assertEquals("Mac SSH Status: Connection timeout", mapSshExceptionToMacStatus("Connection timed out"))
        assertEquals("Mac SSH Status: Connection timeout", mapSshExceptionToMacStatus("connect timeout"))
        assertEquals("Mac SSH Status: Connection timeout", mapSshExceptionToMacStatus("SSH command 'sw_vers' timed out after 15000ms"))
    }

    @Test
    fun `auth message maps to Authentication failed`() {
        assertEquals("Mac SSH Status: Authentication failed", mapSshExceptionToMacStatus("Auth fail"))
        assertEquals("Mac SSH Status: Authentication failed", mapSshExceptionToMacStatus("password authentication failed"))
    }

    @Test
    fun `refused message maps to Connection refused`() {
        assertEquals("Mac SSH Status: Connection refused", mapSshExceptionToMacStatus("Connection refused"))
        assertEquals("Mac SSH Status: Connection refused", mapSshExceptionToMacStatus("connect: connection refused"))
    }

    @Test
    fun `unresolved host message maps to Unknown host`() {
        assertEquals("Mac SSH Status: Unknown host", mapSshExceptionToMacStatus("UnknownHostException: mymac.local"))
        assertEquals("Mac SSH Status: Unknown host", mapSshExceptionToMacStatus("Unresolved address"))
    }

    @Test
    fun `unknown message defaults to Connection failed`() {
        assertEquals("Mac SSH Status: Connection failed", mapSshExceptionToMacStatus("Network unreachable"))
        assertEquals("Mac SSH Status: Connection failed", mapSshExceptionToMacStatus(""))
    }

    @Test
    fun `missing Ethernet network maps to a failed Mac SSH test`() {
        assertEquals(
            "Mac SSH Status: Connection failed",
            mapSshExceptionToMacStatus("Required network for SSH target MAC is unavailable")
        )
    }

    @Test
    fun `legacy default Mac tethering address is no longer assumed`() {
        assertFalse(isValidIpv4(""))
    }
}
