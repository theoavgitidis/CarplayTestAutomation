package com.example.adb_connection.data.ssh

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class MacScpDownloadRepositoryTest {
    private val tempDir = Files.createTempDirectory("mac-scp-download-test").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `successful download is finalized only after size and checksum verification`() = runTest {
        val payload = "binary\u0000ats payload".toByteArray()
        val repository = repository(payload = payload, sourceHash = sha256(payload))

        val result = repository.download("/tmp/capture.capture_tool_placeholder", jobId = "job-1")

        assertTrue(result.isSuccess)
        val file = result.getOrThrow().file
        assertEquals("capture.capture_tool_placeholder.ready", file.name)
        assertTrue(file.isFile)
        assertTrue(file.readBytes().contentEquals(payload))
        assertFalse(File(file.parentFile, "capture.capture_tool_placeholder.download").exists())
    }

    @Test
    fun `failed SCP download removes partial file and job directory`() = runTest {
        val repository = repository(payload = "partial".toByteArray(), scpFailure = IOException("link lost"))

        val result = repository.download("/tmp/capture.capture_tool_placeholder", jobId = "job-2")

        assertTrue(result.isFailure)
        assertFalse(File(tempDir, "capture-tool-placeholder-transfer/job-2").exists())
    }

    @Test
    fun `checksum mismatch removes downloaded data instead of marking it complete`() = runTest {
        val repository = repository(payload = "payload".toByteArray(), sourceHash = "0".repeat(64))

        val result = repository.download("/tmp/capture.capture_tool_placeholder", jobId = "job-3")

        assertTrue(result.isFailure)
        assertFalse(File(tempDir, "capture-tool-placeholder-transfer/job-3").exists())
    }

    @Test
    fun `insufficient space prevents SCP download and leaves no directory`() = runTest {
        val repository = repository(payload = "payload".toByteArray(), availableBytes = 1)

        val result = repository.download("/tmp/capture.capture_tool_placeholder", jobId = "job-4")

        assertTrue(result.isFailure)
        assertFalse(File(tempDir, "capture-tool-placeholder-transfer/job-4").exists())
    }

    @Test
    fun `stale download from a prior failed job is removed before a new download`() = runTest {
        val staleDirectory = File(tempDir, "capture-tool-placeholder-transfer/failed-job").apply { mkdirs() }
        File(staleDirectory, "capture.capture_tool_placeholder.download").writeText("incomplete")
        val repository = repository(payload = "payload".toByteArray())

        val result = repository.download("/tmp/capture.capture_tool_placeholder", jobId = "job-5")

        assertTrue(result.isSuccess)
        assertFalse(staleDirectory.exists())
    }

    @Test
    fun `storage safety margin prevents download before SCP begins`() = runTest {
        val payload = "payload".toByteArray()
        val repository = repository(payload = payload, availableBytes = payload.size.toLong() + 1024)

        val result = repository.download("/tmp/capture.capture_tool_placeholder", jobId = "job-6")

        assertTrue(result.isFailure)
        assertFalse(File(tempDir, "capture-tool-placeholder-transfer/job-6").exists())
    }

    private fun repository(
        payload: ByteArray,
        sourceHash: String? = null,
        scpFailure: Exception? = null,
        availableBytes: Long = Long.MAX_VALUE
    ): MacScpDownloadRepository = MacScpDownloadRepository(
        filesDir = tempDir,
        sshRepository = object : SshRepository {
            override suspend fun executeCommand(
                host: String, port: Int, user: String, password: String, command: String
            ): Result<SshCommandResult> = Result.failure(AssertionError("Target-bound SSH required"))

            override suspend fun executeCommand(
                target: SshTarget, host: String, port: Int, user: String, password: String, command: String
            ): Result<SshCommandResult> {
                assertEquals(SshTarget.MAC, target)
                val stdout = when {
                    command.startsWith("stat ") -> payload.size.toString()
                    command.startsWith("shasum ") -> sourceHash?.let { "$it  capture.capture_tool_placeholder" } ?: ""
                    else -> ""
                }
                return Result.success(SshCommandResult(command, 0, stdout, ""))
            }
        },
        scpRepository = object : ScpRepository {
            override suspend fun download(
                target: SshTarget, remotePath: String, localFile: File, onProgress: (ScpTransferProgress) -> Unit
            ): Result<ScpTransferResult> {
                assertEquals(SshTarget.MAC, target)
                localFile.writeBytes(payload)
                onProgress(ScpTransferProgress(payload.size.toLong(), payload.size.toLong()))
                return scpFailure?.let { Result.failure(it) }
                    ?: Result.success(ScpTransferResult(payload.size.toLong(), localFile))
            }

            override suspend fun upload(
                target: SshTarget, localFile: File, remotePath: String, onProgress: (ScpTransferProgress) -> Unit
            ): Result<ScpTransferResult> = error("Not used")
        },
        configProvider = ScpConnectionConfigProvider {
            ScpConnectionConfig("192.168.1.2", 22, "mac", "password")
        },
        availableBytes = { availableBytes }
    )

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
