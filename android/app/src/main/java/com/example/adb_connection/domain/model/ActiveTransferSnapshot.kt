package com.example.adb_connection.domain.model

/** Lifecycle of a single in-flight remote worker job, as tracked for crash recovery. */
enum class RemoteJobLifecycle {
    /** The jobId has been chosen and persisted, but the start script has not yet been confirmed launched. */
    LAUNCHING,
    /** The start script returned a PID — the worker is confirmed running (or was, last we checked). */
    RUNNING
}

/** Which half of a trigger's two-step transfer (normal, then offline-trace variant) a job belongs to. */
data class ActiveJobSnapshot(
    val jobId: String,
    val stem: String,
    val variant: ArchiveVariant,
    val useStrategyA: Boolean,
    val phase: RemoteJobLifecycle
)

/** Persisted per-archive results recorded so far, keyed by the archive's stem. */
data class TileResultSnapshot(
    val stem: String,
    val normalResult: ResultSummary? = null,
    val offlineResult: ResultSummary? = null
)

/**
 * Compact, pure (no Android dependency) snapshot of an in-progress transfer batch, persisted
 * to disk so [com.example.adb_connection.domain.usb.UsbTransferCoordinator] can reattach to a
 * still-running remote worker — or correctly resolve one that finished or died — after the
 * Android process hosting it is killed and recreated (screen-off doze kill, low-memory kill,
 * app swipe-away, etc.). See [com.example.adb_connection.domain.usb.ActiveTransferSnapshotCodec]
 * for the on-disk text encoding.
 */
data class ActiveTransferSnapshot(
    val expectedMount: UsbMount,
    val sessionDir: String,
    /** Selected NORMAL archives, in the order they are (or were being) processed. */
    val archives: List<TriggerArchive>,
    /** Index into [archives] currently being processed (or next to process if no job is in flight). */
    val currentIndex: Int,
    /** Non-null exactly while a remote worker for the current archive/variant is in flight. */
    val currentJob: ActiveJobSnapshot?,
    /** Results recorded so far, one entry per archive that has at least started. */
    val tileResults: List<TileResultSnapshot>,
    val cancelRequested: Boolean
)
