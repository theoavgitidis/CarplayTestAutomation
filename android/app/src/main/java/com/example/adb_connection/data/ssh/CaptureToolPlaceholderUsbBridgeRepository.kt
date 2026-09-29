package com.example.adb_connection.data.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

/** Bridges CAPTURE_TOOL_PLACEHOLDER captures through Android; it never establishes a Mac-to-Headunit transfer. */
interface CaptureToolPlaceholderTransferRunner {
    suspend fun transfer(
        remotePaths: List<String>,
        onState: (CaptureToolPlaceholderUsbTransferState) -> Unit = {}
    ): Result<List<CaptureToolPlaceholderUsbTransferResult>>
}

class CaptureToolPlaceholderUsbBridgeRepository(
    private val macDownloadRepository: MacScpDownloadRepository,
    private val headunitScpRepository: ScpRepository,
    private val sshRepository: SshRepository,
    private val configProvider: ScpConnectionConfigProvider
) : CaptureToolPlaceholderTransferRunner {
    private companion object {
        const val USB_MOUNT_ROOT = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER"
        const val CAPTURE_TOOL_PLACEHOLDER_EXPORT_DIRECTORY = "CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER"
    }
    override suspend fun transfer(
        remotePaths: List<String>,
        onState: (CaptureToolPlaceholderUsbTransferState) -> Unit
    ): Result<List<CaptureToolPlaceholderUsbTransferResult>> {
        if (remotePaths.isEmpty()) return Result.failure(IllegalArgumentException("No CAPTURE_TOOL_PLACEHOLDER captures selected"))
        val results = mutableListOf<CaptureToolPlaceholderUsbTransferResult>()
        var currentPhase = CaptureToolPlaceholderUsbTransferPhase.PREPARING
        var currentFile: String? = null
        try {
            remotePaths.forEach { remotePath ->
                val fileName = ScpProtocol.fileName(remotePath.substringAfterLast('/'))
                currentFile = fileName
                currentPhase = CaptureToolPlaceholderUsbTransferPhase.PREPARING
                onState(CaptureToolPlaceholderUsbTransferState(currentPhase, fileName))
                val downloaded = macDownloadRepository.download(
                    remotePath = remotePath,
                    jobId = UUID.randomUUID().toString(),
                    onPhase = { phase ->
                        val bridgePhase = when (phase) {
                            MacScpDownloadPhase.QUERYING_SOURCE -> CaptureToolPlaceholderUsbTransferPhase.QUERYING_MAC_FILE
                            MacScpDownloadPhase.DOWNLOADING -> CaptureToolPlaceholderUsbTransferPhase.DOWNLOADING_FROM_MAC
                            MacScpDownloadPhase.VERIFYING -> CaptureToolPlaceholderUsbTransferPhase.VERIFYING_ANDROID_COPY
                        }
                        currentPhase = bridgePhase
                        onState(CaptureToolPlaceholderUsbTransferState(bridgePhase, fileName))
                    },
                    onProgress = { progress ->
                        currentPhase = CaptureToolPlaceholderUsbTransferPhase.DOWNLOADING_FROM_MAC
                        onState(CaptureToolPlaceholderUsbTransferState(CaptureToolPlaceholderUsbTransferPhase.DOWNLOADING_FROM_MAC, fileName, progress))
                    }
                ).getOrElse { throw PhaseException(currentPhase, it) }

                val headunitConfig = configProvider.configFor(SshTarget.HEADUNIT)
                currentPhase = CaptureToolPlaceholderUsbTransferPhase.PREPARING_HEADUNIT_USB
                onState(CaptureToolPlaceholderUsbTransferState(currentPhase, fileName))
                val destinationDir = prepareUsbDestination(headunitConfig)
                val remoteFinal = "$destinationDir/$fileName"
                val remotePartial = "$remoteFinal.partial"
                try {
                    prepareRemoteFiles(headunitConfig, remoteFinal, remotePartial)
                    currentPhase = CaptureToolPlaceholderUsbTransferPhase.UPLOADING_TO_HEADUNIT
                    onState(CaptureToolPlaceholderUsbTransferState(currentPhase, fileName))
                    val uploaded = headunitScpRepository.upload(
                        target = SshTarget.HEADUNIT,
                        localFile = downloaded.file,
                        remotePath = remotePartial
                    ) { progress ->
                        currentPhase = CaptureToolPlaceholderUsbTransferPhase.UPLOADING_TO_HEADUNIT
                        onState(CaptureToolPlaceholderUsbTransferState(CaptureToolPlaceholderUsbTransferPhase.UPLOADING_TO_HEADUNIT, fileName, progress))
                    }.getOrElse { throw PhaseException(CaptureToolPlaceholderUsbTransferPhase.UPLOADING_TO_HEADUNIT, it) }
                    if (uploaded.transferredBytes != downloaded.bytes) {
                        throw PhaseException(CaptureToolPlaceholderUsbTransferPhase.UPLOADING_TO_HEADUNIT, IOException("Uploaded byte count differs from verified Android copy"))
                    }

                    currentPhase = CaptureToolPlaceholderUsbTransferPhase.VERIFYING_USB_COPY
                    onState(CaptureToolPlaceholderUsbTransferState(currentPhase, fileName))
                    verifyUsbCopy(headunitConfig, remotePartial, downloaded)
                    currentPhase = CaptureToolPlaceholderUsbTransferPhase.FINALIZING
                    onState(CaptureToolPlaceholderUsbTransferState(currentPhase, fileName))
                    finalizeUsbCopy(headunitConfig, remotePartial, remoteFinal)
                    results += CaptureToolPlaceholderUsbTransferResult(fileName, remoteFinal, downloaded.bytes, downloaded.sha256)
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { cleanupPartial(headunitConfig, remotePartial) }
                    throw e
                } catch (e: Exception) {
                    cleanupPartial(headunitConfig, remotePartial)
                    throw e
                }
            }
            onState(CaptureToolPlaceholderUsbTransferState(CaptureToolPlaceholderUsbTransferPhase.COMPLETED, null))
            return Result.success(results)
        } catch (e: CancellationException) {
            onState(CaptureToolPlaceholderUsbTransferState(CaptureToolPlaceholderUsbTransferPhase.CANCELLED, currentFile, error = "Transfer cancelled"))
            throw e
        } catch (e: PhaseException) {
            onState(CaptureToolPlaceholderUsbTransferState(e.phase, currentFile, error = e.cause?.message ?: "Transfer failed"))
            return Result.failure(e)
        } catch (e: Exception) {
            onState(CaptureToolPlaceholderUsbTransferState(currentPhase, currentFile, error = e.message ?: "Transfer failed"))
            return Result.failure(e)
        }
    }

    private suspend fun prepareUsbDestination(config: ScpConnectionConfig): String {
        val mount = runHeadunitCommand(
            config,
            "for p in $USB_MOUNT_ROOT/*; do [ -d \"\$p\" ] || continue; probe=\"\$p/.tracemate_write_probe_\$\$\"; if (umask 077; : > \"\$probe\") 2>/dev/null; then rm -f \"\$probe\"; printf '%s\\n' \"\$p\"; break; fi; done"
        ).stdout.trim()
        if (mount.isBlank()) throw PhaseException(CaptureToolPlaceholderUsbTransferPhase.PREPARING_HEADUNIT_USB, IOException("No writable USB stick found"))
        val destination = "$mount/$CAPTURE_TOOL_PLACEHOLDER_EXPORT_DIRECTORY"
        runHeadunitCommand(config, "mkdir -p -- ${ScpProtocol.shellQuote(destination)}")
        return destination
    }

    private suspend fun verifyUsbCopy(config: ScpConnectionConfig, remotePartial: String, downloaded: MacScpDownloadResult) {
        val quoted = ScpProtocol.shellQuote(remotePartial)
        val size = runHeadunitCommand(config, "stat -c %s -- $quoted").stdout.trim().toLongOrNull()
        if (size != downloaded.bytes) throw PhaseException(CaptureToolPlaceholderUsbTransferPhase.VERIFYING_USB_COPY, IOException("USB copy size does not match Android copy"))
        val hash = runHeadunitCommand(config, "sha256sum -- $quoted").stdout.trim().substringBefore(' ')
        if (!hash.equals(downloaded.sha256, ignoreCase = true)) {
            throw PhaseException(CaptureToolPlaceholderUsbTransferPhase.VERIFYING_USB_COPY, IOException("USB copy SHA-256 does not match Android copy"))
        }
    }

    private suspend fun prepareRemoteFiles(config: ScpConnectionConfig, remoteFinal: String, remotePartial: String) {
        val final = ScpProtocol.shellQuote(remoteFinal)
        val partial = ScpProtocol.shellQuote(remotePartial)
        runHeadunitCommand(config, "if [ -e $final ]; then echo 'Destination file already exists' >&2; exit 1; fi; rm -f -- $partial")
    }

    private suspend fun finalizeUsbCopy(config: ScpConnectionConfig, remotePartial: String, remoteFinal: String) {
        val partial = ScpProtocol.shellQuote(remotePartial)
        val final = ScpProtocol.shellQuote(remoteFinal)
        runHeadunitCommand(config, "if [ -e $final ]; then echo 'Destination file already exists' >&2; exit 1; fi; mv -- $partial $final; test -f $final; test ! -e $partial")
    }

    private suspend fun cleanupPartial(config: ScpConnectionConfig, remotePartial: String) {
        try {
            runHeadunitCommand(config, "rm -f -- ${ScpProtocol.shellQuote(remotePartial)}")
        } catch (_: Exception) {
            // Preserve the transfer failure; cleanup is best effort.
        }
    }

    private suspend fun runHeadunitCommand(config: ScpConnectionConfig, command: String): SshCommandResult {
        val result = sshRepository.executeCommand(
            target = SshTarget.HEADUNIT,
            host = config.host,
            port = config.port,
            user = config.user,
            password = config.password,
            command = command
        ).getOrElse { throw IOException("Headunit command failed", it) }
        if (!result.isSuccess) throw IOException("Headunit command failed: ${result.allOutput}")
        return result
    }
}

enum class CaptureToolPlaceholderUsbTransferPhase {
    PREPARING,
    QUERYING_MAC_FILE,
    DOWNLOADING_FROM_MAC,
    VERIFYING_ANDROID_COPY,
    PREPARING_HEADUNIT_USB,
    UPLOADING_TO_HEADUNIT,
    VERIFYING_USB_COPY,
    FINALIZING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class CaptureToolPlaceholderUsbTransferState(
    val phase: CaptureToolPlaceholderUsbTransferPhase,
    val fileName: String?,
    val progress: ScpTransferProgress? = null,
    val error: String? = null
)

data class CaptureToolPlaceholderUsbTransferResult(val fileName: String, val usbPath: String, val bytes: Long, val sha256: String)

private class PhaseException(val phase: CaptureToolPlaceholderUsbTransferPhase, cause: Throwable) : IOException(cause.message, cause)
