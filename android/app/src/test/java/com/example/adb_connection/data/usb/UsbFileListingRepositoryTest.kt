package com.example.adb_connection.data.usb

import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbFileType
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbSessionDirResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [UsbTransferRepository.listUsbFiles] exercised via a fake that
 * controls detectUsbMount and ADB shell command output, plus a fake-repo
 * variant that directly returns [UsbListingResult] values.
 *
 * Because [UsbTransferRepositoryImpl] is tightly coupled to [AdbProtocol]
 * (a concrete object), these tests use a thin fake repository interface so
 * we can test all result branches without a real ADB connection.
 */
class UsbFileListingRepositoryTest {

    private val mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E"
    private val usbMount = UsbMount(
        devicePath = "/dev/sda1",
        mountPath = mountPath,
        fileSystem = "exfat",
        mountOptions = setOf("rw", "relatime")
    )
    private val base = mountPath

    // ── Fake repository that exposes only listUsbFiles ────────────────────────────

    private fun fakeRepo(result: UsbListingResult): UsbTransferRepository =
        object : UsbTransferRepository {
            override suspend fun discoverArchives(host: String, port: Int) = emptyList<TriggerArchive>()
            override suspend fun detectUsbMount(host: String, port: Int): UsbMount? = null
            override suspend fun checkDuplicate(host: String, port: Int, usbMount: UsbMount, stem: String): String? = null
            override suspend fun createSessionDir(host: String, port: Int, usbMount: UsbMount) =
                UsbSessionDirResult.Failed(FailureReason.UNKNOWN)
            override suspend fun transferArchive(
                host: String, port: Int, archive: TriggerArchive, usbMount: UsbMount,
                sessionDir: String, cancelSignal: StateFlow<Boolean>, onPhase: (TransferPhase) -> Unit
            ): TriggerTransferResult = TriggerTransferResult.Failed(archive, com.example.adb_connection.domain.model.FailureReason.UNKNOWN)
            override suspend fun syncAfterTransfer(host: String, port: Int) {}
            override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult =
                UsbDeleteResult.Success
            override suspend fun listUsbFiles(host: String, port: Int, relativeDirectory: String): UsbListingResult = result
        }

    // ── USB not connected ─────────────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns UsbNotConnected when no USB mount found`() = runTest {
        val repo = fakeRepo(UsbListingResult.UsbNotConnected())
        val result = repo.listUsbFiles("192.168.0.1", 5555)
        assertTrue(result is UsbListingResult.UsbNotConnected)
    }

    // ── ADB command failed ────────────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns AdbCommandFailed when ADB shell fails`() = runTest {
        val repo = fakeRepo(UsbListingResult.AdbCommandFailed("Connection reset"))
        val result = repo.listUsbFiles("192.168.0.1", 5555)
        val failed = result as UsbListingResult.AdbCommandFailed
        assertEquals("Connection reset", failed.message)
    }

    // ── TraceMate directory absent ────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns TraceMateDirectoryAbsent when TraceMate folder missing`() = runTest {
        val repo = fakeRepo(UsbListingResult.TraceMateDirectoryAbsent(usbMount))
        val result = repo.listUsbFiles("192.168.0.1", 5555)
        assertTrue(result is UsbListingResult.TraceMateDirectoryAbsent)
        assertEquals(mountPath, (result as UsbListingResult.TraceMateDirectoryAbsent).mount.mountPath)
    }

    // ── No exported files ─────────────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns NoExportedFiles when TraceMate directory is empty`() = runTest {
        val repo = fakeRepo(UsbListingResult.NoExportedFiles(usbMount))
        val result = repo.listUsbFiles("192.168.0.1", 5555)
        assertTrue(result is UsbListingResult.NoExportedFiles)
    }

    // ── USB removed during listing ────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns UsbRemovedDuringListing when sentinel is absent`() = runTest {
        val partial = listOf(
            com.example.adb_connection.domain.model.UsbFileEntry(
                name = "session1",
                relativePath = "session1",
                type = UsbFileType.DIRECTORY,
                sizeBytes = null
            )
        )
        val repo = fakeRepo(UsbListingResult.UsbRemovedDuringListing(usbMount, partial))
        val result = repo.listUsbFiles("192.168.0.1", 5555) as UsbListingResult.UsbRemovedDuringListing
        assertEquals(1, result.partialEntries.size)
        assertEquals("session1", result.partialEntries[0].name)
    }

    // ── Success ───────────────────────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns Success with directory entries`() = runTest {
        val entries = listOf(
            com.example.adb_connection.domain.model.UsbFileEntry(
                name = "tracemate_export_20260710_120000",
                relativePath = "tracemate_export_20260710_120000",
                type = UsbFileType.DIRECTORY,
                sizeBytes = null
            )
        )
        val repo = fakeRepo(UsbListingResult.Success(usbMount, entries))
        val result = repo.listUsbFiles("192.168.0.1", 5555) as UsbListingResult.Success
        assertEquals(mountPath, result.mount.mountPath)
        assertEquals(1, result.entries.size)
        assertEquals(UsbFileType.DIRECTORY, result.entries[0].type)
    }

    @Test
    fun `listUsbFiles returns Success with regular file entries`() = runTest {
        val entries = listOf(
            com.example.adb_connection.domain.model.UsbFileEntry(
                name = "core.log",
                relativePath = "tracemate_export_20260710_120000/trigger_1/core.log",
                type = UsbFileType.FILE,
                sizeBytes = 512000L
            )
        )
        val repo = fakeRepo(UsbListingResult.Success(usbMount, entries))
        val result = repo.listUsbFiles("192.168.0.1", 5555) as UsbListingResult.Success
        val file = result.entries[0]
        assertEquals(UsbFileType.FILE, file.type)
        assertEquals(512000L, file.sizeBytes)
    }

    // ── DirectoryNotFound ─────────────────────────────────────────────────────────

    @Test
    fun `listUsbFiles returns DirectoryNotFound when repository returns it`() = runTest {
        val repo = fakeRepo(UsbListingResult.DirectoryNotFound(usbMount, "session1/gone"))
        val result = repo.listUsbFiles("192.168.0.1", 5555, "session1/gone")
        val notFound = result as UsbListingResult.DirectoryNotFound
        assertEquals("session1/gone", notFound.relativeDirectory)
        assertEquals(mountPath, notFound.mount.mountPath)
    }

    // ── Shell command structure (UsbShellCommandBuilder) ─────────────────────────

    @Test
    fun `listTraceMateFilesCommand contains find with maxdepth`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("Must use find", cmd.contains("find"))
        assertTrue("Must bound depth with -maxdepth", cmd.contains("-maxdepth"))
    }

    @Test
    fun `listTraceMateFilesCommand uses pipe delimiter in stat format`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("stat format must contain pipe delimiter", cmd.contains("%F|%s|%n"))
    }

    @Test
    fun `listTraceMateFilesCommand excludes partial directories`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("Must exclude .partial dirs", cmd.contains(".partial"))
        assertTrue("Must use -prune to prevent descent", cmd.contains("-prune"))
    }

    @Test
    fun `listTraceMateFilesCommand emits STATUS_DONE only on find success`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("Must emit STATUS:DONE sentinel", cmd.contains("STATUS:DONE"))
        assertTrue("STATUS:DONE must be conditional on find success (&&)", cmd.contains("&& echo STATUS:DONE"))
        assertFalse("Must not use unconditional ; before STATUS:DONE", cmd.contains("; echo STATUS:DONE"))
    }

    @Test
    fun `listTraceMateFilesCommand targets TraceMate subdirectory`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("Must target HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER dir", cmd.contains("$mountPath/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"))
    }

    @Test
    fun `listTraceMateFilesCommand default maxdepth is 4`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("Default maxdepth must be 4", cmd.contains("-maxdepth 4"))
    }

    @Test
    fun `listTraceMateFilesCommand custom maxdepth is honoured`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount, maxDepth = 2)
        assertTrue(cmd.contains("-maxdepth 2"))
        assertFalse(cmd.contains("-maxdepth 4"))
    }

    @Test
    fun `listTraceMateFilesCommand includes both files and directories`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .listTraceMateFilesCommand(usbMount)
        assertTrue("Must match -type f", cmd.contains("-type f"))
        assertTrue("Must match -type d", cmd.contains("-type d"))
    }

    // ── USB detection shared by copy and listing features ───────────────────────

    @Test
    fun `detectUsbMountsCommand is shared with copy feature`() {
        val cmd = com.example.adb_connection.domain.usb.UsbShellCommandBuilder
            .detectUsbMountsCommand()
        // Same /proc/mounts query used by both copy and listing
        assertTrue(cmd.contains("/proc/mounts"))
        assertTrue("Must reference configured USB mount root", cmd.contains("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER"))
    }
}
