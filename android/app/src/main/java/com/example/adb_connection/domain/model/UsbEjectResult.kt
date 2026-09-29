package com.example.adb_connection.domain.model

sealed interface UsbEjectResult {
    data object Success : UsbEjectResult
    data class Failed(val message: String) : UsbEjectResult
}
