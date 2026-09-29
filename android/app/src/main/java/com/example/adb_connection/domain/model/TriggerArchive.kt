package com.example.adb_connection.domain.model

enum class ArchiveVariant(val label: String) {
    NORMAL("normal"),
    OFFLINE_TRACE("offline trace")
}

data class TriggerArchive(
    val triggerNumber: Int,
    val stem: String,
    val archivePath: String,
    val extractedDirectoryPath: String,
    val variant: ArchiveVariant = ArchiveVariant.NORMAL
) {
    val displayLabel: String get() = "T$triggerNumber"
    val variantLabel: String get() = variant.label

    fun offlineVariant(): TriggerArchive {
        require(variant == ArchiveVariant.NORMAL)
        return copy(
            stem = "${stem}_dlt_offlinetrace",
            archivePath = archivePath.removeSuffix(".tar.lz4") + "_dlt_offlinetrace.tar.lz4",
            extractedDirectoryPath = "${extractedDirectoryPath}_dlt_offlinetrace",
            variant = ArchiveVariant.OFFLINE_TRACE
        )
    }
}
