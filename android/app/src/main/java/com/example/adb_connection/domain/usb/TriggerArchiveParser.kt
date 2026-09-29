package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.TriggerArchive

object TriggerArchiveParser {

    private val FILENAME_REGEX = Regex("""^trigger_(\d+)_HU_(\d{8})_(\d{6})_COREDUMP\.tar\.lz4$""")
    private val STEM_REGEX = Regex("""^trigger_\d+_HU_\d{8}_\d{6}_COREDUMP(?:_dlt_offlinetrace)?$""")
    private val SAFE_PATH_CHARS = Regex("""^[a-zA-Z0-9_./-]+$""")
    private val JOB_ID_REGEX = Regex("""^[a-f0-9]{16}$""")
    private val USB_MOUNT_CHARS = Regex("""^[a-zA-Z0-9_./ -]+$""")
    private val USB_MOUNT_PATH_REGEX = Regex("""^HEAD_UNIT_USB_MOUNT_ROOT_PLACEHOLDER/[a-zA-Z0-9_. -]+$""")
    private val DEVICE_PATH_CHARS = Regex("""^[a-zA-Z0-9_./,:-]+$""")

    private const val ARCHIVE_ROOT = "HEAD_UNIT_TRACE_ARCHIVE_DIR_PLACEHOLDER/"

    fun parseDiscoveryOutput(findOutput: String): List<TriggerArchive> {
        val seen = LinkedHashMap<String, TriggerArchive>()
        findOutput.lines()
            .filter { it.isNotBlank() }
            .mapNotNull { parseSinglePath(it.trim()) }
            .forEach { archive -> seen.putIfAbsent(archive.stem, archive) }
        return seen.values.sortedBy { it.triggerNumber }
    }

    fun parseSinglePath(absolutePath: String): TriggerArchive? {
        if (!isPathSafe(absolutePath)) return null
        if (!absolutePath.startsWith(ARCHIVE_ROOT)) return null

        // The path must be directly under ARCHIVE_ROOT — no subdirectory nesting
        val relative = absolutePath.removePrefix(ARCHIVE_ROOT)
        if (relative.contains('/')) return null

        val filename = relative
        val match = FILENAME_REGEX.matchEntire(filename) ?: return null

        val triggerNumber = match.groupValues[1].toLongOrNull()?.let {
            if (it > Int.MAX_VALUE) null else it.toInt()
        } ?: return null

        val datePart = match.groupValues[2]
        val timePart = match.groupValues[3]
        val stem = "trigger_${triggerNumber}_HU_${datePart}_${timePart}_COREDUMP"

        if (!isStemValid(stem)) return null

        val extractedDirectoryPath = "$ARCHIVE_ROOT$stem"
        return TriggerArchive(
            triggerNumber = triggerNumber,
            stem = stem,
            archivePath = absolutePath,
            extractedDirectoryPath = extractedDirectoryPath
        )
    }

    fun isPathSafe(path: String): Boolean {
        if (path.isBlank()) return false
        if (path.contains("..")) return false
        return SAFE_PATH_CHARS.matches(path)
    }

    fun isStemValid(stem: String): Boolean = STEM_REGEX.matches(stem)

    fun isJobIdValid(jobId: String): Boolean = JOB_ID_REGEX.matches(jobId)

    fun isUsbMountSafe(mountPath: String): Boolean {
        if (mountPath.isBlank()) return false
        if (mountPath.contains("..")) return false
        return USB_MOUNT_CHARS.matches(mountPath)
    }

    /** Accepts mount points matching exactly the configured one-segment USB root layout. */
    fun isUsbMountPathValid(mountPath: String): Boolean =
        !mountPath.contains("..") && USB_MOUNT_PATH_REGEX.matches(mountPath)

    fun isDevicePathSafe(devicePath: String): Boolean {
        if (devicePath.isBlank()) return false
        if (devicePath.contains("..")) return false
        return DEVICE_PATH_CHARS.matches(devicePath)
    }
}
