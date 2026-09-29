package com.example.adb_connection.domain.usb

import com.example.adb_connection.domain.model.UsbFileEntry
import com.example.adb_connection.domain.model.UsbFileType

/**
 * Parses the output of [UsbShellCommandBuilder.listTraceMateDirectoryCommand].
 *
 * Expected format per entry line:
 *   <stat-%F>|<stat-%s>|<absolute-path>
 *
 * The last line must be "STATUS:DONE"; absence of that sentinel means the
 * listing was truncated (e.g. USB removed while the command was running).
 *
 * .partial entries are excluded at the shell level; this parser does not
 * need to filter them further.
 */
object UsbFileListingParser {

    private const val SENTINEL = "STATUS:DONE"
    private const val DELIMITER = '|'
    private const val PARTS_COUNT = 3

    data class ParseResult(
        val entries: List<UsbFileEntry>,
        val complete: Boolean  // false when STATUS:DONE sentinel was missing
    )

    fun parse(output: String, mountPath: String): ParseResult {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return ParseResult(emptyList(), complete = false)

        val hasSentinel = lines.last().trim() == SENTINEL
        val dataLines = if (hasSentinel) lines.dropLast(1) else lines

        val base = mountPath
        val entries = dataLines.mapNotNull { parseLine(it, base) }

        return ParseResult(entries = entries, complete = hasSentinel)
    }

    private fun parseLine(line: String, base: String): UsbFileEntry? {
        // Split on the first two pipe characters only — path may contain none,
        // but we split at most into 3 parts to handle paths safely.
        val firstPipe = line.indexOf(DELIMITER)
        if (firstPipe < 0) return null
        val secondPipe = line.indexOf(DELIMITER, firstPipe + 1)
        if (secondPipe < 0) return null

        val typePart = line.substring(0, firstPipe).trim()
        val sizePart = line.substring(firstPipe + 1, secondPipe).trim()
        val path = line.substring(secondPipe + 1)   // preserve leading slash and spaces

        if (path.isBlank()) return null
        if (!isListingPathSafe(path)) return null

        val type = when (typePart) {
            "regular file" -> UsbFileType.FILE
            "directory"    -> UsbFileType.DIRECTORY
            else           -> return null   // block devices, symlinks, etc. — ignore
        }

        val sizeBytes: Long? = if (type == UsbFileType.FILE) sizePart.toLongOrNull() else null

        // relativePath is the portion after the USB mount path — strip the base prefix.
        val relativePath = when {
            path == base              -> ""
            path.startsWith("$base/") -> path.removePrefix("$base/")
            else                       -> return null   // path outside the USB mount — reject
        }
        if (relativePath.isBlank() && type == UsbFileType.DIRECTORY) return null // skip root itself

        val name = path.substringAfterLast('/')

        return UsbFileEntry(
            name = name,
            relativePath = relativePath,
            type = type,
            sizeBytes = sizeBytes
        )
    }

    private fun isListingPathSafe(path: String): Boolean =
        !path.contains("..") && path.all { char ->
            char.isLetterOrDigit() || char == '_' || char == '-' ||
                char == '.' || char == '/' || char == ' '
        }
}
