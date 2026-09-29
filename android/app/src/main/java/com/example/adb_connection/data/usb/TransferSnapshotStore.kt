package com.example.adb_connection.data.usb

/**
 * Persists the compact, opaque text produced by
 * [com.example.adb_connection.domain.usb.ActiveTransferSnapshotCodec] for an in-progress
 * transfer batch, so it can survive process death and be reattached to. Kept as a thin
 * interface (rather than exposing DataStore directly) so
 * [com.example.adb_connection.domain.usb.UsbTransferCoordinator] can be unit tested with an
 * in-memory fake — this project has no mocking framework, so all Android-backed
 * implementations are tested only indirectly, via fakes, consistent with
 * [UsbTransferRepository].
 */
interface TransferSnapshotStore {
    suspend fun save(raw: String)
    suspend fun load(): String?
    suspend fun clear()
}
