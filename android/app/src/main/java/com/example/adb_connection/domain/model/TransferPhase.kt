package com.example.adb_connection.domain.model

sealed class TransferPhase {
    data class Checking(val archive: TriggerArchive) : TransferPhase()
    data class CopyingExisting(val archive: TriggerArchive) : TransferPhase()
    data class Extracting(val archive: TriggerArchive) : TransferPhase()
    data class Stopping(val archive: TriggerArchive) : TransferPhase()
}
