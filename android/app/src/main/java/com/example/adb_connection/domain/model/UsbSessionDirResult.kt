package com.example.adb_connection.domain.model

/** Result of creating the TraceMate session-export directory on the USB mount. */
sealed interface UsbSessionDirResult {
    data class Success(val path: String) : UsbSessionDirResult
    data class Failed(val reason: FailureReason, val message: String? = null) : UsbSessionDirResult
}
