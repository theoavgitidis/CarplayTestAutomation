package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.UsbMount

/**
 * Parses the output of [UsbShellCommandBuilder.detectUsbMountsCommand].
 *
 * Each non-blank line is expected to be pipe-delimited:
 *   <device>|<mountpoint>|<fstype>|<options>
 *
 * Lines that fail path validation or whose mount point does not match
 * the required pattern are silently dropped.
 */
object UsbMountParser {

    data class ParseResult(
        val mounts: List<UsbMount>,
        val rejectedCandidateCount: Int
    )

    fun parse(output: String): List<UsbMount> = parseWithDiagnostics(output).mounts

    fun parseWithDiagnostics(output: String): ParseResult {
        val candidates = output.lines()
            .filter { it.isNotBlank() }
        val mounts = candidates.mapNotNull { parseLine(it.trim()) }
        return ParseResult(mounts, candidates.size - mounts.size)
    }

    private fun parseLine(line: String): UsbMount? {
        val parts = line.split('|')
        if (parts.size < 4) return null

        val device = decodeProcMountField(parts[0].trim()) ?: return null
        val mountPoint = decodeProcMountField(parts[1].trim()) ?: return null
        val fsType = parts[2].trim()
        val options = parts[3].trim()

        if (!TriggerArchiveParser.isDevicePathSafe(device)) return null
        if (!TriggerArchiveParser.isUsbMountPathValid(mountPoint)) return null
        if (fsType.isBlank()) return null

        val optionSet = options.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        return UsbMount(
            devicePath = device,
            mountPath = mountPoint,
            fileSystem = fsType,
            mountOptions = optionSet
        )
    }

    private fun decodeProcMountField(value: String): String? {
        val decoded = value
            .replace("\\040", " ")
            .replace("\\011", "\t")
            .replace("\\012", "\n")
            .replace("\\134", "\\")
        return if ('\\' in decoded) null else decoded
    }
}
