package com.example.adb_connection.domain.usb

import com.example.adb_connection.data.adb.MAX_PAYLOAD
import com.example.adb_connection.data.adb.SHELL_SERVICE_OVERHEAD
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.UsbMount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbShellCommandBuilderTest {

    private val usbMount = UsbMount(
        devicePath = "/dev/sda1",
        mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E",
        fileSystem = "exfat",
        mountOptions = setOf("rw", "relatime")
    )
    private val stem = "trigger_2_HU_20260701_113733_COREDUMP"
    private val archive = TriggerArchive(
        triggerNumber = 2,
        stem = stem,
        archivePath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/$stem.tar.lz4",
        extractedDirectoryPath = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/$stem"
    )
    private val sessionDir = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/695E-276E/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/tracemate_export_20260710_120000"
    private val jobId = "a1b2c3d4e5f60718"

    // ── discoverArchivesCommand ─────────────────────────────────────────────────

    @Test
    fun `discoverArchivesCommand contains correct archive root`() {
        val cmd = UsbShellCommandBuilder.discoverArchivesCommand()
        assertTrue(cmd.contains("HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER"))
    }

    @Test
    fun `discoverArchivesCommand has maxdepth 1`() {
        val cmd = UsbShellCommandBuilder.discoverArchivesCommand()
        assertTrue(cmd.contains("-maxdepth 1"))
    }

    @Test
    fun `discoverArchivesCommand has type file filter`() {
        val cmd = UsbShellCommandBuilder.discoverArchivesCommand()
        assertTrue(cmd.contains("-type f"))
    }

    @Test
    fun `discoverArchivesCommand has correct filename pattern`() {
        val cmd = UsbShellCommandBuilder.discoverArchivesCommand()
        assertTrue(cmd.contains("trigger_*_COREDUMP.tar.lz4"))
    }

    // ── detectUsbMountsCommand ─────────────────────────────────────────────────

    @Test
    fun `detectUsbMountsCommand reads proc mounts`() {
        val cmd = UsbShellCommandBuilder.detectUsbMountsCommand()
        assertTrue(cmd.contains("/proc/mounts"))
    }

    @Test
    fun `detectUsbMountsCommand only matches configured USB root subdirectories`() {
        val cmd = UsbShellCommandBuilder.detectUsbMountsCommand()
        assertTrue("Must reference configured USB mount root", cmd.contains("HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER"))
        assertFalse("Must not match UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER", cmd.contains("UNSUPPORTED_USB_MOUNT_ROOT_PLACEHOLDER"))
    }

    @Test
    fun `detectUsbMountsCommand emits pipe-delimited fields`() {
        val cmd = UsbShellCommandBuilder.detectUsbMountsCommand()
        assertTrue(cmd.contains("\"|\""))
    }

    @Test
    fun `mount validation encodes spaces as proc mounts escapes`() {
        val mountWithSpace = usbMount.copy(mountPath = "HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/My USB")

        val command = UsbShellCommandBuilder.createSessionDirCommand(mountWithSpace)

        assertTrue(command.contains("-v mp='HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/My\\040USB'"))
        assertTrue(command.contains("PROBE='HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/My USB/.tracemate_probe_"))
    }

    @Test
    fun `delete command validates original USB mount before and after removal`() {
        val command = UsbShellCommandBuilder.deleteAllTraceMateFilesCommand(usbMount)

        assertEquals(2, "awk -v mp=".toRegex().findAll(command).count())
        assertTrue(command.contains("USB_DISCONNECTED"))
        assertTrue(command.contains("USB_MOUNT_CHANGED"))
        assertTrue(command.contains("USB_NOT_WRITABLE"))
        assertTrue(command.contains("TRACEMATE_DELETE:SUCCESS"))
    }

    @Test
    fun `delete command rejects an unexpected target and verifies deletion`() {
        val command = UsbShellCommandBuilder.deleteAllTraceMateFilesCommand(usbMount)

        assertTrue(command.contains("[ -L \"\$TARGET\" ] || [ ! -d \"\$TARGET\" ]"))
        assertTrue(command.contains("rm -rf -- \"\$TARGET\""))
        assertTrue(command.contains("POSTCONDITION_FAILED"))
        assertTrue(command.contains("sync"))
    }

    @Test
    fun `existing export listing is bounded and emits completion sentinel`() {
        val command = UsbShellCommandBuilder.listExistingExportDirectoriesCommand(usbMount)

        assertTrue(command.contains("${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"))
        assertTrue(command.contains("-mindepth 2 -maxdepth 2 -type d"))
        assertTrue(command.contains("STATUS:DONE"))
    }

    // ── checkDuplicateCommand ───────────────────────────────────────────────────

    @Test
    fun `checkDuplicateCommand has bounded depth`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue(cmd.contains("-mindepth 2"))
        assertTrue(cmd.contains("-maxdepth 2"))
    }

    @Test
    fun `checkDuplicateCommand searches by exact name`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue(cmd.contains("-name '$stem'"))
    }

    @Test
    fun `checkDuplicateCommand limits to first result`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue(cmd.contains("head -n 1"))
    }

    @Test
    fun `checkDuplicateCommand searches under TraceMate directory`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue(cmd.contains("${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"))
    }

    @Test
    fun `checkDuplicateCommand requires directory type — regular file with same name is ignored`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue("Must restrict to -type d so a regular file is not matched", cmd.contains("-type d"))
    }

    @Test
    fun `checkDuplicateCommand uses exact name match — similarly named directory is not matched`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue(cmd.contains("-name '$stem'"))
        assertFalse(cmd.contains("-name '*$stem*'"))
    }

    @Test
    fun `checkDuplicateCommand depth bounds prevent searching outside TraceMate`() {
        val cmd = UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem)
        assertTrue(cmd.contains("-mindepth 2"))
        assertTrue(cmd.contains("-maxdepth 2"))
    }

    // ── checkArchiveExistsCommand ───────────────────────────────────────────────

    @Test
    fun `checkArchiveExistsCommand uses file test and prints YES on success`() {
        val cmd = UsbShellCommandBuilder.checkArchiveExistsCommand(archive.archivePath)
        assertTrue("Must use -f file test", cmd.contains("[ -f '${archive.archivePath}' ]"))
        assertTrue("Must echo YES when file exists", cmd.contains("echo YES"))
        assertTrue("Must echo NO when file missing", cmd.contains("echo NO"))
    }

    @Test
    fun `checkArchiveExistsCommand embeds the exact archive path`() {
        val path = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/trigger_2_HU_20260701_113733_COREDUMP.tar.lz4"
        val cmd = UsbShellCommandBuilder.checkArchiveExistsCommand(path)
        assertTrue(cmd.contains(path))
    }

    // ── buildWorkerScriptContent — Strategy A ──────────────────────────────────

    @Test
    fun `strategy A worker contains cp -a`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("cp -a"))
    }

    @Test
    fun `strategy A worker contains partial marker`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains(".$stem.partial"))
    }

    @Test
    fun `strategy A worker contains sync`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("sync"))
    }

    @Test
    fun `strategy A worker contains ALREADY_PRESENT check`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("ALREADY_PRESENT"))
    }

    @Test
    fun `strategy A worker contains job directory reference`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId"))
    }

    // ── buildWorkerScriptContent — Strategy B ──────────────────────────────────

    @Test
    fun `strategy B worker contains lz4 -dc`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("lz4 -dc"))
    }

    @Test
    fun `strategy B worker contains tar -x`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("tar -x"))
    }

    @Test
    fun `strategy B worker uses fifo to capture lz4 exit code in POSIX sh`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("mkfifo"))
        assertTrue(script.contains("LZ4PID"))
        assertTrue(script.contains("wait \$LZ4PID"))
    }

    @Test
    fun `strategy B worker contains partial marker`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains(".$stem.partial"))
    }

    @Test
    fun `strategy B worker normalizes top-level stem directory`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("\$PARTIAL/$stem"))
    }

    @Test
    fun `strategy B worker contains sync`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("sync"))
    }

    @Test
    fun `strategy B worker contains ALREADY_PRESENT check`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("ALREADY_PRESENT"))
    }

    @Test
    fun `strategy B worker does not contain cp -a`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertFalse(script.contains("cp -a"))
    }

    // ── buildCancelScript ───────────────────────────────────────────────────────

    private val statusPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/status"
    private val cancelPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/cancel"
    private val pidPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/pid"
    private val partialDir = "$sessionDir/.$stem.partial"
    private val cancelJobDir = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId"

    @Test
    fun `cancel script sends SIGTERM`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue(script.contains("kill -TERM"))
    }

    @Test
    fun `cancel script waits before SIGKILL`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue(script.contains("sleep 2"))
    }

    @Test
    fun `cancel script sends SIGKILL`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue(script.contains("kill -KILL"))
    }

    @Test
    fun `cancel script removes partial directory only after the worker is confirmed stopped`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue(script.contains("rm -rf '$partialDir'"))
        assertTrue(script.contains("CANCEL_FAILED:WORKER_STILL_RUNNING"))
    }

    @Test
    fun `cancel script outputs CANCEL_DONE`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue(script.contains("CANCEL_DONE"))
    }

    @Test
    fun `cancel script targets process group via negative PID`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue("Must kill process group via negative PID", script.contains("kill -TERM -- -\$PID"))
        assertTrue("Must kill process group via negative PID on SIGKILL", script.contains("kill -KILL -- -\$PID"))
    }

    @Test
    fun `cancel script writes CANCEL_REQUESTED to cancel file before sending signals`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        val cancelFlagLine = script.lines().indexOfFirst { it.contains("CANCEL_REQUESTED") && it.contains("'$cancelPath'") }
        val termLine = script.lines().indexOfFirst { it.contains("kill -TERM") }
        assertTrue("CANCEL_REQUESTED must be written to cancel file", cancelFlagLine >= 0)
        assertTrue("CANCEL_REQUESTED flag must precede SIGTERM", cancelFlagLine < termLine)
    }

    @Test
    fun `cancel script writes CANCELLED only after cancellation confirmation`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        val lines = script.lines()
        val cancelledLine = lines.indexOfFirst { it.contains("'CANCELLED'") && it.contains("'$statusPath'") }
        val cancelDoneLine = lines.indexOfFirst { it.contains("CANCEL_DONE") }
        assertTrue("cancel script must write CANCELLED to status file", cancelledLine >= 0)
        assertTrue("CANCELLED must be written before CANCEL_DONE", cancelledLine < cancelDoneLine)
        val aliveCheckLine = lines.indexOfFirst { it.contains("CANCEL_FAILED:WORKER_STILL_RUNNING") }
        assertTrue("Worker liveness must be checked before terminal cancellation status", aliveCheckLine < cancelledLine)
    }

    @Test
    fun `strategy A worker checks cancel file after copy`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("CANCELFILE"))
    }

    @Test
    fun `strategy B worker checks cancel file after extraction`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("CANCELFILE"))
    }

    @Test
    fun `strategy A partial dir name is dot-prefixed and dot-partial-suffixed`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains(".$stem.partial"))
    }

    @Test
    fun `strategy B partial dir name is dot-prefixed and dot-partial-suffixed`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains(".$stem.partial"))
    }

    // ── buildWorkerScriptContent — Strategy B FIFO placement ───────────────────

    @Test
    fun `strategy B FIFO is created inside job dir on tmpfs, not inside partial dir on USB`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val expectedFifo = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/.lz4fifo"
        assertTrue("FIFO path must be inside job dir", script.contains(expectedFifo))
        assertFalse("FIFO must not be created inside the USB partial dir", script.contains("\$PARTIAL/.lz4fifo"))
        assertFalse("FIFO must not be created inside the USB partial dir", script.contains(".$stem.partial/.lz4fifo"))
    }

    @Test
    fun `strategy B FIFO is removed after lz4 and tar complete`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val waitLine = lines.indexOfFirst { it.contains("wait \$LZ4PID") }
        val fifoRemoveLine = lines.withIndex()
            .indexOfFirst { (i, line) -> i > waitLine && line.contains("rm -f") && line.contains("\$FIFO") }
        assertTrue("FIFO must be removed via \$FIFO variable", fifoRemoveLine >= 0)
        assertTrue("FIFO must be removed after lz4 wait", fifoRemoveLine > waitLine)
    }

    @Test
    fun `strategy B partial dir is cleaned up on FIFO_CREATE_FAILED`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val fifoFailLine = lines.indexOfFirst { it.contains("FIFO_CREATE_FAILED") }
        assertTrue("Must emit FIFO_CREATE_FAILED on mkfifo failure", fifoFailLine >= 0)
        val line = lines[fifoFailLine]
        assertTrue("Must rm partial dir before reporting FIFO_CREATE_FAILED", line.contains("rm -rf"))
    }

    @Test
    fun `strategy B partial dir is cleaned up on LZ4_DECOMPRESS_FAILED`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val pipelineFailedLine = lines.indexOfFirst { it.contains("PIPELINE_FAILED -eq 1") }
        val reportLine = lines.indexOfFirst { it.contains("echo \"FAILED:\$_CLASSIFY_REASON\"") }
        assertTrue("Must enter the pipeline-failure block", pipelineFailedLine in 0 until reportLine)
        val block = lines.subList(pipelineFailedLine, reportLine + 1)
        assertTrue("Must assign LZ4_DECOMPRESS_FAILED", block.any { it.contains("_CLASSIFY_REASON='LZ4_DECOMPRESS_FAILED'") })
        assertTrue("Must rm partial dir before reporting FAILED:\$_CLASSIFY_REASON", block.any { it.contains("rm -rf \"\$PARTIAL\"") })
    }

    @Test
    fun `strategy B partial dir is cleaned up on TAR_EXTRACT_FAILED`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val pipelineFailedLine = lines.indexOfFirst { it.contains("PIPELINE_FAILED -eq 1") }
        val reportLine = lines.indexOfFirst { it.contains("echo \"FAILED:\$_CLASSIFY_REASON\"") }
        assertTrue("Must enter the pipeline-failure block", pipelineFailedLine in 0 until reportLine)
        val block = lines.subList(pipelineFailedLine, reportLine + 1)
        assertTrue("Must assign TAR_EXTRACT_FAILED", block.any { it.contains("_CLASSIFY_REASON='TAR_EXTRACT_FAILED'") })
        assertTrue("Must rm partial dir before reporting FAILED:\$_CLASSIFY_REASON", block.any { it.contains("rm -rf \"\$PARTIAL\"") })
    }

    @Test
    fun `strategy B partial dir is cleaned up on LZ4_UNAVAILABLE`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val failLine = lines.indexOfFirst { it.contains("LZ4_UNAVAILABLE") }
        assertTrue("Must emit LZ4_UNAVAILABLE", failLine >= 0)
        assertTrue("Must rm partial dir on LZ4_UNAVAILABLE", lines[failLine].contains("rm -rf"))
    }

    // ── generateJobId ───────────────────────────────────────────────────────────

    @Test
    fun `generated job id is 16 characters`() {
        val id = UsbShellCommandBuilder.generateJobId()
        assertEquals(16, id.length)
    }

    @Test
    fun `generated job id contains only lowercase hex`() {
        val id = UsbShellCommandBuilder.generateJobId()
        assertTrue(id.matches(Regex("^[a-f0-9]{16}$")))
    }

    @Test
    fun `two generated job ids differ`() {
        val id1 = UsbShellCommandBuilder.generateJobId()
        val id2 = UsbShellCommandBuilder.generateJobId()
        assertNotEquals(id1, id2)
    }

    @Test
    fun `generated job id is valid per parser`() {
        val id = UsbShellCommandBuilder.generateJobId()
        assertTrue(TriggerArchiveParser.isJobIdValid(id))
    }

    // ── blockingWaitAndReadStatusCommand ────────────────────────────────────────

    @Test
    fun `blockingWaitAndReadStatusCommand reads PID from pidPath`() {
        val cmd = UsbShellCommandBuilder.blockingWaitAndReadStatusCommand(pidPath, statusPath)
        assertTrue("Must cat the pid file to read PID", cmd.contains("cat '$pidPath'"))
    }

    @Test
    fun `blockingWaitAndReadStatusCommand uses kill -0 for liveness polling`() {
        val cmd = UsbShellCommandBuilder.blockingWaitAndReadStatusCommand(pidPath, statusPath)
        assertTrue("Must use kill -0 to poll process", cmd.contains("kill -0"))
    }

    @Test
    fun `blockingWaitAndReadStatusCommand loops with sleep`() {
        val cmd = UsbShellCommandBuilder.blockingWaitAndReadStatusCommand(pidPath, statusPath)
        assertTrue("Must sleep between kill -0 checks", cmd.contains("sleep"))
    }

    @Test
    fun `blockingWaitAndReadStatusCommand cats status file after process exits`() {
        val cmd = UsbShellCommandBuilder.blockingWaitAndReadStatusCommand(pidPath, statusPath)
        val lines = cmd.lines().filter { it.isNotBlank() }
        val catLine = lines.indexOfFirst { it.contains("cat '$statusPath'") }
        val loopLine = lines.indexOfFirst { it.contains("kill -0") }
        assertTrue("Must cat status file after the while loop", catLine > loopLine)
    }

    // ── inspectJobCommand ────────────────────────────────────────────────────────

    @Test
    fun `inspectJobCommand checks liveness via kill -0 on the pid file`() {
        val cmd = UsbShellCommandBuilder.inspectJobCommand(jobId)
        assertTrue("Must read the pid file", cmd.contains("cat 'HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/pid'"))
        assertTrue("Must probe liveness with kill -0", cmd.contains("kill -0"))
    }

    @Test
    fun `inspectJobCommand emits ALIVE token`() {
        val cmd = UsbShellCommandBuilder.inspectJobCommand(jobId)
        assertTrue(cmd.contains("ALIVE:1"))
        assertTrue(cmd.contains("ALIVE:0"))
    }

    @Test
    fun `inspectJobCommand emits STATUS token from the job's status file`() {
        val cmd = UsbShellCommandBuilder.inspectJobCommand(jobId)
        assertTrue(cmd.contains("STATUS:"))
        assertTrue(cmd.contains("cat 'HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/status'"))
    }

    @Test
    fun `inspectJobCommand does not launch or wait for anything — single round trip only`() {
        val cmd = UsbShellCommandBuilder.inspectJobCommand(jobId)
        assertFalse("Must never start a new worker", cmd.contains("setsid"))
        assertFalse("Must not block waiting on the process", cmd.contains("while"))
    }

    // ── listTraceMateFilesCommand ───────────────────────────────────────────────

    @Test
    fun `listTraceMateFilesCommand prunes partial directories and their contents`() {
        val cmd = UsbShellCommandBuilder.listTraceMateFilesCommand(usbMount)
        assertTrue(
            "Must use -prune to prevent descent into .partial dirs",
            cmd.contains("-name '.*\\.partial' -prune")
        )
        assertFalse(
            "Must not use -not -name (only excludes the dir entry, not its contents)",
            cmd.contains("-not -name")
        )
    }

    // ── listTraceMateDirectoryCommand ───────────────────────────────────────────

    @Test
    fun `listTraceMateDirectoryCommand with empty relativeDirectory lists the USB mount root`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue(cmd.contains("'${usbMount.mountPath}'"))
        assertFalse(cmd.contains("-name 'HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER' -o -name 'CAPTURE_TOOL_PLACEHOLDER_CAPTURE_DIRECTORY_PLACEHOLDER'"))
    }

    @Test
    fun `listTraceMateDirectoryCommand with relativeDirectory targets subdirectory`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/session1")
        assertTrue(cmd.contains("${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/session1"))
    }

    @Test
    fun `listTraceMateDirectoryCommand uses maxdepth 1`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue("Must use -maxdepth 1 for shallow listing", cmd.contains("-maxdepth 1"))
        assertFalse("Must not allow deeper recursion", cmd.contains("-maxdepth 4"))
    }

    @Test
    fun `listTraceMateDirectoryCommand uses mindepth 1`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue("Must use -mindepth 1 to skip the directory itself", cmd.contains("-mindepth 1"))
    }

    @Test
    fun `listTraceMateDirectoryCommand emits STATUS_NOT_FOUND when directory absent`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue("Must emit STATUS:NOT_FOUND when target dir missing", cmd.contains("STATUS:NOT_FOUND"))
    }

    @Test
    fun `listTraceMateDirectoryCommand emits STATUS_DONE unconditionally after find`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue("Must emit STATUS:DONE sentinel", cmd.contains("STATUS:DONE"))
    }

    @Test
    fun `listTraceMateDirectoryCommand prunes partial files`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue("Must prune .partial entries", cmd.contains(".partial"))
        assertTrue("Must use -prune", cmd.contains("-prune"))
    }

    @Test
    fun `listTraceMateDirectoryCommand uses pipe delimiter in stat format`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "")
        assertTrue("stat format must contain pipe delimiter", cmd.contains("%F|%s|%n"))
    }

    @Test
    fun `listTraceMateDirectoryCommand rejects path traversal`() {
        try {
            UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "../etc")
            assertTrue("Must throw for path traversal", false)
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `listTraceMateDirectoryCommand rejects absolute paths`() {
        try {
            UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "/etc")
            assertTrue("Must throw for absolute path", false)
        } catch (_: IllegalArgumentException) { /* expected */ }
    }

    @Test
    fun `listTraceMateDirectoryCommand accepts nested path`() {
        val cmd = UsbShellCommandBuilder.listTraceMateDirectoryCommand(usbMount, "HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/session1/trigger_1")
        assertTrue(cmd.contains("${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/session1/trigger_1"))
    }

    // ── createSessionDirCommand — mount revalidation before mkdir ──────────────

    @Test
    fun `createSessionDirCommand revalidates proc mounts before mkdir`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        val mkdirLine = cmd.lines().indexOfFirst { it.contains("mkdir -p") }
        val checkLine = cmd.lines().indexOfFirst { it.contains("/proc/mounts") }
        assertTrue("Must read /proc/mounts", checkLine >= 0)
        assertTrue("Must validate mount before mkdir", checkLine < mkdirLine)
    }

    @Test
    fun `createSessionDirCommand checks the exact expected device and mount path`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue(cmd.contains("mp='${usbMount.mountPath}'"))
        assertTrue(cmd.contains("'${usbMount.devicePath}'"))
    }

    @Test
    fun `createSessionDirCommand reports FAIL USB_NOT_FOUND when mount is missing`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue(cmd.contains("_USB_CHECK='USB_NOT_FOUND'"))
        assertTrue(cmd.contains("echo \"FAIL:\$_USB_CHECK\""))
    }

    @Test
    fun `createSessionDirCommand reports USB_MOUNT_CHANGED when a different device now owns the path`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue(cmd.contains("_USB_CHECK='USB_MOUNT_CHANGED'"))
    }

    @Test
    fun `createSessionDirCommand reports USB_NOT_WRITABLE when mounted read-only`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue(cmd.contains("_USB_CHECK='USB_NOT_WRITABLE'"))
        assertTrue("Must check for rw among mount options", cmd.contains(",rw,"))
    }

    @Test
    fun `createSessionDirCommand exits before mkdir when validation fails`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue(cmd.contains("if [ \"\$_USB_CHECK\" != 'OK' ]; then echo \"FAIL:\$_USB_CHECK\"; exit 0; fi"))
    }

    @Test
    fun `createSessionDirCommand never targets a bare mount label directory - always nests under HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue("Session dir must live under HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/, not directly on the mount root", cmd.contains("${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER/"))
    }

    @Test
    fun `createSessionDirCommand performs a write probe before mkdir`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        val lines = cmd.lines()
        val probeLine = lines.indexOfFirst { it.contains("touch") }
        val mkdirLine = lines.indexOfFirst { it.contains("mkdir -p") }
        assertTrue("Must have a write probe via touch", probeLine >= 0)
        assertTrue("Write probe must precede mkdir", probeLine < mkdirLine)
    }

    @Test
    fun `createSessionDirCommand reports USB_NOT_WRITABLE when write probe fails`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue(cmd.contains("FAIL:USB_NOT_WRITABLE"))
    }

    @Test
    fun `createSessionDirCommand cleans up probe file after successful probe`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        val lines = cmd.lines()
        val probeLine = lines.indexOfFirst { it.contains("touch") && it.contains(usbMount.mountPath) }
        val rmProbeLine = lines.indexOfFirst { it.contains("rm -f") && it.contains("PROBE") }
        assertTrue("Must remove the probe file after the probe", rmProbeLine > probeLine)
    }

    @Test
    fun `createSessionDirCommand probe path is inside the USB mount root`() {
        val cmd = UsbShellCommandBuilder.createSessionDirCommand(usbMount)
        assertTrue("Probe must be placed on the USB mount", cmd.contains("'${usbMount.mountPath}/"))
    }

    // ── buildJobPrepareScript — job dir creation and mount revalidation ─────────

    @Test
    fun `buildJobPrepareScript revalidates mount before creating job directory`() {
        val script = UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount)
        val lines = script.lines()
        val checkLine = lines.indexOfFirst { it.contains("/proc/mounts") }
        val jobDirLine = lines.indexOfFirst { it.contains("mkdir -p") }
        assertTrue("Must validate mount", checkLine >= 0)
        assertTrue("Must validate before creating job dir", checkLine < jobDirLine)
    }

    @Test
    fun `buildJobPrepareScript reports ERROR USB_DISCONNECTED when mount missing`() {
        val script = UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount)
        assertTrue(script.contains("_USB_CHECK='USB_DISCONNECTED'"))
        assertTrue(script.contains("echo \"ERROR:\$_USB_CHECK\"; exit 1"))
    }

    @Test
    fun `buildJobPrepareScript creates job directory under tmp tracemate-usb`() {
        val script = UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount)
        assertTrue(script.contains("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId"))
        assertTrue(script.contains("mkdir -p"))
    }

    @Test
    fun `buildJobPrepareScript reports ERROR JOB_DIR_CREATE_FAILED on mkdir failure`() {
        val script = UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount)
        assertTrue(script.contains("ERROR:JOB_DIR_CREATE_FAILED"))
    }

    @Test
    fun `buildJobPrepareScript emits JOBDIR_READY on success`() {
        val script = UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount)
        assertTrue(script.contains("JOBDIR_READY"))
    }

    // ── buildJobLaunchScript ────────────────────────────────────────────────────

    @Test
    fun `buildJobLaunchScript uses setsid for full process-group detachment`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        assertTrue("Must use setsid for full detachment", script.contains("setsid"))
    }

    @Test
    fun `buildJobLaunchScript redirects stdin from dev null`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        assertTrue("Worker must not inherit ADB stdin", script.contains("</dev/null"))
    }

    @Test
    fun `buildJobLaunchScript redirects stdout and stderr to worker log`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        val workerLog = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/worker.log"
        assertTrue("Must redirect stdout to worker.log", script.contains(">'$workerLog'"))
        assertTrue("Must redirect stderr to worker.log", script.contains("2>&1"))
    }

    @Test
    fun `buildJobLaunchScript runs worker sh in background`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        assertTrue("Must background the worker", script.contains("&"))
        assertTrue("Must capture background PID via \$!", script.contains("\$!"))
    }

    @Test
    fun `buildJobLaunchScript writes background PID to pid file`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        val pidFilePath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/pid"
        assertTrue("PID must be written to $pidFilePath", script.contains("'$pidFilePath'"))
        assertTrue("Background PID capture must use \$!", script.contains("\$!"))
    }

    @Test
    fun `buildJobLaunchScript emits STARTED with the background PID`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        assertTrue("Must emit STARTED:<pid>", script.contains("STARTED:"))
    }

    @Test
    fun `buildJobLaunchScript writes RUNNING status before starting worker`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        val lines = script.lines()
        val runningLine = lines.indexOfFirst { it.contains("'RUNNING'") }
        val setsidLine = lines.indexOfFirst { it.contains("setsid") }
        assertTrue("Must write RUNNING status", runningLine >= 0)
        assertTrue("RUNNING must be written before the worker is launched", runningLine < setsidLine)
    }

    @Test
    fun `buildJobLaunchScript launches worker sh not embedded shell commands`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        val workerScriptPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/worker.sh"
        assertTrue("Must run worker.sh by path", script.contains("'$workerScriptPath'"))
    }

    // ── buildJobLaunchScript — launch command size safety ──────────────────────

    @Test
    fun `buildJobLaunchScript is well under the ADB payload limit`() {
        val script = UsbShellCommandBuilder.buildJobLaunchScript(jobId)
        val payloadBytes = ("shell:" + script + " ").toByteArray().size
        assertTrue(
            "Launch script payload $payloadBytes bytes must fit in ADB MAX_PAYLOAD $MAX_PAYLOAD",
            payloadBytes <= MAX_PAYLOAD
        )
    }

    // ── buildJobPrepareScript — payload size safety ────────────────────────────

    @Test
    fun `buildJobPrepareScript is well under the ADB payload limit`() {
        val script = UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount)
        val payloadBytes = ("shell:" + script + " ").toByteArray().size
        assertTrue(
            "Prepare script payload $payloadBytes bytes must fit in ADB MAX_PAYLOAD $MAX_PAYLOAD",
            payloadBytes <= MAX_PAYLOAD
        )
    }

    // ── buildWorkerScriptContent — Strategy A / B workers: mount revalidation ──

    @Test
    fun `strategy A worker revalidates mount immediately before final mv`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val lines = script.lines()
        val checkLine = lines.indexOfLast { it.contains("/proc/mounts") }
        val mvLine = lines.indexOfFirst { it.contains("mv \"\$PARTIAL\" \"\$FINAL\"") }
        assertTrue("Must validate mount before final rename", checkLine in 0 until mvLine)
    }

    @Test
    fun `strategy A worker removes partial dir and reports FAILED reason when revalidation fails`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"FAILED:\$_USB_CHECK\" > \"\$STATUSFILE\""))
    }

    @Test
    fun `strategy A worker never reaches the final mv when mount revalidation fails (mismatch or disconnect)`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(
            "Revalidation failure branch must exit 1 before ever reaching mv",
            script.contains("if [ \"\$_USB_CHECK\" != 'OK' ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"FAILED:\$_USB_CHECK\" > \"\$STATUSFILE\"; exit 1; fi")
        )
        val lines = script.lines()
        val revalidationFailLine = lines.indexOfFirst { it.contains("if [ \"\$_USB_CHECK\" != 'OK' ]") && it.contains("exit 1; fi") }
        val mvLine = lines.indexOfFirst { it.contains("mv \"\$PARTIAL\" \"\$FINAL\"") }
        assertTrue("Guard must run before mv", revalidationFailLine < mvLine)
    }

    @Test
    fun `strategy B worker revalidates mount immediately before final mv`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val checkLine = lines.indexOfLast { it.contains("/proc/mounts") }
        val mvLine = lines.indexOfFirst { it.contains("mv \"\$PARTIAL\" \"\$FINAL\"") }
        assertTrue("Must validate mount before final rename", checkLine in 0 until mvLine)
    }

    @Test
    fun `strategy B worker removes partial dir and reports FAILED reason when revalidation fails`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(script.contains("rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"FAILED:\$_USB_CHECK\" > \"\$STATUSFILE\""))
    }

    @Test
    fun `strategy B worker never reaches the final mv when mount revalidation fails (mismatch or disconnect)`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(
            "Revalidation failure branch must exit 1 before ever reaching mv",
            script.contains("if [ \"\$_USB_CHECK\" != 'OK' ]; then rm -rf \"\$PARTIAL\" 2>/dev/null; echo \"FAILED:\$_USB_CHECK\" > \"\$STATUSFILE\"; exit 1; fi")
        )
        val lines = script.lines()
        val revalidationFailLine = lines.indexOfFirst { it.contains("if [ \"\$_USB_CHECK\" != 'OK' ]") && it.contains("exit 1; fi") }
        val mvLine = lines.indexOfFirst { it.contains("mv \"\$PARTIAL\" \"\$FINAL\"") }
        assertTrue("Guard must run before mv", revalidationFailLine < mvLine)
    }

    // ── Strategy A worker — cp stderr capture and classification ──────────────

    @Test
    fun `strategy A worker captures cp stderr instead of discarding it`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue("Must define a cp.err capture file", script.contains("CPERR='HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/cp.err'"))
        assertTrue("cp -a must redirect stderr to CPERR", script.contains("cp -a '${archive.extractedDirectoryPath}/.' \"\$PARTIAL/\" 2>\"\$CPERR\""))
        assertTrue("cp -r fallback must also redirect stderr to CPERR", script.contains("cp -r '${archive.extractedDirectoryPath}/.' \"\$PARTIAL/\" 2>\"\$CPERR\""))
        assertFalse("Must not silently discard cp stderr to /dev/null", script.contains("cp -a '${archive.extractedDirectoryPath}/.' \"\$PARTIAL/\" 2>/dev/null"))
    }

    @Test
    fun `strategy A worker emits captured cp stderr into worker log on failure`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val lines = script.lines()
        val cpCodeCheck = lines.indexOfFirst { it.contains("if [ \$CPCODE -ne 0 ]; then") }
        val catLine = lines.indexOfFirst { it.contains("cat \"\$CPERR\" 2>/dev/null") }
        assertTrue("Must cat CPERR on cp failure so it flows into worker.log", catLine > cpCodeCheck)
    }

    @Test
    fun `strategy A worker classifies USB_FULL from cp stderr before falling back to CP_FAILED`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue("Must grep for the full-disk signature", script.contains("grep -q 'No space left on device' \"\$_ERRFILE\""))
        assertTrue("Must assign USB_FULL when matched", script.contains("_CLASSIFY_REASON='USB_FULL'"))
        assertTrue("Must fall back to CP_FAILED when nothing else matches", script.contains("_CLASSIFY_REASON='CP_FAILED'"))
    }

    @Test
    fun `strategy A worker classifies USB_NOT_WRITABLE and USB_IO_ERROR from cp stderr`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("grep -q 'Read-only file system' \"\$_ERRFILE\""))
        assertTrue(script.contains("grep -q 'Input/output error' \"\$_ERRFILE\""))
    }

    @Test
    fun `strategy A worker classification checks stderr patterns before mount revalidation`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val lines = script.lines()
        val cpCodeCheck = lines.indexOfFirst { it.contains("if [ \$CPCODE -ne 0 ]; then") }
        val reportLine = lines.indexOfFirst { it.contains("echo \"FAILED:\$_CLASSIFY_REASON\"") }
        assertTrue("Must be inside the cp-failure block", cpCodeCheck in 0 until reportLine)
        val block = lines.subList(cpCodeCheck, reportLine + 1)
        val patternGrepLine = block.indexOfFirst { it.contains("grep -q 'No space left on device'") }
        val mountRecheckLine = block.indexOfFirst { it.contains("/proc/mounts") }
        assertTrue("Must grep the captured stderr", patternGrepLine >= 0)
        assertTrue("Must revalidate mount only after stderr patterns are checked", mountRecheckLine > patternGrepLine)
    }

    @Test
    fun `strategy A worker never labels a destination write failure as CP_FAILED`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val lines = script.lines()
        val defaultLine = lines.indexOfFirst { it.contains("_CLASSIFY_REASON='CP_FAILED'") }
        assertTrue("CP_FAILED must be gated behind a healthy-mount else branch", lines[defaultLine].contains("else"))
    }

    // ── Strategy B worker — tar/lz4 stderr capture and classification ─────────

    @Test
    fun `strategy B worker captures tar and lz4 stderr instead of discarding it`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue("Must define a tar.err capture file", script.contains("TARERR='HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/tar.err'"))
        assertTrue("Must define a lz4.err capture file", script.contains("LZ4ERR='HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/lz4.err'"))
        assertTrue("lz4 must redirect stderr to LZ4ERR", script.contains("2>\"\$LZ4ERR\""))
        assertTrue("tar must redirect stderr to TARERR", script.contains("2>\"\$TARERR\""))
    }

    @Test
    fun `strategy B worker emits captured tar and lz4 stderr into worker log on pipeline failure`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val pipelineFailedLine = lines.indexOfFirst { it.contains("PIPELINE_FAILED -eq 1") }
        val catTar = lines.indexOfFirst { it.contains("cat \"\$TARERR\" 2>/dev/null") }
        val catLz4 = lines.indexOfFirst { it.contains("cat \"\$LZ4ERR\" 2>/dev/null") }
        assertTrue("Must cat TARERR after pipeline failure detected", catTar > pipelineFailedLine)
        assertTrue("Must cat LZ4ERR after pipeline failure detected", catLz4 > pipelineFailedLine)
    }

    @Test
    fun `strategy B worker keeps 141 SIGPIPE exemption for lz4 exit code`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(
            "lz4 exit 141 must remain exempt from failure classification",
            script.contains("if [ \$LZ4CODE -ne 0 ] && [ \$LZ4CODE -ne 141 ]; then PIPELINE_FAILED=1; fi")
        )
    }

    @Test
    fun `strategy B worker does not classify destination failure as archive corruption`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue("Must use the _UNRESOLVED sentinel before checking archive integrity", script.contains("if [ \"\$_CLASSIFY_REASON\" = '_UNRESOLVED' ]; then"))
    }

    @Test
    fun `strategy B worker runs lz4 -t against the source archive when destination is healthy`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue("Must test archive integrity with lz4 -t", script.contains("lz4 -t -- '${archive.archivePath}'"))
        assertTrue("Must capture lz4 -t output for diagnostics", script.contains("\$LZ4TESTERR"))
    }

    @Test
    fun `strategy B worker reports LZ4_DECOMPRESS_FAILED when the archive integrity test fails`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val catTestErrLine = lines.indexOfFirst { it.contains("cat \"\$LZ4TESTERR\" 2>/dev/null") }
        assertTrue("Must cat the failed integrity-test output", catTestErrLine >= 0)
        val nearby = lines.subList(catTestErrLine, minOf(catTestErrLine + 3, lines.size))
        assertTrue(
            "A failed integrity test must be classified as LZ4_DECOMPRESS_FAILED",
            nearby.any { it.contains("_CLASSIFY_REASON='LZ4_DECOMPRESS_FAILED'") }
        )
    }

    @Test
    fun `strategy B worker reports LZ4_DECOMPRESS_FAILED when lz4 fails independently despite a valid archive`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(
            "A genuinely bad LZ4CODE (non-zero, non-141) with a valid archive must still be LZ4_DECOMPRESS_FAILED",
            script.contains("if [ \$LZ4CODE -ne 0 ] && [ \$LZ4CODE -ne 141 ]; then\n        _CLASSIFY_REASON='LZ4_DECOMPRESS_FAILED'")
        )
    }

    @Test
    fun `strategy B worker falls back to TAR_EXTRACT_FAILED when archive is intact and lz4 exit was clean`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(
            "A genuine tar-only failure with an intact archive must be TAR_EXTRACT_FAILED",
            script.contains("_CLASSIFY_REASON='TAR_EXTRACT_FAILED'")
        )
    }

    @Test
    fun `strategy B worker classification checks stderr patterns and mount before archive integrity`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val patternLine = lines.indexOfFirst { it.contains("grep -q 'No space left on device'") }
        val mountRecheckLine = lines.indexOfFirst { it.contains("_USB_LINE=") }
        val unresolvedCheckLine = lines.indexOfFirst { it.contains("if [ \"\$_CLASSIFY_REASON\" = '_UNRESOLVED' ]; then") }
        assertTrue("stderr pattern check must come first", patternLine in 0 until mountRecheckLine)
        assertTrue("mount recheck must come before the archive-integrity fallback", mountRecheckLine < unresolvedCheckLine)
    }

    @Test
    fun `strategy B worker requires jobDir-scoped error files not shared across jobs`() {
        val otherJobId = "ffffffff00000000"
        val scriptA = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val scriptB = UsbShellCommandBuilder.buildWorkerScriptContent(otherJobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue(scriptA.contains("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/tar.err"))
        assertTrue(scriptB.contains("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$otherJobId/tar.err"))
        assertFalse(scriptA.contains("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$otherJobId/tar.err"))
    }

    // ── readWorkerLogTailCommand ────────────────────────────────────────────────

    @Test
    fun `readWorkerLogTailCommand tails the job's worker log`() {
        val cmd = UsbShellCommandBuilder.readWorkerLogTailCommand(jobId)
        assertTrue(cmd.contains("tail -c"))
        assertTrue(cmd.contains("HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/worker.log"))
    }

    @Test
    fun `readWorkerLogTailCommand suppresses errors when the log is missing`() {
        val cmd = UsbShellCommandBuilder.readWorkerLogTailCommand(jobId)
        assertTrue(cmd.contains("2>/dev/null"))
    }

    @Test
    fun `readWorkerLogTailCommand respects a custom maxBytes`() {
        val cmd = UsbShellCommandBuilder.readWorkerLogTailCommand(jobId, maxBytes = 500)
        assertTrue(cmd.contains("tail -c 500"))
    }

    // ── Strategy A worker — trap/finally-style cleanup ─────────────────────────

    @Test
    fun `strategy A worker sets a trap on EXIT`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue("Worker must install an EXIT trap for guaranteed cleanup", script.contains("trap '") && script.contains("' EXIT"))
    }

    @Test
    fun `strategy A worker EXIT trap always removes the cp error scratch file`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val trapLine = script.lines().first { it.contains("trap '") && it.contains("' EXIT") }
        assertTrue("EXIT trap must remove \$CPERR unconditionally", trapLine.contains("rm -f \"\$CPERR\""))
    }

    @Test
    fun `strategy A worker EXIT trap only removes partial when not committed`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val trapLine = script.lines().first { it.contains("trap '") && it.contains("' EXIT") }
        assertTrue("EXIT trap must guard partial removal on the commit flag", trapLine.contains("_COMMITTED"))
        assertTrue("EXIT trap must remove \$PARTIAL when not committed", trapLine.contains("rm -rf \"\$PARTIAL\""))
    }

    @Test
    fun `strategy A worker marks committed only after a successful final rename`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        val lines = script.lines()
        val mvLine = lines.indexOfFirst { it.contains("mv \"\$PARTIAL\" \"\$FINAL\"") }
        val committedLine = lines.indexOfFirst { it.trim() == "_COMMITTED=1" }
        val successLine = lines.indexOfFirst { it.contains("SUCCESS:\$FINAL") }
        assertTrue("Must set _COMMITTED=1 after the mv call", committedLine > mvLine)
        assertTrue("Must set _COMMITTED=1 before writing SUCCESS", committedLine < successLine)
    }

    @Test
    fun `strategy A worker MV_RENAME_FAILED path leaves cleanup to the trap`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = true)
        assertTrue(script.contains("mv \"\$PARTIAL\" \"\$FINAL\" || { echo 'FAILED:MV_RENAME_FAILED'"))
        assertTrue("_COMMITTED must start at 0", script.contains("_COMMITTED=0"))
    }

    // ── Strategy B worker — trap/finally-style cleanup ─────────────────────────

    @Test
    fun `strategy B worker sets a trap on EXIT`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        assertTrue("Worker must install an EXIT trap for guaranteed cleanup", script.contains("trap '") && script.contains("' EXIT"))
    }

    @Test
    fun `strategy B worker EXIT trap always removes FIFO and tar-lz4 error scratch files`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val trapLine = script.lines().first { it.contains("trap '") && it.contains("' EXIT") }
        assertTrue(trapLine.contains("rm -f \"\$FIFO\""))
        assertTrue(trapLine.contains("\$TARERR\""))
        assertTrue(trapLine.contains("\$LZ4ERR\""))
        assertTrue(trapLine.contains("\$LZ4TESTERR\""))
    }

    @Test
    fun `strategy B worker EXIT trap only removes partial when not committed`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val trapLine = script.lines().first { it.contains("trap '") && it.contains("' EXIT") }
        assertTrue(trapLine.contains("_COMMITTED"))
        assertTrue(trapLine.contains("rm -rf \"\$PARTIAL\""))
    }

    @Test
    fun `strategy B worker marks committed only after a successful final rename`() {
        val script = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA = false)
        val lines = script.lines()
        val mvLine = lines.indexOfFirst { it.contains("mv \"\$PARTIAL\" \"\$FINAL\"") }
        val committedLine = lines.indexOfFirst { it.trim() == "_COMMITTED=1" }
        val successLine = lines.indexOfFirst { it.contains("SUCCESS:\$FINAL") }
        assertTrue("Must set _COMMITTED=1 after the mv call", committedLine > mvLine)
        assertTrue("Must set _COMMITTED=1 before writing SUCCESS", committedLine < successLine)
    }

    // ── buildCancelScript — jobDir scratch-file cleanup ────────────────────────

    @Test
    fun `cancel script also removes leftover error and FIFO scratch files in the job dir`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        assertTrue("Cancel script must remove *.err scratch files", script.contains("rm -f '$cancelJobDir'/*.err"))
        assertTrue("Cancel script must remove the FIFO", script.contains(".lz4fifo"))
    }

    @Test
    fun `cancel script never removes the status or pid files themselves`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        val cleanupLine = script.lines().first { it.contains("*.err") }
        assertFalse("Cleanup must not touch the status file", cleanupLine.contains("'$statusPath'"))
        assertFalse("Cleanup must not touch the pid file", cleanupLine.contains("'$pidPath'"))
    }

    @Test
    fun `cancel script removes scratch files before writing CANCELLED`() {
        val script = UsbShellCommandBuilder.buildCancelScript(cancelJobDir, pidPath, cancelPath, partialDir, statusPath)
        val lines = script.lines()
        val scratchCleanupLine = lines.indexOfFirst { it.contains("*.err") }
        val cancelledLine = lines.indexOfFirst { it.contains("'CANCELLED'") && it.contains("'$statusPath'") }
        assertTrue(scratchCleanupLine >= 0)
        assertTrue("Scratch cleanup should happen before the terminal CANCELLED write", scratchCleanupLine < cancelledLine)
    }

    // ── cleanupEmptySessionAndTraceMateDirsCommand ─────────────────────────────

    @Test
    fun `cleanupEmptySessionAndTraceMateDirsCommand uses rmdir not rm -rf`() {
        val cmd = UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand(sessionDir, usbMount)
        assertTrue("Must use rmdir (fails on non-empty dir) to guarantee safety", cmd.contains("rmdir '$sessionDir'"))
        assertTrue(cmd.contains("rmdir '${usbMount.mountPath}/HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER'"))
        assertFalse("Must never use rm -rf for this cleanup — could delete non-empty exports", cmd.contains("rm -rf"))
    }

    @Test
    fun `cleanupEmptySessionAndTraceMateDirsCommand suppresses rmdir errors so it is safe on non-empty dirs`() {
        val cmd = UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand(sessionDir, usbMount)
        val rmdirLines = cmd.lines().filter { it.trim().startsWith("rmdir") }
        assertEquals(2, rmdirLines.size)
        rmdirLines.forEach { assertTrue(it.contains("2>/dev/null")) }
    }

    @Test
    fun `cleanupEmptySessionAndTraceMateDirsCommand revalidates the mount before touching directories`() {
        val cmd = UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand(sessionDir, usbMount)
        val lines = cmd.lines()
        val checkLine = lines.indexOfFirst { it.contains("_USB_CHECK") && it.contains("CLEANUP_SKIPPED") }
        val firstRmdirLine = lines.indexOfFirst { it.trim().startsWith("rmdir") }
        assertTrue("Must check the live mount before removing anything", checkLine >= 0)
        assertTrue("Mount check must precede the rmdir calls", checkLine < firstRmdirLine)
    }

    @Test
    fun `cleanupEmptySessionAndTraceMateDirsCommand skips removal when the mount has changed`() {
        val cmd = UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand(sessionDir, usbMount)
        assertTrue(cmd.contains("CLEANUP_SKIPPED"))
    }

    @Test
    fun `cleanupEmptySessionAndTraceMateDirsCommand reports completion`() {
        val cmd = UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand(sessionDir, usbMount)
        assertTrue(cmd.contains("CLEANUP_DONE"))
    }

    // ── ADB payload constants sanity check ─────────────────────────────────────

    @Test
    fun `SHELL_SERVICE_OVERHEAD accounts for shell prefix and NUL`() {
        // "shell:" is 6 bytes, NUL terminator is 1 byte = 7 total
        assertEquals(7, SHELL_SERVICE_OVERHEAD)
    }

    @Test
    fun `MAX_PAYLOAD is 4096 bytes`() {
        assertEquals(4096, MAX_PAYLOAD)
    }
}
