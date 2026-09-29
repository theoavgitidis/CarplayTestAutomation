package com.example.adb_connection.data.ssh

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.adb_connection.data.settings.dataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Base64

/** Durable last-known state for an CAPTURE_TOOL_PLACEHOLDER bridge job; transfer data itself is never persisted here. */
interface CaptureToolPlaceholderTransferSnapshotStore {
    suspend fun save(snapshot: CaptureToolPlaceholderTransferSnapshot)
    suspend fun load(): CaptureToolPlaceholderTransferSnapshot?
}

data class CaptureToolPlaceholderTransferSnapshot(
    val jobId: String,
    val status: CaptureToolPlaceholderTransferJobStatus,
    val phase: CaptureToolPlaceholderUsbTransferPhase,
    val remotePaths: List<String>,
    val fileName: String? = null,
    val transferredBytes: Long = 0,
    val totalBytes: Long = 0,
    val error: String? = null
)

enum class CaptureToolPlaceholderTransferJobStatus { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

class DataStoreCaptureToolPlaceholderTransferSnapshotStore(private val context: Context) : CaptureToolPlaceholderTransferSnapshotStore {
    override suspend fun save(snapshot: CaptureToolPlaceholderTransferSnapshot) {
        context.dataStore.edit { it[KEY] = CaptureToolPlaceholderTransferSnapshotCodec.encode(snapshot) }
    }

    override suspend fun load(): CaptureToolPlaceholderTransferSnapshot? =
        context.dataStore.data.map { it[KEY] }.first()?.let(CaptureToolPlaceholderTransferSnapshotCodec::decode)

    private companion object {
        val KEY = stringPreferencesKey("capture_tool_placeholder_transfer_snapshot")
    }
}

internal object CaptureToolPlaceholderTransferSnapshotCodec {
    fun encode(snapshot: CaptureToolPlaceholderTransferSnapshot): String = listOf(
        snapshot.jobId,
        snapshot.status.name,
        snapshot.phase.name,
        encodeValue(snapshot.fileName),
        snapshot.transferredBytes.toString(),
        snapshot.totalBytes.toString(),
        encodeValue(snapshot.error),
        snapshot.remotePaths.joinToString(",") { encodeValue(it) }
    ).joinToString("|")

    fun decode(raw: String): CaptureToolPlaceholderTransferSnapshot? {
        return try {
            val parts = raw.split("|", limit = 8)
            if (parts.size != 8) return null
            CaptureToolPlaceholderTransferSnapshot(
                jobId = parts[0],
                status = CaptureToolPlaceholderTransferJobStatus.valueOf(parts[1]),
                phase = CaptureToolPlaceholderUsbTransferPhase.valueOf(parts[2]),
                fileName = decodeValue(parts[3]),
                transferredBytes = parts[4].toLong(),
                totalBytes = parts[5].toLong(),
                error = decodeValue(parts[6]),
                remotePaths = if (parts[7].isBlank()) emptyList() else parts[7].split(",").map(::decodeValue).filterNotNull()
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun encodeValue(value: String?): String = value?.let {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    } ?: "-"

    private fun decodeValue(value: String): String? = if (value == "-") null else String(
        Base64.getUrlDecoder().decode(value), Charsets.UTF_8
    )
}
