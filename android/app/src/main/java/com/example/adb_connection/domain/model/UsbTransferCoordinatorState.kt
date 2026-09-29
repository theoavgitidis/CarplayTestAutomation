package com.example.adb_connection.domain.model

/**
 * Observable state of the application-scoped transfer batch, owned by
 * [com.example.adb_connection.domain.usb.UsbTransferCoordinator]. Deliberately mirrors the
 * shape `UsbCopyUiState.Transferring`/`Completed` used to have, but lives in the domain layer
 * so the coordinator (and the foreground service hosting it) do not depend on UI code, and so
 * this state survives ViewModel/Activity destruction — only process death clears it, at which
 * point [com.example.adb_connection.domain.usb.UsbTransferCoordinator.attachOrRecover] rebuilds
 * an equivalent state from the persisted [ActiveTransferSnapshot].
 */
sealed interface UsbTransferCoordinatorState {
    data object Idle : UsbTransferCoordinatorState

    data class Running(
        val tiles: List<ArchiveTileState>,
        val progress: TransferProgressState,
        val phase: TransferPhase?
    ) : UsbTransferCoordinatorState

    data class Completed(
        val tiles: List<ArchiveTileState>,
        val progress: TransferProgressState,
        val sessionDir: String?
    ) : UsbTransferCoordinatorState
}
