package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.UsbMount
import com.example.adb_connection.domain.model.UsbTransferCoordinatorState
import kotlinx.coroutines.flow.StateFlow

/** Outcome of requesting a new batch via [UsbTransferCoordinator.start]. */
sealed interface CoordinatorStartResult {
    /** The batch was accepted and is now running in the coordinator's own scope. */
    data object Started : CoordinatorStartResult

    /** A batch is already [UsbTransferCoordinatorState.Running]; the request was ignored. */
    data object AlreadyRunning : CoordinatorStartResult

    /** Session directory creation failed before any transfer began — nothing was started. */
    data class SessionDirFailed(val reason: FailureReason, val message: String?) : CoordinatorStartResult
}

/**
 * Application-scoped owner of the USB export transfer batch. Unlike the old
 * `UsbCopyViewModel`-owned transfer `Job`, an implementation of this interface must run in a
 * scope that outlives any single ViewModel/Activity — screen-off, activity recreation,
 * navigation recomposition, or ViewModel destruction must not cancel the in-flight transfer or
 * cause a duplicate job to start. Only [requestCancel] may trigger the remote worker's
 * cancellation script.
 *
 * Obtainable as an application-wide singleton via `ServiceLocator`.
 */
interface UsbTransferCoordinator {

    /** Observable batch state: Idle / Running (with live tiles/progress/phase) / Completed. */
    val state: StateFlow<UsbTransferCoordinatorState>

    /**
     * Starts a new batch for the given [archives] (already-selected NORMAL archives; each is
     * paired internally with its offline-trace variant, exactly as the old ViewModel loop did).
     * Suspends only long enough to create the session directory — the actual transfer loop
     * then continues in the coordinator's own scope, independent of the caller's lifecycle.
     */
    suspend fun start(host: String, usbMount: UsbMount, archives: List<TriggerArchive>): CoordinatorStartResult

    /**
     * Explicit user cancellation. This is the *only* path that causes the remote worker's
     * process group to be killed and its partial output removed — ordinary coroutine or
     * service teardown must never call this.
     */
    fun requestCancel()

    /**
     * Called after service/process recreation to check for and reattach to a persisted
     * in-flight batch. Safe to call when there is no persisted batch, or when a batch is
     * already [UsbTransferCoordinatorState.Running] in this process (no-op, returns false).
     * Returns `true` if a persisted batch was found and recovery was attempted (successfully
     * reattached, or terminally resolved as lost/mount-invalid) — not necessarily that it is
     * still running by the time this returns, since recovery continues asynchronously.
     */
    suspend fun attachOrRecover(host: String): Boolean

    /** Clears a [UsbTransferCoordinatorState.Completed] state back to Idle. No-op otherwise. */
    fun dismissCompleted()
}
