package com.example.adb_connection.data.ssh

data class SshCommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    val isSuccess: Boolean get() = exitCode == 0

    val allOutput: String get() = buildString {
        if (stdout.isNotBlank()) append(stdout)
        if (stderr.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append(stderr)
        }
    }
}
