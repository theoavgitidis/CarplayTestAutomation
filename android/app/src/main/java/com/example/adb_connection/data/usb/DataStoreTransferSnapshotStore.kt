package com.example.adb_connection.data.usb

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.adb_connection.data.settings.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-backed [TransferSnapshotStore]. Reuses the same `settings` preferences DataStore
 * as [com.example.adb_connection.data.settings.SettingsRepository] — no new storage mechanism
 * is introduced. Android-backed, so (like [UsbTransferRepositoryImpl]) this class is not unit
 * tested directly; the coordinator logic that depends on [TransferSnapshotStore] is tested via
 * an in-memory fake instead.
 */
class DataStoreTransferSnapshotStore(private val context: Context) : TransferSnapshotStore {

    companion object {
        private val ACTIVE_TRANSFER_SNAPSHOT = stringPreferencesKey("active_transfer_snapshot")
    }

    override suspend fun save(raw: String) {
        context.dataStore.edit { prefs -> prefs[ACTIVE_TRANSFER_SNAPSHOT] = raw }
    }

    override suspend fun load(): String? =
        context.dataStore.data.map { it[ACTIVE_TRANSFER_SNAPSHOT] }.first()

    override suspend fun clear() {
        context.dataStore.edit { prefs -> prefs.remove(ACTIVE_TRANSFER_SNAPSHOT) }
    }
}
