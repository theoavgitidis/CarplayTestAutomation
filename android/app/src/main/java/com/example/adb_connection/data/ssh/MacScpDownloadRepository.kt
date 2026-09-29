package com.example.adb_connection.data.ssh

import android.os.StatFs
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

private const val STORAGE_SAFETY_MARGIN_BYTES = 16L * 1024 * 1024

/** First direct data path: Mac SCP server -> Android app-private storage. */
class MacScpDownloadRepository(
    private val filesDir: File,
    private val sshRepository: SshRepository,
    private val scpRepository: ScpRepository,
    private val configProvider: ScpConnectionConfigProvider,
    private val availableBytes: () -> Long = { StatFs(filesDir.path).availableBytes }
) {
    suspend fun download(
        remotePath: String,
        jobId: String = UUID.randomUUID().toString(),
        onPhase: (MacScpDownloadPhase) -> Unit = {},
        onProgress: (ScpTransferProgress) -> Unit = {}
    ): Result<MacScpDownloadResult> {
        val transferDir = File(File(filesDir, "capture-tool-placeholder-transfer"), validJobId(jobId))
        var completed = false
        try {
            removeIncompleteDownloads()
            val remoteFileName = ScpProtocol.fileName(remotePath.substringAfterLast('/'))
            val quotedPath = ScpProtocol.shellQuote(remotePath)
            val macConfig = configProvider.configFor(SshTarget.MAC)
            onPhase(MacScpDownloadPhase.QUERYING_SOURCE)
            val sourceSize = readSourceSize(macConfig, quotedPath)
            val sourceSha256 = readSourceSha256(macConfig, quotedPath)
            val requiredBytes = sourceSize + STORAGE_SAFETY_MARGIN_BYTES
            if (requiredBytes < sourceSize || availableBytes() < requiredBytes) {
                throw IOException("Insufficient app storage: need $sourceSize bytes plus safety margin")
            }
            if (!transferDir.mkdirs() && !transferDir.isDirectory) {
                throw IOException("Could not create transfer directory")
            }
            val finalFile = File(transferDir, "$remoteFileName.ready")
            val partialFile = File(transferDir, "$remoteFileName.download")
            onProgress(ScpTransferProgress(0, sourceSize))
            onPhase(MacScpDownloadPhase.DOWNLOADING)
            val transfer = scpRepository.download(SshTarget.MAC, remotePath, partialFile, onProgress)
                .getOrElse { throw it }
            onPhase(MacScpDownloadPhase.VERIFYING)
            if (transfer.transferredBytes != sourceSize || partialFile.length() != sourceSize) {
                throw IOException("Downloaded size does not match source size")
            }
            val localSha256 = sha256(partialFile)
            if (sourceSha256 != null && !sourceSha256.equals(localSha256, ignoreCase = true)) {
                throw IOException("Downloaded SHA-256 does not match source")
            }
            if (!partialFile.renameTo(finalFile)) {
                throw IOException("Could not finalize downloaded file")
            }
            completed = true
            return Result.success(
                MacScpDownloadResult(
                    jobId = jobId,
                    file = finalFile,
                    bytes = sourceSize,
                    sha256 = localSha256
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            if (!completed) transferDir.deleteRecursively()
        }
    }

    private suspend fun readSourceSize(config: ScpConnectionConfig, quotedPath: String): Long {
        val result = sshRepository.executeCommand(
            target = SshTarget.MAC,
            host = config.host,
            port = config.port,
            user = config.user,
            password = config.password,
            command = "stat -f %z $quotedPath"
        ).getOrElse { throw IOException("Could not determine remote file size", it) }
        if (!result.isSuccess) throw IOException("Could not determine remote file size: ${result.allOutput}")
        return result.stdout.trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("Invalid remote file size: ${result.stdout.trim()}")
    }

    private suspend fun readSourceSha256(config: ScpConnectionConfig, quotedPath: String): String? {
        val result = sshRepository.executeCommand(
            target = SshTarget.MAC,
            host = config.host,
            port = config.port,
            user = config.user,
            password = config.password,
            command = "shasum -a 256 $quotedPath 2>/dev/null || true"
        ).getOrElse { return null }
        if (!result.isSuccess) return null
        return SHA256_REGEX.find(result.stdout)?.value
    }

    private fun validJobId(jobId: String): String {
        require(JOB_ID_REGEX.matches(jobId)) { "Invalid transfer job ID" }
        return jobId
    }

    private fun removeIncompleteDownloads() {
        val root = File(filesDir, "capture-tool-placeholder-transfer")
        root.listFiles()?.forEach { jobDirectory ->
            if (!jobDirectory.isDirectory) return@forEach
            jobDirectory.walkBottomUp().forEach { file ->
                if (file.isFile && file.name.endsWith(".download")) file.delete()
            }
            jobDirectory.deleteRecursivelyIfEmpty()
        }
    }

    private fun File.deleteRecursivelyIfEmpty() {
        listFiles()?.forEach { child ->
            if (child.isDirectory) child.deleteRecursivelyIfEmpty()
        }
        if (listFiles().isNullOrEmpty()) delete()
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val JOB_ID_REGEX = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")
        val SHA256_REGEX = Regex("(?i)\\b[a-f0-9]{64}\\b")
    }
}

enum class MacScpDownloadPhase { QUERYING_SOURCE, DOWNLOADING, VERIFYING }

data class MacScpDownloadResult(
    val jobId: String,
    val file: File,
    val bytes: Long,
    val sha256: String
)
