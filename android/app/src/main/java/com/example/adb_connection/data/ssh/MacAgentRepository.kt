package com.example.adb_connection.data.ssh

private const val MAC_AGENT_CLI = "\"MAC_AGENT_CLI_PATH_PLACEHOLDER\""
private const val MAC_AGENT_LAUNCH_AGENT = "MAC_AGENT_LAUNCH_AGENT_PLACEHOLDER"

class MacAgentRepository(
    private val sshRepository: SshRepository,
    private val configProvider: ScpConnectionConfigProvider
) {
    suspend fun report(): Result<String> = runCatching {
        val health = execute("$MAC_AGENT_CLI health").getOrThrow()
        val jobs = execute("$MAC_AGENT_CLI jobs").getOrThrow()
        "Health:\n$health\n\nJobs:\n$jobs"
    }

    suspend fun start(): Result<String> {
        return runCatching {
            ensureAvailable()
            report().getOrThrow()
        }
    }

    suspend fun startCapture(deviceIdPlaceholder: String, requestId: String? = null): Result<MacAgentJob> = runCatching {
        validateCaptureRequest(deviceIdPlaceholder, requestId)
        val requestArgument = requestId?.let { " --requestId $it" }.orEmpty()
        parseMacAgentJob(execute("$MAC_AGENT_CLI start --deviceIdPlaceholder $deviceIdPlaceholder --name CaptureSessionPlaceholder_Wireless$requestArgument").getOrThrow())
    }

    suspend fun startCaptureEnsuringAgent(deviceIdPlaceholder: String, requestId: String? = null): Result<MacAgentCaptureStart> = runCatching {
        validateCaptureRequest(deviceIdPlaceholder, requestId)
        val agentRestarted = ensureAvailable()
        MacAgentCaptureStart(startCapture(deviceIdPlaceholder, requestId).getOrThrow(), agentRestarted)
    }

    suspend fun captureStatus(jobId: String): Result<MacAgentJob> = runCatching {
        require(JOB_ID_REGEX.matches(jobId)) { "Invalid Mac agent job ID" }
        parseMacAgentJob(execute("$MAC_AGENT_CLI status --job $jobId").getOrThrow())
    }

    suspend fun captureJobs(): Result<List<MacAgentJob>> = runCatching {
        parseMacAgentJobs(execute("$MAC_AGENT_CLI jobs").getOrThrow())
    }

    suspend fun stopCapture(jobId: String): Result<MacAgentJob> = runCatching {
        require(JOB_ID_REGEX.matches(jobId)) { "Invalid Mac agent job ID" }
        parseMacAgentJob(execute("$MAC_AGENT_CLI stop --job $jobId").getOrThrow())
    }

    private suspend fun execute(command: String): Result<String> {
        val config = configProvider.configFor(SshTarget.MAC)
        return sshRepository.executeCommand(
            target = SshTarget.MAC,
            host = config.host,
            port = config.port,
            user = config.user,
            password = config.password,
            command = command
        ).mapCatching { result ->
            if (!result.isSuccess) {
                throw IllegalStateException(result.allOutput.ifBlank { "Mac agent command failed" })
            }
            result.stdout.trim().ifBlank { throw IllegalStateException("Mac agent returned no status") }
        }
    }

    private suspend fun ensureAvailable(): Boolean {
        val initialHealth = execute("$MAC_AGENT_CLI health")
        if (initialHealth.getOrNull().isHealthy()) return false

        execute(
            "launchctl kickstart -k gui/\$(id -u)/$MAC_AGENT_LAUNCH_AGENT && printf started"
        ).getOrThrow()
        check(execute("$MAC_AGENT_CLI health").getOrThrow().isHealthy()) {
            "Mac agent did not become healthy after restart"
        }
        return true
    }

    private fun validateCaptureRequest(deviceIdPlaceholder: String, requestId: String?) {
        require(DEVICE_ID_PLACEHOLDER_REGEX.matches(deviceIdPlaceholder)) { "Invalid MobileDevicePlaceholder DEVICE_ID_PLACEHOLDER" }
        require(requestId == null || REQUEST_ID_REGEX.matches(requestId)) { "Invalid Mac agent request ID" }
    }

    private fun String?.isHealthy(): Boolean = this?.contains(Regex("\"ok\"\\s*:\\s*true")) == true

    private companion object {
        val DEVICE_ID_PLACEHOLDER_REGEX = Regex("[A-Za-z0-9-]{1,64}")
        val JOB_ID_REGEX = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
        val REQUEST_ID_REGEX = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

data class MacAgentCaptureStart(
    val job: MacAgentJob,
    val agentRestarted: Boolean
)
