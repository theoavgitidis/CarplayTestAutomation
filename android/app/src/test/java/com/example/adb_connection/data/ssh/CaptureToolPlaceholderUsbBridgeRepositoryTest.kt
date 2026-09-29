package com.example.adb_connection.data.ssh

import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

class CaptureToolPlaceholderUsbBridgeRepositoryTest {
    private val tempDir = Files.createTempDirectory("capture_tool_placeholder-usb-bridge-test").toFile()

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `transfers selected captures sequentially and finalizes each verified USB copy`() = runTest {
        val payloads = mapOf(
            "/captures/first.capture_tool_placeholder" to "first payload".toByteArray(),
            "/captures/second.capture_tool_placeholder" to "second payload".toByteArray()
        )
        val fixture = fixture(payloads)
        val states = mutableListOf<CaptureToolPlaceholderUsbTransferState>()

        val result = fixture.repository.transfer(payloads.keys.toList(), states::add)

        assertTrue(result.isSuccess)
        assertEquals(listOf("first.capture_tool_placeholder", "second.capture_tool_placeholder"), result.getOrThrow().map { it.fileName })
        assertEquals(
            listOf(
                "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/first.capture_tool_placeholder.partial",
                "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/second.capture_tool_placeholder.partial"
            ),
            fixture.uploadPaths
        )
        assertEquals(2, fixture.commands.count { it.contains("mv -- ") })
        assertEquals(CaptureToolPlaceholderUsbTransferPhase.PREPARING, states.first().phase)
        assertEquals(CaptureToolPlaceholderUsbTransferPhase.COMPLETED, states.last().phase)
        val firstFinalizing = states.indexOfFirst { it.phase == CaptureToolPlaceholderUsbTransferPhase.FINALIZING && it.fileName == "first.capture_tool_placeholder" }
        val secondPreparing = states.indexOfFirst { it.phase == CaptureToolPlaceholderUsbTransferPhase.PREPARING && it.fileName == "second.capture_tool_placeholder" }
        assertTrue(firstFinalizing < secondPreparing)
    }

    @Test
    fun `hash mismatch removes USB partial file and reports verification phase`() = runTest {
        val fixture = fixture(mapOf("/captures/failing.capture_tool_placeholder" to "payload".toByteArray()), usbHash = "0".repeat(64))
        val states = mutableListOf<CaptureToolPlaceholderUsbTransferState>()

        val result = fixture.repository.transfer(listOf("/captures/failing.capture_tool_placeholder"), states::add)

        assertTrue(result.isFailure)
        assertEquals(CaptureToolPlaceholderUsbTransferPhase.VERIFYING_USB_COPY, states.last().phase)
        assertTrue(
            fixture.commands.any {
                it == "rm -f -- 'HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/failing.capture_tool_placeholder.partial'"
            }
        )
        assertTrue(fixture.commands.none { it.startsWith("mv -- ") })
    }

    @Test
    fun `stale USB partial is removed before upload`() = runTest {
        val fixture = fixture(mapOf("/captures/stale.capture_tool_placeholder" to "payload".toByteArray()))

        val result = fixture.repository.transfer(listOf("/captures/stale.capture_tool_placeholder"))

        assertTrue(result.isSuccess)
        val prepare = fixture.commands.indexOfFirst { it.contains("rm -f -- 'HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/stale.capture_tool_placeholder.partial'") }
        val upload = fixture.uploadPaths.indexOf("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb/CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER/stale.capture_tool_placeholder.partial")
        assertTrue(prepare >= 0)
        assertTrue(upload >= 0)
    }

    @Test
    fun `existing final USB file fails without upload or overwrite`() = runTest {
        val fixture = fixture(
            payloads = mapOf("/captures/existing.capture_tool_placeholder" to "payload".toByteArray()),
            destinationExists = true
        )
        val states = mutableListOf<CaptureToolPlaceholderUsbTransferState>()

        val result = fixture.repository.transfer(listOf("/captures/existing.capture_tool_placeholder"), states::add)

        assertTrue(result.isFailure)
        assertEquals(CaptureToolPlaceholderUsbTransferPhase.PREPARING_HEADUNIT_USB, states.last().phase)
        assertTrue(fixture.uploadPaths.isEmpty())
        assertTrue(fixture.commands.none { it.startsWith("mv -- ") })
    }

    private fun fixture(
        payloads: Map<String, ByteArray>,
        usbHash: String? = null,
        destinationExists: Boolean = false
    ): Fixture {
        val uploadPaths = mutableListOf<String>()
        val commands = mutableListOf<String>()
        val macScp = object : ScpRepository {
            override suspend fun download(
                target: SshTarget,
                remotePath: String,
                localFile: File,
                onProgress: (ScpTransferProgress) -> Unit
            ): Result<ScpTransferResult> {
                assertEquals(SshTarget.MAC, target)
                val bytes = payloads.getValue(remotePath)
                localFile.writeBytes(bytes)
                onProgress(ScpTransferProgress(bytes.size.toLong(), bytes.size.toLong()))
                return Result.success(ScpTransferResult(bytes.size.toLong(), localFile))
            }

            override suspend fun upload(
                target: SshTarget,
                localFile: File,
                remotePath: String,
                onProgress: (ScpTransferProgress) -> Unit
            ): Result<ScpTransferResult> = error("Not used")
        }
        val headunitScp = object : ScpRepository {
            override suspend fun download(
                target: SshTarget,
                remotePath: String,
                localFile: File,
                onProgress: (ScpTransferProgress) -> Unit
            ): Result<ScpTransferResult> = error("Not used")

            override suspend fun upload(
                target: SshTarget,
                localFile: File,
                remotePath: String,
                onProgress: (ScpTransferProgress) -> Unit
            ): Result<ScpTransferResult> {
                assertEquals(SshTarget.HEADUNIT, target)
                uploadPaths += remotePath
                onProgress(ScpTransferProgress(localFile.length(), localFile.length()))
                return Result.success(ScpTransferResult(localFile.length(), localFile))
            }
        }
        val headunitPayloadForCommand: (String) -> ByteArray = { command ->
            payloads.entries.first { (path, _) -> command.contains(path.substringAfterLast('/')) }.value
        }
        val ssh = object : SshRepository {
            override suspend fun executeCommand(
                host: String,
                port: Int,
                user: String,
                password: String,
                command: String
            ): Result<SshCommandResult> = error("Target-bound SSH required")

            override suspend fun executeCommand(
                target: SshTarget,
                host: String,
                port: Int,
                user: String,
                password: String,
                command: String
            ): Result<SshCommandResult> {
                val exitCode = if (
                    target == SshTarget.HEADUNIT && destinationExists && command.startsWith("if [ -e ")
                ) 1 else 0
                val stdout = when (target) {
                    SshTarget.MAC -> when {
                        command.startsWith("stat ") -> payloads.entries.first { command.contains(it.key) }.value.size.toString()
                        command.startsWith("shasum ") -> ""
                        else -> error("Unexpected Mac command: $command")
                    }
                    SshTarget.HEADUNIT -> {
                        commands += command
                        when {
                            command.startsWith("for p in ") -> "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/usb\n"
                            command.startsWith("mkdir -p ") -> ""
                            command.startsWith("if [ -e ") && destinationExists -> "Destination file already exists"
                            command.startsWith("if [ -e ") -> ""
                            command.startsWith("stat -c ") -> headunitPayloadForCommand(command).size.toString()
                            command.startsWith("sha256sum ") -> "${usbHash ?: sha256(headunitPayloadForCommand(command))}  capture.capture_tool_placeholder.partial"
                            command.startsWith("mv -- ") || command.startsWith("rm -f ") -> ""
                            else -> error("Unexpected Headunit command: $command")
                        }
                    }
                }
                return Result.success(SshCommandResult(command, exitCode, stdout, ""))
            }
        }
        val configs = ScpConnectionConfigProvider { target ->
            if (target == SshTarget.MAC) ScpConnectionConfig("mac", 22, "mac-user", "password")
            else ScpConnectionConfig("headunit", 22, "headunit-user", "password")
        }
        val macDownload = MacScpDownloadRepository(tempDir, ssh, macScp, configs, availableBytes = { Long.MAX_VALUE })
        return Fixture(CaptureToolPlaceholderUsbBridgeRepository(macDownload, headunitScp, ssh, configs), uploadPaths, commands)
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }

    private data class Fixture(
        val repository: CaptureToolPlaceholderUsbBridgeRepository,
        val uploadPaths: List<String>,
        val commands: List<String>
    )
}
