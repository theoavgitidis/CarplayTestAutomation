package com.example.adb_connection.data.usb

import android.util.Log
import com.example.adb_connection.data.adb.AdbProtocol
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.JobInspection
import com.example.adb_connection.domain.model.LaunchOutcome
import com.example.adb_connection.domain.model.PrepareOutcome
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbEjectResult
import com.example.adb_connection.domain.model.UsbFileEntry
import com.example.adb_connection.domain.model.UsbRemountResult
import com.example.adb_connection.domain.model.UsbFileType
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbSessionDirResult
import com.example.adb_connection.domain.model.UsbTransferHandle
import com.example.adb_connection.domain.usb.TriggerArchiveParser
import com.example.adb_connection.domain.usb.UsbFileListingParser
import com.example.adb_connection.domain.usb.UsbExistingExportParser
import com.example.adb_connection.domain.usb.UsbMountParser
import com.example.adb_connection.domain.usb.UsbShellCommandBuilder
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private const val TAG = "UsbTransferRepository"

private sealed interface UsbMountQueryResult {
    data class Success(val mounts: List<UsbMount>, val rejectedCandidateCount: Int) : UsbMountQueryResult
    data class Failed(val message: String?) : UsbMountQueryResult
}

class UsbTransferRepositoryImpl : UsbTransferRepository {

    override suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive> {
        val result = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.discoverArchivesCommand())
        if (!result.success) {
            Log.e(TAG, "Archive discovery failed: ${result.errorMessage}")
            throw java.io.IOException(result.errorMessage ?: "ADB shell execution failed")
        }
        return TriggerArchiveParser.parseDiscoveryOutput(result.output)
    }

    override suspend fun detectUsbMount(host: String, port: Int): UsbMount? {
        return (detectUsbStatus(host, port) as? UsbDetectionResult.Writable)?.mount
    }

    override suspend fun detectUsbStatus(host: String, port: Int): UsbDetectionResult {
        val query = queryUsbMounts(host, port)
        val successfulQuery = when (query) {
            is UsbMountQueryResult.Success -> query
            is UsbMountQueryResult.Failed -> return UsbDetectionResult.QueryFailed(query.message)
        }
        val mounts = successfulQuery.mounts
        if (mounts.isEmpty()) {
            return if (successfulQuery.rejectedCandidateCount > 0) {
                UsbDetectionResult.UnsupportedMountLayout(successfulQuery.rejectedCandidateCount)
            } else {
                UsbDetectionResult.NotFound
            }
        }
        val writableMounts = mounts.filter { it.isWritable }
        if (writableMounts.size == 1) return UsbDetectionResult.Writable(writableMounts.single())
        if (writableMounts.size > 1) return UsbDetectionResult.MultipleWritableMounts(writableMounts)
        return if (mounts.size == 1) {
            UsbDetectionResult.ReadOnly(mounts.single())
        } else {
            UsbDetectionResult.MultipleReadOnlyMounts(mounts)
        }
    }

    private suspend fun queryUsbMounts(host: String, port: Int): UsbMountQueryResult {
        val result = AdbProtocol.executeShellCommand(
            host, port,
            UsbShellCommandBuilder.detectUsbMountsCommand()
        )
        if (!result.success) {
            Log.w(TAG, "USB mount query failed: ${result.errorMessage}")
            return UsbMountQueryResult.Failed(result.errorMessage)
        }
        val parsed = UsbMountParser.parseWithDiagnostics(result.output)
        return UsbMountQueryResult.Success(parsed.mounts, parsed.rejectedCandidateCount)
    }

    override suspend fun checkDuplicate(
        host: String,
        port: Int,
        usbMount: UsbMount,
        stem: String
    ): String? {
        if (!TriggerArchiveParser.isStemValid(stem)) {
            Log.w(TAG, "Invalid stem rejected for duplicate check: $stem")
            return null
        }
        val result = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.checkDuplicateCommand(usbMount, stem))
        val path = result.output.trim()
        return if (result.success && path.isNotBlank()) path else null
    }

    override suspend fun listExistingExportDirectories(
        host: String,
        port: Int,
        usbMount: UsbMount
    ): Map<String, String>? {
        val result = AdbProtocol.executeShellCommand(
            host, port, UsbShellCommandBuilder.listExistingExportDirectoriesCommand(usbMount)
        )
        if (!result.success) {
            Log.w(TAG, "Existing USB export listing failed: ${result.errorMessage}")
            return null
        }
        return UsbExistingExportParser.parse(result.output, usbMount.mountPath)
    }

    override suspend fun createSessionDir(
        host: String,
        port: Int,
        usbMount: UsbMount
    ): UsbSessionDirResult {
        val result = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.createSessionDirCommand(usbMount))
        if (!result.success) {
            Log.e(TAG, "Session dir creation failed: ${result.errorMessage}")
            return UsbSessionDirResult.Failed(FailureReason.ADB_COMMUNICATION_ERROR, result.errorMessage)
        }
        val output = result.output.trim()
        return when {
            output.startsWith("OK:") -> UsbSessionDirResult.Success(output.removePrefix("OK:").trim())
            output.startsWith("FAIL:") -> {
                val token = output.removePrefix("FAIL:").trim()
                val reason = parseFailureReason(token)
                // parseFailureReason falls back to UNKNOWN for unrecognised tokens — that
                // covers the plain mkdir failure case (token is the target path, not a
                // validation code), which is really a generic session-dir-create failure.
                val effectiveReason = if (reason == FailureReason.UNKNOWN) FailureReason.SESSION_DIR_CREATE_FAILED else reason
                UsbSessionDirResult.Failed(effectiveReason, token)
            }
            else -> UsbSessionDirResult.Failed(FailureReason.SESSION_DIR_CREATE_FAILED, output)
        }
    }

    override suspend fun prepareTransfer(
        host: String,
        port: Int,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String
    ): PrepareOutcome {
        val existingPath = checkDuplicate(host, port, usbMount, archive.stem)
        if (existingPath != null) {
            Log.d(TAG, "Archive already present on USB: $existingPath")
            return PrepareOutcome.AlreadyPresent(existingPath)
        }

        val useStrategyA = checkStrategyA(host, port, archive)
        Log.d(TAG, "Transfer strategy for ${archive.stem}: ${if (useStrategyA) "A (existing dir)" else "B (extract archive)"}")

        if (!useStrategyA) {
            val archiveExists = checkArchiveExists(host, port, archive)
            if (!archiveExists) {
                Log.w(TAG, "Archive file missing, skipping: ${archive.archivePath}")
                return PrepareOutcome.Failed(FailureReason.SOURCE_ARCHIVE_MISSING)
            }
        }
        return PrepareOutcome.Ready(useStrategyA)
    }

    override suspend fun launchTransfer(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String,
        useStrategyA: Boolean
    ): LaunchOutcome {
        if (!TriggerArchiveParser.isJobIdValid(jobId)) {
            Log.e(TAG, "Job ID is invalid: $jobId")
            return LaunchOutcome.Failed(FailureReason.REMOTE_SCRIPT_LAUNCH_FAILED, "Invalid job ID: $jobId")
        }
        return when (val launch = launchRemoteJob(host, port, jobId, archive, usbMount, sessionDir, useStrategyA)) {
            is LaunchResult.Started -> {
                Log.d(TAG, "Remote job launched: jobId=$jobId pid=${launch.handle.remotePid}")
                LaunchOutcome.Started(launch.handle)
            }
            is LaunchResult.RecoveredRunning -> {
                Log.d(TAG, "Remote job recovered (still running after launch timeout): jobId=$jobId")
                LaunchOutcome.Started(launch.handle)
            }
            is LaunchResult.RecoveredTerminal -> {
                // Worker already completed before we could read the STARTED reply.
                // Map the terminal result back to a LaunchOutcome that the coordinator
                // can consume without going through awaitTransfer.
                Log.d(TAG, "Remote job recovered as already terminal: jobId=$jobId result=${launch.result}")
                when (val r = launch.result) {
                    is TriggerTransferResult.Success -> LaunchOutcome.Started(
                        // awaitTransfer is still called; it will read the terminal status
                        // from the device's status file and return immediately.
                        UsbTransferHandle(
                            jobId = jobId,
                            remotePid = -1,
                            statusPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/status",
                            cancelPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/cancel"
                        )
                    )
                    is TriggerTransferResult.AlreadyPresent -> LaunchOutcome.Started(
                        UsbTransferHandle(
                            jobId = jobId,
                            remotePid = -1,
                            statusPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/status",
                            cancelPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/cancel"
                        )
                    )
                    is TriggerTransferResult.Cancelled -> LaunchOutcome.Started(
                        UsbTransferHandle(
                            jobId = jobId,
                            remotePid = -1,
                            statusPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/status",
                            cancelPath = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId/cancel"
                        )
                    )
                    is TriggerTransferResult.Failed -> LaunchOutcome.Failed(r.reason, r.message)
                }
            }
            is LaunchResult.Failed -> LaunchOutcome.Failed(launch.reason, launch.message)
        }
    }

    override suspend fun awaitTransfer(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        sessionDir: String,
        usbMount: UsbMount,
        useStrategyA: Boolean,
        cancelSignal: StateFlow<Boolean>,
        onPhase: (TransferPhase) -> Unit
    ): TriggerTransferResult {
        val jobDir = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId"
        return waitForCompletion(
            host, port, jobId, jobDir, archive, sessionDir, usbMount, useStrategyA, cancelSignal, onPhase
        )
    }

    override suspend fun inspectJob(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        useStrategyA: Boolean
    ): JobInspection {
        val result = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.inspectJobCommand(jobId))
        if (!result.success) {
            Log.w(TAG, "Job inspection failed for $jobId: ${result.errorMessage}")
            return JobInspection.CommunicationFailure(result.errorMessage)
        }
        val lines = result.output.lines()
        val aliveLine = lines.firstOrNull { it.startsWith("ALIVE:") }
        val statusLine = lines.firstOrNull { it.startsWith("STATUS:") }
        val alive = aliveLine?.removePrefix("ALIVE:")?.trim() == "1"
        val status = statusLine?.removePrefix("STATUS:")?.trim().orEmpty()

        if (alive) {
            Log.d(TAG, "Job $jobId still alive on reattach")
            return JobInspection.Alive
        }
        if (status.isBlank() || status == "RUNNING") {
            Log.w(TAG, "Job $jobId is dead with no terminal status (status='$status') — worker lost")
            return JobInspection.Lost
        }
        val mapped = mapStatus(status, archive, useStrategyA)
        val enriched = if (mapped is TriggerTransferResult.Failed) enrichWithWorkerLog(host, port, jobId, mapped) else mapped
        return JobInspection.Terminal(enriched)
    }



    override suspend fun syncAfterTransfer(host: String, port: Int) {
        try {
            AdbProtocol.executeShellCommand(host, port, "sync")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) { /* best-effort */ }
    }

    // Best-effort, run once after a batch finishes. Uses rmdir (fails harmlessly on a
    // non-empty directory) so a session directory or TraceMate directory still holding
    // a successful export is never removed.
    override suspend fun cleanupEmptySessionDir(host: String, port: Int, sessionDir: String, usbMount: UsbMount) {
        try {
            AdbProtocol.executeShellCommand(
                host, port,
                UsbShellCommandBuilder.cleanupEmptySessionAndTraceMateDirsCommand(sessionDir, usbMount)
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Empty session/TraceMate dir cleanup failed: ${e.message}")
        }
    }

    override suspend fun remountReadWrite(host: String, port: Int, usbMount: UsbMount): UsbRemountResult {
        val remountResult = AdbProtocol.executeShellCommand(
            host, port,
            UsbShellCommandBuilder.remountReadWriteCommand(usbMount)
        )
        if (!remountResult.output.contains("REMOUNT_OK")) {
            val msg = remountResult.output.trim().ifBlank { remountResult.errorMessage ?: "mount remount failed" }
            Log.w(TAG, "Remount failed for ${usbMount.mountPath}: $msg")
            return UsbRemountResult.Failed("Remount failed: $msg")
        }
        // Re-read /proc/mounts to verify the mount is now rw with the same device.
        val mounts = when (val query = queryUsbMounts(host, port)) {
            is UsbMountQueryResult.Success -> query.mounts
            is UsbMountQueryResult.Failed -> return UsbRemountResult.Failed(query.message ?: "USB mount query failed")
        }
        val verified = mounts.firstOrNull {
            it.mountPath == usbMount.mountPath && it.devicePath == usbMount.devicePath && it.isWritable
        }
        return if (verified != null) {
            Log.d(TAG, "Remount verified rw: ${verified.mountPath}")
            UsbRemountResult.Success(verified)
        } else {
            val afterMount = mounts.firstOrNull { it.mountPath == usbMount.mountPath }
            val detail = when {
                afterMount == null -> "mount entry disappeared after remount"
                afterMount.devicePath != usbMount.devicePath -> "device changed after remount"
                else -> "mount is still read-only after remount"
            }
            Log.w(TAG, "Remount verification failed for ${usbMount.mountPath}: $detail")
            UsbRemountResult.Failed(detail)
        }
    }

    override suspend fun ejectUsb(host: String, port: Int, usbMount: UsbMount): UsbEjectResult {
        val result = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.ejectUsbCommand(usbMount))
        return if (result.output.contains("EJECTED")) UsbEjectResult.Success
        else UsbEjectResult.Failed(result.output.trim().ifBlank { result.errorMessage ?: "umount failed" })
    }

    override suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult {
        val result = AdbProtocol.executeShellCommand(
            host, port,
            UsbShellCommandBuilder.deleteAllTraceMateFilesCommand(usbMount)
        )
        if (!result.success && result.exitCode == null) {
            return UsbDeleteResult.AdbCommandFailed(result.errorMessage ?: "ADB shell failed")
        }
        return when (result.output.lineSequence().lastOrNull { it.isNotBlank() }?.trim()) {
            "TRACEMATE_DELETE:SUCCESS", "TRACEMATE_DELETE:ALREADY_ABSENT" -> UsbDeleteResult.Success
            "TRACEMATE_DELETE:FAILED:USB_DISCONNECTED" ->
                UsbDeleteResult.UsbNotConnected("USB removed during deletion")
            "TRACEMATE_DELETE:FAILED:USB_MOUNT_CHANGED" ->
                UsbDeleteResult.UsbMountChanged("A different USB device is mounted at the expected path")
            "TRACEMATE_DELETE:FAILED:USB_NOT_WRITABLE" ->
                UsbDeleteResult.UsbNotWritable("USB stick is read-only")
            else -> UsbDeleteResult.AdbCommandFailed("USB deletion did not complete safely")
        }
    }

    override suspend fun listUsbFiles(
        host: String,
        port: Int,
        relativeDirectory: String
    ): UsbListingResult {
        // Browsing is allowed on both rw and ro mounts — use any real mount.
        val mounts = when (val query = queryUsbMounts(host, port)) {
            is UsbMountQueryResult.Success -> query.mounts
            is UsbMountQueryResult.Failed -> return UsbListingResult.AdbCommandFailed(query.message ?: "USB mount query failed")
        }
        val mount = mounts.firstOrNull()
            ?: return UsbListingResult.UsbNotConnected()

        // Run the shallow directory listing command.
        val listResult = AdbProtocol.executeShellCommand(
            host, port,
            UsbShellCommandBuilder.listTraceMateDirectoryCommand(mount, relativeDirectory)
        )

        if (listResult.output.contains("STATUS:NOT_FOUND")) {
            return UsbListingResult.DirectoryNotFound(mount, relativeDirectory)
        }

        if (!listResult.success && listResult.exitCode == null) {
            Log.e(TAG, "USB listing failed: ${listResult.errorMessage}")
            return UsbListingResult.AdbCommandFailed(
                listResult.errorMessage ?: "ADB shell failed"
            )
        }

        val parsed = UsbFileListingParser.parse(listResult.output, mount.mountPath)

        if (!parsed.complete) {
            Log.w(TAG, "USB listing incomplete — USB likely removed")
            return UsbListingResult.UsbRemovedDuringListing(mount, parsed.entries)
        }

        val sorted = parsed.entries.sortedWith(
            compareBy<UsbFileEntry> { it.type != UsbFileType.DIRECTORY }
                .thenBy { it.name.lowercase() }
        )

        return UsbListingResult.Success(mount, sorted)
    }

    private suspend fun checkStrategyA(host: String, port: Int, archive: TriggerArchive): Boolean {
        val result = AdbProtocol.executeShellCommand(
            host, port,
            UsbShellCommandBuilder.checkStrategyACommand(archive.extractedDirectoryPath)
        )
        return result.success && result.output.trim() == "YES"
    }

    private suspend fun checkArchiveExists(host: String, port: Int, archive: TriggerArchive): Boolean {
        val result = AdbProtocol.executeShellCommand(
            host, port,
            UsbShellCommandBuilder.checkArchiveExistsCommand(archive.archivePath)
        )
        return result.success && result.output.trim() == "YES"
    }

    // Result of launching the remote worker job. A dedicated result type (rather than
    // a nullable handle) lets launch-time failures — including USB revalidation
    // failures reported by buildJobStartScript — propagate a specific FailureReason
    // instead of a generic REMOTE_SCRIPT_LAUNCH_FAILED.
    internal sealed interface LaunchResult {
        data class Started(val handle: UsbTransferHandle) : LaunchResult
        data class Failed(val reason: FailureReason, val message: String? = null) : LaunchResult
        // The launch ADB call timed out or returned ambiguous output, but the job was
        // found to be running or already terminal on the device after a recovery probe.
        // The caller should treat this exactly like Started — awaitTransfer reads pid/status
        // from the device's files and does not need the in-memory PID from a STARTED line.
        data class RecoveredRunning(val handle: UsbTransferHandle) : LaunchResult
        data class RecoveredTerminal(val result: TriggerTransferResult) : LaunchResult
    }

    private suspend fun launchRemoteJob(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String,
        useStrategyA: Boolean
    ): LaunchResult {
        val jobDir = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId"

        // Step 1: validate mount and create the job directory.
        val prepareResult = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.buildJobPrepareScript(jobId, usbMount))
        if (!prepareResult.success) {
            Log.e(TAG, "Job prepare step failed: ${prepareResult.errorMessage}")
            return LaunchResult.Failed(FailureReason.ADB_COMMUNICATION_ERROR, prepareResult.errorMessage)
        }
        val prepareOutput = prepareResult.output.trim()
        if (prepareOutput.startsWith("ERROR:")) {
            val token = prepareOutput.removePrefix("ERROR:").trim()
            Log.e(TAG, "Job prepare returned error: $prepareOutput")
            return LaunchResult.Failed(parseFailureReason(token), prepareOutput)
        }
        if (!prepareOutput.contains("JOBDIR_READY")) {
            Log.e(TAG, "Unexpected prepare output: $prepareOutput")
            return LaunchResult.Failed(FailureReason.JOB_DIR_CREATE_FAILED, prepareOutput)
        }

        // Step 2: write worker.sh to the job directory in safe chunks.
        val workerContent = UsbShellCommandBuilder.buildWorkerScriptContent(jobId, archive, usbMount, sessionDir, useStrategyA)
        val writeResult = AdbProtocol.writeRemoteFile(host, port, "$jobDir/worker.sh", workerContent)
        if (!writeResult.success) {
            Log.e(TAG, "Worker script write failed: ${writeResult.errorMessage}")
            return LaunchResult.Failed(FailureReason.WORKER_SCRIPT_WRITE_FAILED, writeResult.errorMessage)
        }

        // Step 3: launch the worker with full process-group detachment.
        val launchResult = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.buildJobLaunchScript(jobId))
        val launchOutput = launchResult.output.trim()

        // Parse the happy path first.
        if (launchResult.success) {
            val started = launchOutput.lines().firstOrNull { it.startsWith("STARTED:") }
            if (started != null) {
                val pid = started.removePrefix("STARTED:").trim().toIntOrNull()
                if (pid != null && pid > 0) {
                    return LaunchResult.Started(
                        UsbTransferHandle(
                            jobId = jobId,
                            remotePid = pid,
                            statusPath = "$jobDir/status",
                            cancelPath = "$jobDir/cancel"
                        )
                    )
                }
                Log.e(TAG, "Malformed STARTED line in launch output: $launchOutput")
                // Fall through to recovery — the script may have launched despite the bad output.
            }
            // No STARTED line even though the ADB call "succeeded" (e.g. truncated read).
            // Fall through to recovery.
            Log.w(TAG, "No STARTED line in launch output (adb success), probing job state: $launchOutput")
        } else {
            // ADB call itself failed — connection dropped or timed out after the head unit
            // already received and executed the launch command. Probe before giving up.
            Log.w(TAG, "Launch ADB call failed (${launchResult.errorMessage}), probing job state")
        }

        // Recovery probe: query the device's pid/status files to determine whether the
        // worker actually started. This avoids incorrectly reporting a failed launch when
        // the head unit executed the script but the ADB read timed out.
        val inspection = inspectJob(host, port, jobId, archive, useStrategyA)
        return interpretLaunchRecovery(inspection, jobId, launchResult.errorMessage)
    }

    // Pure mapping from a post-launch-timeout job inspection to a LaunchResult.
    // Extracted so it can be exercised in unit tests without ADB mocks.
    internal fun interpretLaunchRecovery(
        inspection: JobInspection,
        jobId: String,
        originalError: String?
    ): LaunchResult {
        val jobDir = "HEAD_UNIT_TRANSFER_WORK_DIR_PLACEHOLDER/$jobId"
        return when (inspection) {
            is JobInspection.Alive -> {
                // Worker is running on the device. awaitTransfer reads pid/status from
                // device files directly, so the in-memory remotePid value is a sentinel.
                LaunchResult.RecoveredRunning(
                    UsbTransferHandle(
                        jobId = jobId,
                        remotePid = -1,
                        statusPath = "$jobDir/status",
                        cancelPath = "$jobDir/cancel"
                    )
                )
            }
            is JobInspection.Terminal -> LaunchResult.RecoveredTerminal(inspection.result)
            is JobInspection.Lost -> {
                // Job directory or pid file exists (inspectJob reached it) but the worker
                // is not running and left no terminal status — ambiguous start failure.
                LaunchResult.Failed(
                    FailureReason.REMOTE_SCRIPT_LAUNCH_FAILED,
                    "Launch timed out and job left no terminal status (original: $originalError)"
                )
            }
            is JobInspection.CommunicationFailure -> {
                // Cannot reach the device even for the recovery probe — original error stands.
                LaunchResult.Failed(
                    FailureReason.ADB_COMMUNICATION_ERROR,
                    originalError ?: inspection.message
                )
            }
        }
    }

    // No total timeout: archive sizes are unknown and transfers may take very long.
    // The user must cancel manually via the Stop button.
    // A single ADB connection is held open for the full duration; the device-side shell
    // watches the worker PID and unblocks when it exits, avoiding repeated reconnects
    // that fail under heavy disk I/O.
    //
    // Takes jobId/jobDir directly (rather than a UsbTransferHandle) so the exact same wait
    // logic can be used both right after launch and when reattaching to an already-running
    // job after a service/process recreation — in the reattach case no in-memory handle
    // (with its remotePid) is available or needed, since the wait is entirely driven by the
    // remote pid/status files.
    private suspend fun waitForCompletion(
        host: String,
        port: Int,
        jobId: String,
        jobDir: String,
        archive: TriggerArchive,
        sessionDir: String,
        usbMount: UsbMount,
        useStrategyA: Boolean,
        cancelSignal: StateFlow<Boolean>,
        onPhase: (TransferPhase) -> Unit
    ): TriggerTransferResult = coroutineScope {
        val pidPath = "$jobDir/pid"
        val statusPath = "$jobDir/status"
        val cancelPath = "$jobDir/cancel"
        val partialDir = "$sessionDir/.${archive.stem}.partial"

        val waitDeferred = async {
            AdbProtocol.executeShellCommand(
                host, port,
                UsbShellCommandBuilder.blockingWaitAndReadStatusCommand(pidPath, statusPath),
                readTimeoutMs = 0  // infinite — transfer duration is unbounded
            )
        }

        // Watches _cancelRequested; when set, sends the cancel script over a second
        // short-lived connection. Does NOT cancel waitDeferred — the device-side kill
        // writes CANCELLED to the status file, so the blocking wait returns naturally.
        val cancelResult = CompletableDeferred<TriggerTransferResult>()
        val cancelWatcher = launch {
            cancelSignal.first { it }
            Log.d(TAG, "Cancel requested for job $jobId")
            onPhase(TransferPhase.Stopping(archive))
            val result = executeCancel(host, port, jobDir, pidPath, cancelPath, partialDir, statusPath, archive)
            cancelResult.complete(result)
            if (result is TriggerTransferResult.Failed) {
                // Do not keep the primary ADB connection blocked after cancellation could not
                // be confirmed. The failure keeps the batch snapshot available for recovery.
                waitDeferred.cancel()
            }
        }

        try {
            val pollResult = waitDeferred.await()
            cancelWatcher.cancel()
            if (!pollResult.success) {
                Log.e(TAG, "Blocking wait failed for job $jobId: ${pollResult.errorMessage}")
                return@coroutineScope TriggerTransferResult.Failed(
                    archive, FailureReason.ADB_COMMUNICATION_ERROR, pollResult.errorMessage
                )
            }
            val result = interpretStatus(pollResult.output.trim(), jobId, archive, useStrategyA)
            if (result is TriggerTransferResult.Failed) {
                // Best-effort: enrich with a worker.log tail (captured cp/tar/lz4 stderr)
                // so the user sees a concrete reason instead of just the status token.
                enrichWithWorkerLog(host, port, jobId, result)
            } else {
                result
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelWatcher.cancel()
            if (cancelResult.isCompleted) {
                return@coroutineScope cancelResult.await()
            }
            throw e
        }
    }

    // Not suspend: the worker already performed the final duplicate check before writing
    // SUCCESS, so no extra ADB round-trip is needed here.
    private fun interpretStatus(
        status: String,
        jobId: String,
        archive: TriggerArchive,
        useStrategyA: Boolean
    ): TriggerTransferResult {
        val result = mapStatus(status, archive, useStrategyA)
        when (result) {
            is TriggerTransferResult.Success -> Log.d(TAG, "Transfer succeeded: ${result.destinationPath}")
            is TriggerTransferResult.Failed -> Log.e(TAG, "Remote job failed: $status")
            is TriggerTransferResult.AlreadyPresent -> Log.d(TAG, "Worker detected duplicate: ${result.existingPath}")
            is TriggerTransferResult.Cancelled -> Log.d(TAG, "Remote job cancelled")
        }
        return result
    }

    // Best-effort read of the job's worker.log for a concise diagnostic. Any ADB
    // failure here must not mask the original FailureReason — swallow and return
    // the failure unchanged rather than throwing.
    private suspend fun enrichWithWorkerLog(
        host: String,
        port: Int,
        jobId: String,
        failed: TriggerTransferResult.Failed
    ): TriggerTransferResult.Failed {
        val logTail = try {
            val result = AdbProtocol.executeShellCommand(host, port, UsbShellCommandBuilder.readWorkerLogTailCommand(jobId))
            result.output.trim().takeIf { result.success && it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read worker.log for job $jobId: ${e.message}")
            null
        }
        return appendWorkerLogDiagnostic(failed, logTail)
    }

    // Pure — no I/O, so the diagnostic-formatting behavior can be exercised in
    // unit tests without ADB mocks. Keeps mapStatus's status-token mapping
    // (tested separately) unaffected by log-tail formatting concerns.
    internal fun appendWorkerLogDiagnostic(
        failed: TriggerTransferResult.Failed,
        workerLogTail: String?
    ): TriggerTransferResult.Failed {
        val diagnostic = workerLogTail
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.takeLast(5)
            ?.joinToString(" | ")
            ?.takeIf { it.isNotBlank() }
            ?: return failed
        val combinedMessage = if (failed.message.isNullOrBlank()) diagnostic else "${failed.message} — $diagnostic"
        return failed.copy(message = combinedMessage)
    }

    // Pure mapping — no Log calls, so it can be exercised in unit tests without Android mocks.
    internal fun mapStatus(
        status: String,
        archive: TriggerArchive,
        useStrategyA: Boolean
    ): TriggerTransferResult = when {
        status.startsWith("SUCCESS:") -> {
            val destPath = status.removePrefix("SUCCESS:").trim()
            val sourceType = if (useStrategyA) SourceType.EXISTING_EXTRACTED_DIRECTORY else SourceType.ARCHIVE_EXTRACTED_TO_USB
            TriggerTransferResult.Success(archive, destPath, sourceType)
        }
        status.startsWith("FAILED:") -> {
            val token = status.removePrefix("FAILED:").trim()
            val reason = parseFailureReason(token)
            TriggerTransferResult.Failed(archive, reason, "${reason.name}: $status")
        }
        status.startsWith("ALREADY_PRESENT:") -> {
            val path = status.removePrefix("ALREADY_PRESENT:").trim()
            TriggerTransferResult.AlreadyPresent(archive, path)
        }
        status == "CANCELLED" -> TriggerTransferResult.Cancelled(archive, partialOutputRemoved = true)
        else -> TriggerTransferResult.Failed(archive, FailureReason.UNEXPECTED_STATUS, status)
    }

    private suspend fun executeCancel(
        host: String,
        port: Int,
        jobDir: String,
        pidPath: String,
        cancelPath: String,
        partialDir: String,
        statusPath: String,
        archive: TriggerArchive
    ): TriggerTransferResult {
        val cancelScript = UsbShellCommandBuilder.buildCancelScript(jobDir, pidPath, cancelPath, partialDir, statusPath)
        val result = AdbProtocol.executeShellCommand(host, port, cancelScript)
        val partialRemoved = result.success && result.output.contains("CANCEL_DONE")
        Log.d(TAG, "Cancellation executed for ${archive.stem}, confirmed=$partialRemoved")
        return if (partialRemoved) {
            TriggerTransferResult.Cancelled(archive, partialOutputRemoved = true)
        } else {
            TriggerTransferResult.Failed(
                archive,
                FailureReason.ADB_COMMUNICATION_ERROR,
                result.output.trim().ifBlank { result.errorMessage ?: "Could not confirm remote worker cancellation" }
            )
        }
    }

    internal fun parseFailureReason(reason: String): FailureReason = when (reason) {
        "SOURCE_ARCHIVE_MISSING" -> FailureReason.SOURCE_ARCHIVE_MISSING
        "PARTIAL_DIR_CREATE_FAILED" -> FailureReason.PARTIAL_DIR_CREATE_FAILED
        "CP_FAILED" -> FailureReason.CP_FAILED
        "MV_RENAME_FAILED" -> FailureReason.MV_RENAME_FAILED
        "FIFO_CREATE_FAILED" -> FailureReason.FIFO_CREATE_FAILED
        "LZ4_UNAVAILABLE" -> FailureReason.LZ4_UNAVAILABLE
        "LZ4_DECOMPRESS_FAILED" -> FailureReason.LZ4_DECOMPRESS_FAILED
        "TAR_EXTRACT_FAILED" -> FailureReason.TAR_EXTRACT_FAILED
        "JOB_DIR_CREATE_FAILED" -> FailureReason.JOB_DIR_CREATE_FAILED
        // Emitted by the shared USB mount-revalidation snippet (see
        // UsbShellCommandBuilder.mountValidationSnippet), run immediately before
        // session dir creation, worker launch, and the final rename/commit.
        "USB_NOT_FOUND" -> FailureReason.USB_NOT_FOUND
        "USB_DISCONNECTED" -> FailureReason.USB_DISCONNECTED
        "USB_NOT_WRITABLE" -> FailureReason.USB_NOT_WRITABLE
        "USB_MOUNT_CHANGED" -> FailureReason.USB_MOUNT_CHANGED
        // Emitted by UsbShellCommandBuilder.classifyDestinationFailureSnippet after a
        // cp/tar/lz4 failure, parsed from the captured stderr of those tools.
        "USB_FULL" -> FailureReason.USB_FULL
        "USB_IO_ERROR" -> FailureReason.USB_IO_ERROR
        else -> FailureReason.UNKNOWN
    }
}
