package com.example.adb_connection.data.usb

import com.example.adb_connection.domain.model.JobInspection
import com.example.adb_connection.domain.model.LaunchOutcome
import com.example.adb_connection.domain.model.PrepareOutcome
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDeleteResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbEjectResult
import com.example.adb_connection.domain.model.UsbListingResult
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbRemountResult
import com.example.adb_connection.domain.model.UsbSessionDirResult
import com.example.adb_connection.domain.usb.UsbShellCommandBuilder
import kotlinx.coroutines.flow.StateFlow

interface UsbTransferRepository {

    suspend fun discoverArchives(host: String, port: Int): List<TriggerArchive>

    suspend fun detectUsbMount(host: String, port: Int): UsbMount?

    suspend fun detectUsbStatus(host: String, port: Int): UsbDetectionResult {
        val mount = detectUsbMount(host, port)
        return if (mount != null) UsbDetectionResult.Writable(mount) else UsbDetectionResult.NotFound
    }

    suspend fun checkDuplicate(
        host: String,
        port: Int,
        usbMount: UsbMount,
        stem: String
    ): String?

    /**
     * Lists valid app export directories in one bounded, read-only USB scan. A null result
     * means the listing was incomplete or could not be verified.
     */
    suspend fun listExistingExportDirectories(host: String, port: Int, usbMount: UsbMount): Map<String, String>? = emptyMap()

    suspend fun createSessionDir(
        host: String,
        port: Int,
        usbMount: UsbMount
    ): UsbSessionDirResult

    /**
     * Transfer one archive. Emits phase transitions via [onPhase] so the
     * caller can update the UI with an accurate operation label without the
     * repository needing a reference to the ViewModel or UI state.
     *
     * Convenience wrapper around [prepareTransfer] + [launchTransfer] + [awaitTransfer] for
     * callers that do not need to persist/control the jobId themselves (e.g. tests). The
     * default implementation composes those three; the real jobId used is not observable
     * to the caller. [UsbTransferCoordinator][com.example.adb_connection.domain.usb.UsbTransferCoordinator]
     * uses the split methods directly instead, so it can persist a recovery snapshot between steps.
     */
    suspend fun transferArchive(
        host: String,
        port: Int,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String,
        cancelSignal: StateFlow<Boolean>,
        onPhase: (TransferPhase) -> Unit = {}
    ): TriggerTransferResult {
        onPhase(TransferPhase.Checking(archive))
        val prepared = prepareTransfer(host, port, archive, usbMount, sessionDir)
        return when (prepared) {
            is PrepareOutcome.AlreadyPresent -> TriggerTransferResult.AlreadyPresent(archive, prepared.existingPath)
            is PrepareOutcome.Failed -> TriggerTransferResult.Failed(archive, prepared.reason, prepared.message)
            is PrepareOutcome.Ready -> {
                onPhase(
                    if (prepared.useStrategyA) TransferPhase.CopyingExisting(archive) else TransferPhase.Extracting(archive)
                )
                val jobId = UsbShellCommandBuilder.generateJobId()
                when (val launch = launchTransfer(host, port, jobId, archive, usbMount, sessionDir, prepared.useStrategyA)) {
                    is LaunchOutcome.Failed -> TriggerTransferResult.Failed(archive, launch.reason, launch.message)
                    is LaunchOutcome.Started -> awaitTransfer(
                        host, port, jobId, archive, sessionDir, usbMount, prepared.useStrategyA, cancelSignal, onPhase
                    )
                }
            }
        }
    }

    /**
     * Pre-launch checks only (duplicate detection, strategy selection, source existence).
     * Performs no writes and starts no remote job — split out so a caller can choose and
     * persist a jobId *before* actually launching (see [launchTransfer]).
     *
     * No default body is provided: this is genuinely new repository behavior. Fakes that
     * still only implement [transferArchive] directly (as in `UsbCopyViewModelTest`, which
     * predates the Coordinator split and no longer drives a real transfer loop) do not need
     * to override this — but any fake exercising the split path (e.g. a coordinator test)
     * must.
     */
    suspend fun prepareTransfer(
        host: String,
        port: Int,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String
    ): PrepareOutcome = throw NotImplementedError("prepareTransfer not implemented by this UsbTransferRepository")

    /**
     * Starts the remote worker for the caller-supplied [jobId] using the strategy already
     * decided by [prepareTransfer]. Does not wait for completion.
     */
    suspend fun launchTransfer(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        usbMount: UsbMount,
        sessionDir: String,
        useStrategyA: Boolean
    ): LaunchOutcome = throw NotImplementedError("launchTransfer not implemented by this UsbTransferRepository")

    /**
     * Blocks until the job identified by [jobId] reaches a terminal status, watching
     * [cancelSignal]. Works identically whether [jobId] was just launched in this process or
     * is being reattached to after a service/process recreation — the wait is driven entirely
     * by the remote status/pid files, not by any in-memory handle.
     */
    suspend fun awaitTransfer(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        sessionDir: String,
        usbMount: UsbMount,
        useStrategyA: Boolean,
        cancelSignal: StateFlow<Boolean>,
        onPhase: (TransferPhase) -> Unit = {}
    ): TriggerTransferResult = throw NotImplementedError("awaitTransfer not implemented by this UsbTransferRepository")

    /**
     * Inspects a possibly-orphaned job during recovery, without waiting on it or launching
     * anything. See [JobInspection] for the possible outcomes.
     */
    suspend fun inspectJob(
        host: String,
        port: Int,
        jobId: String,
        archive: TriggerArchive,
        useStrategyA: Boolean
    ): JobInspection = JobInspection.CommunicationFailure("Not implemented")

    suspend fun syncAfterTransfer(host: String, port: Int)

    /**
     * Best-effort cleanup run once after a transfer batch completes: removes the
     * session directory and, if now empty, the `TraceMate` directory. Implementations
     * must use an empty-directory-only removal (e.g. `rmdir`) so a directory still
     * holding a successful export is never deleted. Failures are non-fatal — the
     * default implementation is a no-op for callers/tests that don't need it.
     */
    suspend fun cleanupEmptySessionDir(host: String, port: Int, sessionDir: String, usbMount: UsbMount) {}

    suspend fun deleteAllUsbFiles(host: String, port: Int, usbMount: UsbMount): UsbDeleteResult

    suspend fun remountReadWrite(host: String, port: Int, usbMount: UsbMount): UsbRemountResult =
        UsbRemountResult.Failed("Not implemented")

    suspend fun ejectUsb(host: String, port: Int, usbMount: UsbMount): UsbEjectResult =
        UsbEjectResult.Failed("Not implemented")

    /**
     * Lists files and directories under <USB_MOUNT>/[relativeDirectory] and
     * returns a structured result.  Reuses the same USB mount detection as the copy feature.
     * [relativeDirectory] is relative to the USB mount (empty = managed-root listing).
     */
    suspend fun listUsbFiles(
        host: String,
        port: Int,
        relativeDirectory: String = ""
    ): UsbListingResult
}
