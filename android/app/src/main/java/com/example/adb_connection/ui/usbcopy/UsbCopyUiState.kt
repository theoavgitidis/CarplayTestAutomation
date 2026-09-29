package com.example.adb_connection.ui.usbcopy

import com.example.adb_connection.domain.model.ArchiveTileState
import com.example.adb_connection.domain.model.TransferPhase
import com.example.adb_connection.domain.model.TransferProgressState
import com.example.adb_connection.domain.model.TriggerTransferResult
import com.example.adb_connection.domain.model.UsbDetectionResult
import com.example.adb_connection.domain.model.UsbMount

internal fun variantResultText(result: TriggerTransferResult?): String = when (result) {
    is TriggerTransferResult.Success -> "Copied"
    is TriggerTransferResult.AlreadyPresent -> "Already on USB"
    is TriggerTransferResult.Failed -> result.message ?: "Failed: ${result.reason.name}"
    is TriggerTransferResult.Cancelled -> "Cancelled"
    null -> "Not started"
}


fun operationLabel(phase: TransferPhase?): String = when (phase) {
    is TransferPhase.Checking -> "Checking ${phase.archive.displayLabel} ${phase.archive.variantLabel}"
    is TransferPhase.CopyingExisting -> "Copying ${phase.archive.displayLabel} ${phase.archive.variantLabel}"
    is TransferPhase.Extracting -> "Extracting ${phase.archive.displayLabel} ${phase.archive.variantLabel}"
    is TransferPhase.Stopping -> "Stopping ${phase.archive.displayLabel} ${phase.archive.variantLabel}"
    null -> "Preparing..."
}

/**
 * Derives the result overlay headline from the final progress snapshot.
 *
 * Rules (evaluated in priority order):
 * 1. Any cancelled or not-started item → user pressed Stop → "Copy stopped"
 * 2. Failures only (no success / already-present) → "Copy failed"
 * 3. Mix of success and failure → "Copy completed with errors"
 * 4. All items succeeded or were already present → "Copy completed"
 *
 * AlreadyPresent is not counted as an error.
 */
fun headline(progress: TransferProgressState): String = when {
    progress.cancelled > 0 || progress.notStarted > 0 -> "Copy stopped"
    progress.failed > 0 && progress.succeeded == 0 && progress.alreadyPresent == 0 -> "Copy failed"
    progress.failed > 0 -> "Copy completed with errors"
    else -> "Copy completed"
}

sealed interface UsbEjectState {
    data object Idle : UsbEjectState
    data object Ejecting : UsbEjectState
    data object Ejected : UsbEjectState
    data class Failed(val message: String) : UsbEjectState
}

sealed interface RemountState {
    /** No remount action in progress or requested. */
    data object Idle : RemountState
    /** Waiting for the user to confirm the remount dialog. */
    data object ConfirmPending : RemountState
    /** Remount command running on device. */
    data object Remounting : RemountState
    /** Remount succeeded; mount is now rw. */
    data object Success : RemountState
    /** Remount or post-remount verification failed. */
    data class Failed(val message: String) : RemountState
}

sealed interface UsbCopyUiState {
    data object Idle : UsbCopyUiState
    data object Discovering : UsbCopyUiState
    data class DiscoveryFailed(val message: String) : UsbCopyUiState
    data class Ready(
        val tiles: List<ArchiveTileState>,
        val usbDetectionResult: UsbDetectionResult,
        val ejectState: UsbEjectState = UsbEjectState.Idle,
        val remountState: RemountState = RemountState.Idle
    ) : UsbCopyUiState {
        val usbMount: UsbMount? get() = when (usbDetectionResult) {
            is UsbDetectionResult.Writable -> usbDetectionResult.mount
            else -> null
        }
    }
    data class Transferring(
        val tiles: List<ArchiveTileState>,
        val progress: TransferProgressState,
        val phase: TransferPhase?
    ) : UsbCopyUiState
    data class Completed(
        val tiles: List<ArchiveTileState>,
        val progress: TransferProgressState,
        val sessionDir: String? = null
    ) : UsbCopyUiState
}
