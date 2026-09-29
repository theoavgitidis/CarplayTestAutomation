package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.FailureReason

// Infrastructure failures affect the ADB channel, USB device, or remote job scaffolding —
// conditions that will not be resolved by trying the next archive. Archive-specific failures
// (missing source, corrupt data, rename collision) leave the infrastructure intact and should
// let the batch continue. Moved here (from the old `UsbCopyViewModel`) alongside the transfer
// loop it classifies for, now owned by [UsbTransferCoordinator].
internal fun FailureReason.isInfrastructure(): Boolean = when (this) {
    FailureReason.ADB_COMMUNICATION_ERROR,
    FailureReason.USB_NOT_FOUND,
    FailureReason.USB_DISCONNECTED,
    FailureReason.USB_NOT_WRITABLE,
    FailureReason.USB_MOUNT_CHANGED,
    FailureReason.USB_FULL,
    FailureReason.USB_IO_ERROR,
    FailureReason.JOB_DIR_CREATE_FAILED,
    FailureReason.REMOTE_SCRIPT_LAUNCH_FAILED,
    FailureReason.WORKER_LOST,
    FailureReason.STATUS_FILE_UNREADABLE -> true
    else -> false
}
