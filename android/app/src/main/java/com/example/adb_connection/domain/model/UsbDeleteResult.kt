package com.example.adb_connection.domain.model

sealed interface UsbDeleteResult {
    data object Success : UsbDeleteResult
    data class UsbNotConnected(val message: String? = null) : UsbDeleteResult
    data class UsbMountChanged(val message: String) : UsbDeleteResult
    data class UsbNotWritable(val message: String) : UsbDeleteResult
    data class AdbCommandFailed(val message: String) : UsbDeleteResult
}
