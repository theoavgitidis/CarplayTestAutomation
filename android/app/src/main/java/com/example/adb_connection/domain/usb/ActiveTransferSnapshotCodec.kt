package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.ActiveJobSnapshot
import com.example.adb_connection.domain.model.ActiveTransferSnapshot
import com.example.adb_connection.domain.model.ArchiveVariant
import com.example.adb_connection.domain.model.FailureReason
import com.example.adb_connection.domain.model.RemoteJobLifecycle
import com.example.adb_connection.domain.model.ResultKind
import com.example.adb_connection.domain.model.ResultSummary
import com.example.adb_connection.domain.model.SourceType
import com.example.adb_connection.domain.model.TileResultSnapshot
import com.example.adb_connection.domain.model.TriggerArchive
import com.example.adb_connection.domain.model.UsbMount
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Pure (no Android dependency — safe for plain JUnit) text encoding for [ActiveTransferSnapshot],
 * used to persist an in-progress transfer batch so it can survive process death. Deliberately
 * hand-rolled rather than JSON to avoid adding a serialization dependency: each record is one
 * line, `TAG=field1|field2|...`, with individual fields percent-encoded via [URLEncoder] (plain
 * JDK, not Android) so arbitrary path/label content round-trips safely regardless of the `|`
 * delimiter or newlines appearing inside a field.
 *
 * [decode] is defensive: any malformed/corrupt/truncated input returns `null` (treated by the
 * coordinator as "no snapshot to resume") rather than throwing.
 */
object ActiveTransferSnapshotCodec {
    private const val CHARSET = "UTF-8"

    private fun enc(s: String): String = URLEncoder.encode(s, CHARSET)
    private fun dec(s: String): String = URLDecoder.decode(s, CHARSET)

    fun encode(snapshot: ActiveTransferSnapshot): String = buildString {
        val m = snapshot.expectedMount
        appendLine("MOUNT=${enc(m.devicePath)}|${enc(m.mountPath)}|${enc(m.fileSystem)}|${enc(m.mountOptions.joinToString(","))}")
        appendLine("SESSION=${enc(snapshot.sessionDir)}")
        appendLine("CANCEL=${snapshot.cancelRequested}")
        appendLine("INDEX=${snapshot.currentIndex}")
        snapshot.currentJob?.let { j ->
            appendLine("JOB=${enc(j.jobId)}|${enc(j.stem)}|${j.variant.name}|${j.useStrategyA}|${j.phase.name}")
        }
        snapshot.archives.forEachIndexed { i, a ->
            appendLine(
                "ARCHIVE=$i|${a.triggerNumber}|${enc(a.stem)}|${enc(a.archivePath)}|${enc(a.extractedDirectoryPath)}|${a.variant.name}"
            )
        }
        snapshot.tileResults.forEach { tr ->
            tr.normalResult?.let { r -> appendLine("RESULT=${enc(tr.stem)}|NORMAL|${encodeResult(r)}") }
            tr.offlineResult?.let { r -> appendLine("RESULT=${enc(tr.stem)}|OFFLINE|${encodeResult(r)}") }
        }
    }.trimEnd('\n')

    private fun encodeResult(r: ResultSummary): String = listOf(
        r.kind.name,
        enc(r.path ?: ""),
        r.reason?.name ?: "",
        enc(r.message ?: ""),
        r.partialOutputRemoved?.toString() ?: "",
        r.sourceType?.name ?: ""
    ).joinToString("|")

    fun decode(raw: String): ActiveTransferSnapshot? = try {
        var mount: UsbMount? = null
        var sessionDir: String? = null
        var cancelRequested = false
        var currentIndex = 0
        var currentJob: ActiveJobSnapshot? = null
        val archives = sortedMapOf<Int, TriggerArchive>()
        // stem -> (isNormal, summary)
        val results = mutableListOf<Triple<String, Boolean, ResultSummary>>()

        raw.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val key = line.substring(0, eq)
            val value = line.substring(eq + 1)
            when (key) {
                "MOUNT" -> {
                    val parts = value.split("|")
                    if (parts.size == 4) {
                        mount = UsbMount(
                            devicePath = dec(parts[0]),
                            mountPath = dec(parts[1]),
                            fileSystem = dec(parts[2]),
                            mountOptions = dec(parts[3]).split(",").filter { it.isNotBlank() }.toSet()
                        )
                    }
                }
                "SESSION" -> sessionDir = dec(value)
                "CANCEL" -> cancelRequested = value.toBoolean()
                "INDEX" -> currentIndex = value.toIntOrNull() ?: 0
                "JOB" -> {
                    val parts = value.split("|")
                    if (parts.size == 5) {
                        currentJob = ActiveJobSnapshot(
                            jobId = dec(parts[0]),
                            stem = dec(parts[1]),
                            variant = ArchiveVariant.valueOf(parts[2]),
                            useStrategyA = parts[3].toBoolean(),
                            phase = RemoteJobLifecycle.valueOf(parts[4])
                        )
                    }
                }
                "ARCHIVE" -> {
                    val parts = value.split("|")
                    if (parts.size == 6) {
                        val i = parts[0].toIntOrNull() ?: return@forEach
                        archives[i] = TriggerArchive(
                            triggerNumber = parts[1].toIntOrNull() ?: 0,
                            stem = dec(parts[2]),
                            archivePath = dec(parts[3]),
                            extractedDirectoryPath = dec(parts[4]),
                            variant = ArchiveVariant.valueOf(parts[5])
                        )
                    }
                }
                "RESULT" -> {
                    val parts = value.split("|")
                    if (parts.size == 8) {
                        val stem = dec(parts[0])
                        val isNormal = parts[1] == "NORMAL"
                        val summary = ResultSummary(
                            kind = ResultKind.valueOf(parts[2]),
                            path = dec(parts[3]).ifBlank { null },
                            reason = parts[4].takeIf { it.isNotBlank() }?.let { FailureReason.valueOf(it) },
                            message = dec(parts[5]).ifBlank { null },
                            partialOutputRemoved = parts[6].takeIf { it.isNotBlank() }?.toBoolean(),
                            sourceType = parts[7].takeIf { it.isNotBlank() }?.let { SourceType.valueOf(it) }
                        )
                        results += Triple(stem, isNormal, summary)
                    }
                }
            }
        }

        val m = mount ?: return null
        val sd = sessionDir ?: return null
        val archiveList = archives.values.toList()
        val tileResults = archiveList.mapNotNull { a ->
            val normal = results.find { it.first == a.stem && it.second }?.third
            val offline = results.find { it.first == a.stem && !it.second }?.third
            if (normal == null && offline == null) null
            else TileResultSnapshot(a.stem, normal, offline)
        }

        ActiveTransferSnapshot(
            expectedMount = m,
            sessionDir = sd,
            archives = archiveList,
            currentIndex = currentIndex,
            currentJob = currentJob,
            tileResults = tileResults,
            cancelRequested = cancelRequested
        )
    } catch (e: Exception) {
        null
    }
}
