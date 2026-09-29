package com.example.adb_connection.data.ssh

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val SCP_CONNECT_TIMEOUT_MS = 10_000
private const val SCP_TRANSFER_STALL_TIMEOUT_MS = 30_000L

/** JSch exec-channel SCP implementation; it intentionally does not use SFTP. */
class AndroidScpRepository(
    private val networkProvider: SshNetworkProvider,
    private val configProvider: ScpConnectionConfigProvider
) : ScpRepository {
    override suspend fun download(
        target: SshTarget,
        remotePath: String,
        localFile: File,
        onProgress: (ScpTransferProgress) -> Unit
    ): Result<ScpTransferResult> = transfer(target, remotePath, localFile, false, onProgress)

    override suspend fun upload(
        target: SshTarget,
        localFile: File,
        remotePath: String,
        onProgress: (ScpTransferProgress) -> Unit
    ): Result<ScpTransferResult> = transfer(target, remotePath, localFile, true, onProgress)

    private suspend fun transfer(
        target: SshTarget,
        remotePath: String,
        localFile: File,
        upload: Boolean,
        onProgress: (ScpTransferProgress) -> Unit
    ): Result<ScpTransferResult> = withContext(Dispatchers.IO) {
        val network = networkProvider.networkFor(target)
            ?: return@withContext Result.failure(SshNetworkUnavailableException(target))
        var session: Session? = null
        var channel: ChannelExec? = null
        var cancellationHandle: kotlinx.coroutines.DisposableHandle? = null
        try {
            if (upload && (!localFile.isFile || !localFile.canRead())) {
                throw IOException("Local file is not readable: ${localFile.path}")
            }
            val config = configProvider.configFor(target)
            session = JSch().getSession(config.user, config.host, config.port).apply {
                setPassword(config.password)
                setConfig("StrictHostKeyChecking", "no")
                setConfig("PreferredAuthentications", "password")
                setSocketFactory(AndroidNetworkSocketFactory(network, SCP_CONNECT_TIMEOUT_MS))
                connect(SCP_CONNECT_TIMEOUT_MS)
                // This governs inactivity while reading file bytes, independently of connect().
                setTimeout(SCP_TRANSFER_STALL_TIMEOUT_MS.toInt())
            }
            channel = (session.openChannel("exec") as ChannelExec).apply {
                setCommand("scp ${if (upload) "-t" else "-f"} ${ScpProtocol.shellQuote(remotePath)}")
            }
            // JSch stream reads are blocking. Disconnecting both layers on coroutine cancellation
            // wakes those reads so finally can run and the bridge can remove temporary files.
            cancellationHandle = currentCoroutineContext()[Job]?.invokeOnCompletion {
                channel?.disconnect()
                session?.disconnect()
            }
            val input = channel.inputStream
            val output = channel.outputStream
            channel.connect(SCP_CONNECT_TIMEOUT_MS)
            val result = if (upload) {
                uploadFile(input, output, channel, localFile, remotePath, onProgress)
            } else {
                downloadFile(input, output, channel, localFile, onProgress)
            }
            Result.success(result)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            cancellationHandle?.dispose()
            channel?.disconnect()
            session?.disconnect()
        }
    }

    private suspend fun downloadFile(
        input: InputStream,
        output: OutputStream,
        channel: ChannelExec,
        localFile: File,
        onProgress: (ScpTransferProgress) -> Unit
    ): ScpTransferResult {
        output.write(0)
        output.flush()
        val header = ScpProtocol.readFileHeader(input)
        output.write(0)
        output.flush()
        var transferred = 0L
        localFile.outputStream().use { fileOutput ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (transferred < header.size) {
                val maxRead = minOf(buffer.size.toLong(), header.size - transferred).toInt()
                val count = input.read(buffer, 0, maxRead)
                if (count == -1) throw IOException("SCP connection closed before file transfer completed")
                fileOutput.write(buffer, 0, count)
                transferred += count
                onProgress(ScpTransferProgress(transferred, header.size))
            }
        }
        ScpProtocol.readAck(input)
        output.write(0)
        output.flush()
        return ScpTransferResult(transferred, localFile)
    }

    private suspend fun uploadFile(
        input: InputStream,
        output: OutputStream,
        channel: ChannelExec,
        localFile: File,
        remotePath: String,
        onProgress: (ScpTransferProgress) -> Unit
    ): ScpTransferResult {
        ScpProtocol.readAck(input)
        val total = localFile.length()
        output.write(ScpProtocol.header(total, ScpProtocol.fileName(remotePath.substringAfterLast('/'))))
        output.flush()
        ScpProtocol.readAck(input)
        var transferred = 0L
        localFile.inputStream().use { fileInput ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = fileInput.read(buffer)
                if (count == -1) break
                output.write(buffer, 0, count)
                transferred += count
                onProgress(ScpTransferProgress(transferred, total))
            }
        }
        output.write(0)
        output.flush()
        ScpProtocol.readAck(input)
        return ScpTransferResult(transferred, localFile)
    }

}
