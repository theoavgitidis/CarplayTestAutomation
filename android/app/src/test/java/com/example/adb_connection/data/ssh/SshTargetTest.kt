package com.example.adb_connection.data.ssh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshTargetTest {

    @Test
    fun `Headunit SSH fails clearly when WiFi network is unavailable`() = runTest {
        var requestedTarget: SshTarget? = null
        val repository = AndroidSshRepository(networkProvider = SshNetworkProvider {
            requestedTarget = it
            null
        })

        val result = repository.executeCommand(
            target = SshTarget.HEADUNIT,
            host = "HEAD_UNIT_HOST_PLACEHOLDER",
            port = 22,
            user = "HEAD_UNIT_USER_PLACEHOLDER",
            password = "",
            command = "id"
        )

        assertTrue(result.exceptionOrNull() is SshNetworkUnavailableException)
        assertEquals(
            "Required network for SSH target HEADUNIT is unavailable",
            result.exceptionOrNull()?.message
        )
        assertEquals(SshTarget.HEADUNIT, requestedTarget)
    }

    @Test
    fun `Mac SSH fails clearly when Ethernet network is unavailable`() = runTest {
        var requestedTarget: SshTarget? = null
        val repository = AndroidSshRepository(networkProvider = SshNetworkProvider {
            requestedTarget = it
            null
        })

        val result = repository.executeCommand(
            target = SshTarget.MAC,
            host = "192.168.1.2",
            port = 22,
            user = "macuser",
            password = "",
            command = "id"
        )

        assertTrue(result.exceptionOrNull() is SshNetworkUnavailableException)
        assertEquals(
            "Required network for SSH target MAC is unavailable",
            result.exceptionOrNull()?.message
        )
        assertEquals(SshTarget.MAC, requestedTarget)
    }

}
