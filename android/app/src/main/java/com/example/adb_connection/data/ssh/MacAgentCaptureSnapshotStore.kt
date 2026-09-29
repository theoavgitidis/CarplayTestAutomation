package com.example.adb_connection.data.ssh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.adb_connection.data.settings.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

interface MacAgentCaptureSnapshotStore {
    suspend fun save(jobId: String)
    suspend fun load(): String?
    suspend fun clear()
}

class DataStoreMacAgentCaptureSnapshotStore(private val context: Context) : MacAgentCaptureSnapshotStore {
    override suspend fun save(jobId: String) {
        context.dataStore.edit { it[KEY] = jobId }
    }

    override suspend fun load(): String? = context.dataStore.data.map { it[KEY] }.first()

    override suspend fun clear() {
        context.dataStore.edit { it.remove(KEY) }
    }

    private companion object {
        val KEY = stringPreferencesKey("mac_agent_capture_job_id")
    }
}
