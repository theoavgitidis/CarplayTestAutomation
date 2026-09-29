package com.example.adb_connection.data.ssh

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MacAgentRepositoryTest {

    @Test
    fun `healthy agent is reported without restarting its launch agent`() = runTest {
        val fixture = fixture(healthResponses = listOf(healthyStatus, healthyStatus))

        val result = fixture.repository.start()

        assertTrue(result.getOrThrow().contains(healthyStatus))
        assertEquals(listOf(agentHealthCommand, agentHealthCommand, agentJobsCommand), fixture.commands)
    }

    @Test
    fun `unhealthy agent is kickstarted then checked again`() = runTest {
        val fixture = fixture(healthResponses = listOf("", healthyStatus, healthyStatus))

        val result = fixture.repository.start()

        assertTrue(result.getOrThrow().contains(healthyStatus))
        assertEquals(
            listOf(
                agentHealthCommand,
                agentKickstartCommand,
                agentHealthCommand,
                agentHealthCommand,
                agentJobsCommand
            ),
            fixture.commands
        )
    }

    @Test
    fun `agent commands use the Mac target`() = runTest {
        val fixture = fixture(healthResponses = listOf(healthyStatus))

        assertTrue(fixture.repository.report().isSuccess)
        assertEquals(listOf(SshTarget.MAC, SshTarget.MAC), fixture.targets)
    }

    @Test
    fun `start capture validates DEVICE_ID_PLACEHOLDER and parses the agent job`() = runTest {
        val fixture = fixture(healthResponses = emptyList())

        val job = fixture.repository.startCapture("PLACEHOLDER-MOBILE-DEVICE-ID").getOrThrow()

        assertEquals("123e4567-e89b-12d3-a456-426614174000", job.jobId)
        assertEquals("RUNNING", job.state)
        assertEquals("ACTIVE", job.activityState)
        assertEquals(42L, job.captureBytes)
        assertEquals(
            "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\" " +
                "start --deviceIdPlaceholder PLACEHOLDER-MOBILE-DEVICE-ID --name CaptureSessionPlaceholder_Wireless",
            fixture.commands.single()
        )
    }

    @Test
    fun `capture start restarts an unhealthy agent once before submitting the capture`() = runTest {
        val fixture = fixture(healthResponses = listOf("", healthyStatus))

        val start = fixture.repository.startCaptureEnsuringAgent("PLACEHOLDER-MOBILE-DEVICE-ID").getOrThrow()

        assertTrue(start.agentRestarted)
        assertEquals("123e4567-e89b-12d3-a456-426614174000", start.job.jobId)
        assertEquals(
            listOf(
                agentHealthCommand,
                agentKickstartCommand,
                agentHealthCommand,
                "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\" " +
                    "start --deviceIdPlaceholder PLACEHOLDER-MOBILE-DEVICE-ID --name CaptureSessionPlaceholder_Wireless"
            ),
            fixture.commands
        )
    }

    @Test
    fun `capture start does not restart a healthy agent`() = runTest {
        val fixture = fixture(healthResponses = listOf(healthyStatus))

        val start = fixture.repository.startCaptureEnsuringAgent("PLACEHOLDER-MOBILE-DEVICE-ID").getOrThrow()

        assertTrue(!start.agentRestarted)
        assertEquals(
            listOf(
                agentHealthCommand,
                "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\" " +
                    "start --deviceIdPlaceholder PLACEHOLDER-MOBILE-DEVICE-ID --name CaptureSessionPlaceholder_Wireless"
            ),
            fixture.commands
        )
    }

    @Test
    fun `capture recovery rejects unsafe input before checking or restarting the agent`() = runTest {
        val fixture = fixture(healthResponses = emptyList())

        val result = fixture.repository.startCaptureEnsuringAgent("bad;deviceIdPlaceholder")

        assertTrue(result.isFailure)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `start capture forwards a validated idempotency request ID`() = runTest {
        val fixture = fixture(healthResponses = emptyList())

        fixture.repository.startCapture("PLACEHOLDER-MOBILE-DEVICE-ID", requestId = "capture_request-1").getOrThrow()

        assertEquals(
            "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\" " +
                "start --deviceIdPlaceholder PLACEHOLDER-MOBILE-DEVICE-ID --name CaptureSessionPlaceholder_Wireless --requestId capture_request-1",
            fixture.commands.single()
        )
    }

    @Test
    fun `start capture rejects unsafe idempotency request ID before SSH`() = runTest {
        val fixture = fixture(healthResponses = emptyList())

        val result = fixture.repository.startCapture("PLACEHOLDER-MOBILE-DEVICE-ID", requestId = "bad;request")

        assertTrue(result.isFailure)
        assertTrue(fixture.commands.isEmpty())
    }

    @Test
    fun `capture status rejects an invalid job ID before SSH`() = runTest {
        val fixture = fixture(healthResponses = emptyList())

        val result = fixture.repository.captureStatus("not-a-job-id")

        assertTrue(result.isFailure)
        assertTrue(fixture.commands.isEmpty())
    }

    private fun fixture(healthResponses: List<String>): Fixture {
        val commands = mutableListOf<String>()
        val targets = mutableListOf<SshTarget>()
        var healthIndex = 0
        val ssh = object : SshRepository {
            override suspend fun executeCommand(
                host: String, port: Int, user: String, password: String, command: String
            ): Result<SshCommandResult> = error("Target-bound SSH required")

            override suspend fun executeCommand(
                target: SshTarget, host: String, port: Int, user: String, password: String, command: String
            ): Result<SshCommandResult> {
                targets += target
                commands += command
                val stdout = when (command) {
                    agentHealthCommand -> healthResponses[healthIndex++]
                    agentJobsCommand -> "[]"
                    else -> if (command.contains(" start --deviceIdPlaceholder ") || command.contains(" status --job ") || command.contains(" stop --job ")) agentJob else "started"
                }
                return Result.success(SshCommandResult(command, 0, stdout, ""))
            }
        }
        return Fixture(
            MacAgentRepository(ssh) { ScpConnectionConfig("192.168.1.2", 22, "mac", "password") },
            commands,
            targets
        )
    }

    private data class Fixture(
        val repository: MacAgentRepository,
        val commands: List<String>,
        val targets: List<SshTarget>
    )

    private companion object {
        const val agentHealthCommand = "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\" health"
        const val agentJobsCommand = "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\" jobs"
        const val agentKickstartCommand = "launchctl kickstart -k gui/\$(id -u)/MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER && printf started"
        const val healthyStatus = "{\n  \"ok\" : true\n}"
        const val agentJob = """{
            "ok": true,
            "result": {
                "jobId": "123e4567-e89b-12d3-a456-426614174000",
                "outputPath": "/tmp/capture.capture_tool_placeholder",
                "state": "RUNNING",
                "activity": { "state": "ACTIVE", "captureBytes": 42, "observedLiveEvents": 3 },
                "lastError": null
            }
        }"""
    }
}
