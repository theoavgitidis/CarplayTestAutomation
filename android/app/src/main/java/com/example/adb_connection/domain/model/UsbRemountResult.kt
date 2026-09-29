package com.example.adb_connection.domain.model

sealed interface UsbRemountResult {
    data class Success(val mount: UsbMount) : UsbRemountResult
    data class Failed(val message: String) : UsbRemountResult
}
