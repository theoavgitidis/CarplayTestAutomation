package com.example.adb_connection.domain.model

/**
 * Moved from the ui.usbcopy layer so [com.example.adb_connection.domain.usb.UsbTransferCoordinator]
 * (application-scoped, not UI-owned) can expose transfer state without depending on the UI package.
 * Purely data — no Compose/UI dependency — so this is a safe, minimal relocation.
 */
data class ArchiveTileState(
    val archive: TriggerArchive,
    val isSelected: Boolean = false,
    val isAlreadyOnUsb: Boolean = false,
    val existingUsbPath: String? = null,
    val result: TriggerTransferResult? = null,
    val normalResult: TriggerTransferResult? = null,
    val offlineResult: TriggerTransferResult? = null
)

data class TransferProgressState(
    val totalSelected: Int,
    val processed: Int,
    val succeeded: Int,
    val alreadyPresent: Int,
    val failed: Int,
    val cancelled: Int
) {
    val notStarted: Int get() = totalSelected - processed
    // Exact float fraction — do not round to percent before the UI layer.
    val fraction: Float
        get() = if (totalSelected == 0) 0f else processed.toFloat() / totalSelected.toFloat()
    val percentComplete: Int
        get() = (fraction * 100).toInt()

    companion object {
        fun zero(totalSelected: Int) = TransferProgressState(
            totalSelected = totalSelected,
            processed = 0,
            succeeded = 0,
            alreadyPresent = 0,
            failed = 0,
            cancelled = 0
        )
    }
}
