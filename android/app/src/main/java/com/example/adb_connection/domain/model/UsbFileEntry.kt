package com.example.adb_connection.domain.model

enum class UsbFileType { FILE, DIRECTORY }

data class UsbFileEntry(
    val name: String,
    val relativePath: String,
    val type: UsbFileType,
    val sizeBytes: Long?
)

/**
 * Structured result from a USB file listing operation.
 *
 * .partial directories (created by in-progress transfers, named .<stem>.partial)
 * are **omitted** from all Success/UsbRemovedDuringListing entry lists.
 * They represent incomplete exports and must not be shown to the user as completed transfers.
 */
sealed interface UsbListingResult {
    data class Success(
        val mount: UsbMount,
        val entries: List<UsbFileEntry>
    ) : UsbListingResult

    /** No writable USB mount was found. */
    data class UsbNotConnected(val message: String? = null) : UsbListingResult

    /** ADB command for USB detection succeeded but the mount path was rejected or detection failed. */
    data class UsbDetectionFailed(val message: String) : UsbListingResult

    /** USB is present but has no TraceMate directory — nothing has been exported yet. */
    data class TraceMateDirectoryAbsent(val mount: UsbMount) : UsbListingResult

    /** TraceMate directory exists but contains no files or directories. */
    data class NoExportedFiles(val mount: UsbMount) : UsbListingResult

    /**
     * The ADB shell command completed but the output was truncated before the
     * STATUS:DONE sentinel — likely because the USB device was removed mid-listing.
     * [partialEntries] contains whatever was parsed before truncation.
     */
    data class UsbRemovedDuringListing(
        val mount: UsbMount,
        val partialEntries: List<UsbFileEntry>
    ) : UsbListingResult

    /** The ADB shell command itself failed (ADB protocol error or connection loss). */
    data class AdbCommandFailed(val message: String) : UsbListingResult

    /** The requested sub-directory does not exist on the USB mount. */
    data class DirectoryNotFound(
        val mount: UsbMount,
        val relativeDirectory: String
    ) : UsbListingResult

    /** The listing command exceeded the read timeout — the caller may retry once. */
    data object ListingTimedOut : UsbListingResult
}
