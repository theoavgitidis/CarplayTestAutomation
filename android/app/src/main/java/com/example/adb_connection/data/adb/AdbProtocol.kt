package com.example.adb_connection.data.adb

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Limitations: no session reuse (full handshake per command), no AUTH/RSA key support,
// no multi-stream multiplexing, no checksum verification of incoming packets.
private const val TAG = "AdbProtocol"

// ADB protocol constants
private const val A_CNXN = 0x4e584e43  // "CNXN" in little-endian
private const val A_AUTH = 0x48545541  // "AUTH" in little-endian
private const val A_OPEN = 0x4e45504f  // "OPEN"
private const val A_OKAY = 0x59414b4f  // "OKAY"
private const val A_WRTE = 0x45545257  // "WRTE"
private const val A_CLSE = 0x45534c43  // "CLSE"

private const val ADB_VERSION = 0x01000000
internal const val MAX_PAYLOAD = 4096
private const val MAX_RECEIVE_PAYLOAD = 1_048_576  // 1 MB safety limit for incoming packets
private const val HEADER_SIZE = 24
private const val HANDSHAKE_TIMEOUT_MS = 5000
private const val SHELL_READ_TIMEOUT_MS = 15000
private val NUL_TERMINATOR = 0.toChar().toString()

data class AdbPacket(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val dataLength: Int,
    val dataCrc32: Int,
    val magic: Int,
    val payload: ByteArray = ByteArray(0)
) {
    val commandName: String
        get() = when (command) {
            A_CNXN -> "CNXN"
            A_AUTH -> "AUTH"
            A_OPEN -> "OPEN"
            A_OKAY -> "OKAY"
            A_WRTE -> "WRTE"
            A_CLSE -> "CLSE"
            else -> "UNKNOWN(0x${command.toString(16)})"
        }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AdbPacket) return false
        return command == other.command && arg0 == other.arg0 && arg1 == other.arg1
    }

    override fun hashCode(): Int = command * 31 + arg0 * 17 + arg1
}

data class AdbHandshakeResult(
    val success: Boolean,
    val receivedCommand: String,
    val message: String,
    val payload: String? = null
)

data class AdbShellResult(
    val success: Boolean,
    val command: String,
    val output: String,
    val exitCode: Int? = null,
    val errorMessage: String? = null
)

// Shell service prefix length: "shell:" = 6 bytes, plus a NUL terminator = 7 bytes total.
// Any OPEN payload must fit in MAX_PAYLOAD. Reserve those bytes so callers can check.
internal const val SHELL_SERVICE_OVERHEAD = 7  // "shell:" + NUL
internal const val SHELL_COMMAND_LIMIT = MAX_PAYLOAD - SHELL_SERVICE_OVERHEAD  // 4089 bytes

object AdbProtocol {

    suspend fun performHandshake(host: String, port: Int): AdbHandshakeResult =
        withContext(Dispatchers.IO) {
            runInterruptible {
                try {
                Socket().use { socket ->
                    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                    socket.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)

                    val output = DataOutputStream(socket.getOutputStream())
                    val input = DataInputStream(socket.getInputStream())

                    // Send CNXN packet
                    val systemIdentity = "host::TraceMate$NUL_TERMINATOR".toByteArray()
                    sendPacket(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, systemIdentity)
                    Log.d(TAG, "Sent CNXN to $host:$port")

                    // Read response
                    val response = readPacket(input)
                    Log.d(TAG, "Received ${response.commandName} from $host:$port")

                    when (response.command) {
                        A_CNXN -> {
                            val payloadStr = if (response.payload.isNotEmpty()) {
                                String(response.payload).trim('\u0000')
                            } else null
                            AdbHandshakeResult(
                                success = true,
                                receivedCommand = "CNXN",
                                message = "ADB Handshake successful",
                                payload = payloadStr
                            )
                        }
                        A_AUTH -> {
                            AdbHandshakeResult(
                                success = false,
                                receivedCommand = "AUTH",
                                message = "ADB Handshake requires authentication",
                                payload = null
                            )
                        }
                        else -> {
                            AdbHandshakeResult(
                                success = false,
                                receivedCommand = response.commandName,
                                message = "ADB Handshake failed: unexpected packet ${response.commandName}",
                                payload = null
                            )
                        }
                    }
                }
                } catch (e: InterruptedException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "ADB Handshake failed: ${e.message}")
                    AdbHandshakeResult(
                        success = false,
                        receivedCommand = "",
                        message = "ADB Handshake failed: ${e.message ?: "unknown error"}",
                        payload = null
                    )
                }
            }
        }

    // readTimeoutMs=0 means no read timeout — used for long-running blocking-wait commands.
    suspend fun executeShellCommand(
        host: String,
        port: Int,
        command: String,
        readTimeoutMs: Int = SHELL_READ_TIMEOUT_MS
    ): AdbShellResult = withContext(Dispatchers.IO) {
        val wrappedLen = (wrapCommandWithExitMarker(command) + NUL_TERMINATOR).toByteArray().size
        if (wrappedLen > MAX_PAYLOAD) {
            Log.e(TAG, "ADB shell payload too large: $wrappedLen bytes (limit $MAX_PAYLOAD)")
            return@withContext AdbShellResult(
                success = false,
                command = command,
                output = "",
                errorMessage = "ADB shell payload too large: $wrappedLen bytes (limit $MAX_PAYLOAD)"
            )
        }
        // runInterruptible converts a thread interrupt (from coroutine cancellation) into
        // CancellationException, so blocking readFully() calls unblock cleanly on cancel.
        runInterruptible {
            try {
                Socket().use { socket ->
                    socket.soTimeout = HANDSHAKE_TIMEOUT_MS
                    socket.connect(InetSocketAddress(host, port), HANDSHAKE_TIMEOUT_MS)

                    val output = DataOutputStream(socket.getOutputStream())
                    val input = DataInputStream(socket.getInputStream())

                    // Step 1: CNXN handshake
                    val systemIdentity = "host::TraceMate$NUL_TERMINATOR".toByteArray()
                    sendPacket(output, A_CNXN, ADB_VERSION, MAX_PAYLOAD, systemIdentity)

                    val cnxnResponse = readPacket(input)
                    if (cnxnResponse.command != A_CNXN) {
                        val cmdName = cnxnResponse.commandName
                        return@runInterruptible AdbShellResult(
                            success = false,
                            command = command,
                            output = "",
                            errorMessage = "ADB shell:$command failed: unexpected packet $cmdName"
                        )
                    }
                    Log.d(TAG, "Shell: CNXN established")

                    // Increase timeout for shell data reading phase (0 = infinite for long-running commands)
                    socket.soTimeout = readTimeoutMs

                    // Step 2: OPEN shell stream
                    val localId = 1
                    val wrappedCommand = wrapCommandWithExitMarker(command)
                    val servicePayload = "shell:$wrappedCommand$NUL_TERMINATOR".toByteArray()
                    sendPacket(output, A_OPEN, localId, 0, servicePayload)
                    Log.d(TAG, "Shell: sent OPEN for shell:$command")

                    // Step 3: Expect OKAY from remote
                    val openResponse = readPacket(input)
                    if (openResponse.command != A_OKAY || openResponse.arg1 != localId) {
                        return@runInterruptible AdbShellResult(
                            success = false,
                            command = command,
                            output = "",
                            errorMessage = "ADB shell:$command failed: invalid OKAY after OPEN"
                        )
                    }
                    val remoteId = openResponse.arg0
                    Log.d(TAG, "Shell: got OKAY, remoteId=$remoteId")

                    // Step 4: Read WRTE packets until CLSE
                    val outputBuilder = StringBuilder()
                    while (true) {
                        val packet = readPacket(input)
                        when (packet.command) {
                            A_WRTE -> {
                                if (packet.arg0 != remoteId || packet.arg1 != localId) {
                                    return@runInterruptible AdbShellResult(
                                        success = false,
                                        command = command,
                                        output = outputBuilder.toString(),
                                        errorMessage = "ADB shell:$command failed: WRTE stream ID mismatch"
                                    )
                                }
                                // Acknowledge with OKAY
                                sendPacket(output, A_OKAY, localId, remoteId, ByteArray(0))
                                val text = String(packet.payload)
                                outputBuilder.append(text)
                                Log.d(TAG, "Shell: WRTE ${packet.payload.size} bytes")
                            }
                            A_CLSE -> {
                                if (packet.arg0 != remoteId || packet.arg1 != localId) {
                                    return@runInterruptible AdbShellResult(
                                        success = false,
                                        command = command,
                                        output = outputBuilder.toString(),
                                        errorMessage = "ADB shell:$command failed: CLSE stream ID mismatch"
                                    )
                                }
                                // Send CLSE back
                                sendPacket(output, A_CLSE, localId, remoteId, ByteArray(0))
                                Log.d(TAG, "Shell: stream closed")
                                break
                            }
                            else -> {
                                Log.w(TAG, "Shell: unexpected packet ${packet.commandName}")
                                break
                            }
                        }
                    }

                    parseShellOutput(command, outputBuilder.toString())
                }
            } catch (e: InterruptedException) {
                // Let runInterruptible convert this to CancellationException.
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "ADB shell:$command failed: ${e.message}")
                AdbShellResult(
                    success = false,
                    command = command,
                    output = "",
                    errorMessage = "ADB shell:$command failed: ${e.message ?: "unknown error"}"
                )
            }
        }
    }

    /**
     * Writes [content] to [remotePath] on the device using a series of short `printf …
     * >> file` ADB shell commands. Each chunk is small enough to stay well under the
     * 4096-byte ADB payload limit once hex-escaped and wrapped in the shell service
     * prefix. Returns true on success, false if any chunk write fails.
     *
     * Uses `printf '%b'` with octal escapes for every byte so no character in the
     * content can break the shell quoting.
     */
    suspend fun writeRemoteFile(
        host: String,
        port: Int,
        remotePath: String,
        content: String
    ): AdbShellResult {
        val bytes = content.toByteArray(Charsets.UTF_8)
        // Each byte becomes \NNN (4 chars). printf '%b' '...' >> path with overhead.
        // Use 200-byte chunks: 200 * 4 = 800 octal chars, well under limit.
        val chunkSize = 200

        // Truncate/create the file first.
        val truncateResult = executeShellCommand(host, port, "> '$remotePath'")
        if (!truncateResult.success) {
            Log.e(TAG, "writeRemoteFile: truncate failed for $remotePath: ${truncateResult.errorMessage}")
            return AdbShellResult(
                success = false,
                command = "writeRemoteFile",
                output = "",
                errorMessage = "Failed to create $remotePath: ${truncateResult.errorMessage}"
            )
        }

        var offset = 0
        while (offset < bytes.size) {
            val end = minOf(offset + chunkSize, bytes.size)
            val octal = buildString {
                for (i in offset until end) {
                    val b = bytes[i].toInt() and 0xFF
                    append("\\")
                    append(b.toString(8).padStart(3, '0'))
                }
            }
            val cmd = "printf '%b' '$octal' >> '$remotePath'"
            val result = executeShellCommand(host, port, cmd)
            if (!result.success) {
                Log.e(TAG, "writeRemoteFile: chunk write failed at offset $offset: ${result.errorMessage}")
                return AdbShellResult(
                    success = false,
                    command = "writeRemoteFile",
                    output = "",
                    errorMessage = "Failed to write chunk at offset $offset: ${result.errorMessage}"
                )
            }
            offset = end
        }

        return AdbShellResult(success = true, command = "writeRemoteFile", output = "")
    }

    private fun sendPacket(output: DataOutputStream, command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        val checksum = payload.fold(0) { acc, b -> acc + (b.toInt() and 0xFF) }

        header.putInt(command)
        header.putInt(arg0)
        header.putInt(arg1)
        header.putInt(payload.size)
        header.putInt(checksum)
        header.putInt(command xor 0xFFFFFFFF.toInt())

        output.write(header.array())
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readPacket(input: DataInputStream): AdbPacket {
        val headerBytes = ByteArray(HEADER_SIZE)
        input.readFully(headerBytes)

        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.getInt()
        val arg0 = header.getInt()
        val arg1 = header.getInt()
        val dataLength = header.getInt()
        val dataCrc32 = header.getInt()
        val magic = header.getInt()

        if (magic != (command xor 0xFFFFFFFF.toInt())) {
            throw java.io.IOException("ADB packet has invalid magic")
        }
        if (dataLength < 0) {
            throw java.io.IOException("ADB packet has negative data length: $dataLength")
        }

        val payload = if (dataLength > 0) {
            if (dataLength > MAX_RECEIVE_PAYLOAD) {
                throw java.io.IOException("ADB packet data length too large: $dataLength bytes")
            }
            ByteArray(dataLength).also { input.readFully(it) }
        } else {
            ByteArray(0)
        }

        return AdbPacket(command, arg0, arg1, dataLength, dataCrc32, magic, payload)
    }
}
