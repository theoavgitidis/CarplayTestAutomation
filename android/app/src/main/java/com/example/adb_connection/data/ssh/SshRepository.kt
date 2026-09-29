package com.example.adb_connection.data.ssh

interface SshRepository {
    /**
     * Opens an SSH session, executes [command] as [user]@[host]:[port] with [password],
     * reads stdout + stderr, and returns a structured result including exit code.
     */
    suspend fun executeCommand(
        host: String,
        port: Int,
        user: String,
        password: String,
        command: String
    ): Result<SshCommandResult>

    /**
     * Executes through the network assigned to [target]. Implementations must fail rather than
     * falling back to Android's default route when the target network is unavailable.
     */
    suspend fun executeCommand(
        target: SshTarget,
        host: String,
        port: Int,
        user: String,
        password: String,
        command: String
    ): Result<SshCommandResult> = executeCommand(host, port, user, password, command)
}
