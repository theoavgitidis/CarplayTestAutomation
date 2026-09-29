package com.example.adb_connection.data.ssh

import android.util.Log
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream

private const val TAG = "SshRepository"
private const val CONNECT_TIMEOUT_MS = 10_000
private const val EXEC_TIMEOUT_MS = 15_000
private const val MAX_COMMAND_OUTPUT_BYTES = 256 * 1024

class AndroidSshRepository(
    private val networkProvider: SshNetworkProvider? = null
) : SshRepository {

    override suspend fun executeCommand(
        host: String,
        port: Int,
        user: String,
        password: String,
        command: String
    ): Result<SshCommandResult> = executeCommand(
        socketFactory = null,
        host = host,
        port = port,
        user = user,
        password = password,
        command = command
    )

    override suspend fun executeCommand(
        target: SshTarget,
        host: String,
        port: Int,
        user: String,
        password: String,
        command: String
    ): Result<SshCommandResult> {
        val network = networkProvider?.networkFor(target)
            ?: return Result.failure(SshNetworkUnavailableException(target))
        return executeCommand(
            socketFactory = AndroidNetworkSocketFactory(network, CONNECT_TIMEOUT_MS),
            host = host,
            port = port,
            user = user,
            password = password,
            command = command
        )
    }

    private suspend fun executeCommand(
        socketFactory: AndroidNetworkSocketFactory?,
        host: String,
        port: Int,
        user: String,
        password: String,
        command: String
    ): Result<SshCommandResult> = withContext(Dispatchers.IO) {
        var session: Session? = null
        var channel: ChannelExec? = null
        try {
            session = JSch().getSession(user, host, port).apply {
                setPassword(password)
                // Disabled: controlled dev environment, headunit WLAN treated as trusted
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password")
                socketFactory?.let(::setSocketFactory)
                connect(CONNECT_TIMEOUT_MS)
            }

            channel = (session.openChannel("exec") as ChannelExec).apply {
                setCommand(command)
                inputStream = null
            }

            val outputStream = channel.inputStream
            val errorStream = channel.errStream

            channel.connect(CONNECT_TIMEOUT_MS)

            val result = try {
                withTimeout(EXEC_TIMEOUT_MS.toLong()) {
                    // Blocking reads suspend the IO thread instead of polling available().
                    // stdout/stderr are drained concurrently so one stream filling up can't
                    // stall the other (or the remote command).
                    val stdoutJob = async { readFully(outputStream) }
                    val stderrJob = async { readFully(errorStream) }
                    // Wait for both streams to reach EOF (remote closed them).
                    val stdout = stdoutJob.await()
                    val stderr = stderrJob.await()
                    // JSch only populates exitStatus once the channel is closed.
                    // Reading it earlier returns -1 while the command is still running.
                    while (!channel.isClosed) {
                        delay(50)
                    }
                    SshCommandResult(
                        command = command,
                        exitCode = channel.exitStatus,
                        stdout = stdout.trimEnd(),
                        stderr = stderr.trimEnd()
                    )
                }
            } catch (e: TimeoutCancellationException) {
                // Force-close so the blocking reads above unblock immediately via IOException.
                channel.disconnect()
                throw IOException("SSH command '$command' timed out after ${EXEC_TIMEOUT_MS}ms", e)
            }

            Log.d(TAG, "Command '$command' exit=${result.exitCode}, output:\n${result.allOutput}")
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "SSH command '$command' failed: ${e.message}")
            Result.failure(e)
        } finally {
            channel?.disconnect()
            session?.disconnect()
        }
    }

    private fun readFully(input: InputStream): String {
        val buffer = ByteArray(4096)
        val out = StringBuilder()
        var retainedBytes = 0
        var truncated = false
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            if (read > 0 && retainedBytes < MAX_COMMAND_OUTPUT_BYTES) {
                val bytesToRetain = minOf(read, MAX_COMMAND_OUTPUT_BYTES - retainedBytes)
                out.append(String(buffer, 0, bytesToRetain))
                retainedBytes += bytesToRetain
                truncated = truncated || bytesToRetain < read
            } else if (read > 0) {
                truncated = true
            }
        }
        if (truncated) out.append("\n[output truncated at $MAX_COMMAND_OUTPUT_BYTES bytes]")
        return out.toString()
    }
}
