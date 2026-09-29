package com.example.adb_connection.ui.usbfiles

import com.example.adb_connection.domain.model.UsbFileEntry
import com.example.adb_connection.domain.model.UsbMount

sealed interface UsbFilesUiState {
    data object Loading : UsbFilesUiState
    data object Refreshing : UsbFilesUiState
    data object Deleting : UsbFilesUiState
    data object UsbNotConnected : UsbFilesUiState
    data class TraceMateDirectoryAbsent(val mount: UsbMount) : UsbFilesUiState
    data class NoExports(val mount: UsbMount) : UsbFilesUiState
    data class Loaded(val mount: UsbMount, val entries: List<UsbFileEntry>) : UsbFilesUiState
    data class UsbRemovedDuringLoad(val partialEntries: List<UsbFileEntry>) : UsbFilesUiState
    data class AdbError(val message: String) : UsbFilesUiState
    data class DeleteError(val message: String) : UsbFilesUiState
    data object ListingTimedOut : UsbFilesUiState
    data object CopyActive : UsbFilesUiState
}
