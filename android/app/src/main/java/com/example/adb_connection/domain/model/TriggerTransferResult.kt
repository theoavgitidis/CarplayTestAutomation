package com.example.adb_connection.domain.model

enum class SourceType {
    EXISTING_EXTRACTED_DIRECTORY,
    ARCHIVE_EXTRACTED_TO_USB
}

enum class FailureReason {
    SOURCE_ARCHIVE_MISSING,
    EXTRACTED_SOURCE_DISAPPEARED,
    USB_NOT_FOUND,
    USB_DISCONNECTED,
    USB_NOT_WRITABLE,
    USB_MOUNT_CHANGED,
    USB_FULL,
    USB_IO_ERROR,
    SESSION_DIR_CREATE_FAILED,
    LZ4_UNAVAILABLE,
    TAR_UNAVAILABLE,
    LZ4_DECOMPRESS_FAILED,
    TAR_EXTRACT_FAILED,
    PARTIAL_DIR_CREATE_FAILED,
    FIFO_CREATE_FAILED,
    CP_FAILED,
    MV_RENAME_FAILED,
    SYNC_FAILED,
    JOB_DIR_CREATE_FAILED,
    REMOTE_SCRIPT_LAUNCH_FAILED,
    WORKER_SCRIPT_WRITE_FAILED,
    STATUS_FILE_UNREADABLE,
    UNEXPECTED_STATUS,
    POLL_TIMEOUT,
    ADB_COMMUNICATION_ERROR,
    CANCELLATION_FAILED,
    CLEANUP_FAILED,
    // The remote worker process is no longer alive but never wrote a terminal status
    // (SUCCESS/FAILED/ALREADY_PRESENT/CANCELLED) — e.g. it was killed by the head unit's
    // OOM handling, or Android died mid-write of the status file. Discovered only during
    // service/process-recreation reattachment (UsbTransferRepository.inspectJob). The
    // batch must not blindly relaunch the same item; it is reported as a failure instead.
    WORKER_LOST,
    UNKNOWN
}

data class UsbTransferHandle(
    val jobId: String,
    val remotePid: Int,
    val statusPath: String,
    val cancelPath: String
)

sealed interface TriggerTransferResult {
    data class Success(
        val archive: TriggerArchive,
        val destinationPath: String,
        val sourceType: SourceType
    ) : TriggerTransferResult

    data class Failed(
        val archive: TriggerArchive,
        val reason: FailureReason,
        val message: String? = null
    ) : TriggerTransferResult

    data class Cancelled(
        val archive: TriggerArchive,
        val partialOutputRemoved: Boolean
    ) : TriggerTransferResult

    data class AlreadyPresent(
        val archive: TriggerArchive,
        val existingPath: String
    ) : TriggerTransferResult
}

/**
 * Result of the pre-launch checks (duplicate detection, strategy selection, source
 * existence) that must run before a remote worker is started. Split out from
 * [TriggerTransferResult] so a caller (e.g. a coordinator persisting recovery state)
 * can generate and persist a jobId *between* [PrepareOutcome.Ready] and actually
 * launching the worker.
 */
sealed interface PrepareOutcome {
    data class Ready(val useStrategyA: Boolean) : PrepareOutcome
    data class AlreadyPresent(val existingPath: String) : PrepareOutcome
    data class Failed(val reason: FailureReason, val message: String? = null) : PrepareOutcome
}

/** Result of starting the remote worker script for a caller-supplied jobId. Does not wait. */
sealed interface LaunchOutcome {
    data class Started(val handle: UsbTransferHandle) : LaunchOutcome
    data class Failed(val reason: FailureReason, val message: String? = null) : LaunchOutcome
}

/**
 * Result of inspecting a possibly-orphaned remote job during service/process-recreation
 * recovery, without waiting on it. Never launches anything.
 */
sealed interface JobInspection {
    /** The worker process is still running — caller should reattach via `awaitTransfer`. */
    data object Alive : JobInspection

    /** The worker process has exited and left a terminal status, already mapped. */
    data class Terminal(val result: TriggerTransferResult) : JobInspection

    /**
     * The worker process is no longer alive but the status file has no terminal token
     * (missing, blank, or still "RUNNING"). The job's true outcome is unknowable — the
     * caller must report [FailureReason.WORKER_LOST] rather than relaunch the same item.
     */
    data object Lost : JobInspection

    /** The head unit could not be reached at all while inspecting. */
    data class CommunicationFailure(val message: String?) : JobInspection
}
