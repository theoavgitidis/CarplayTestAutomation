package com.example.adb_connection.domain.usb

/** Parses complete, bounded TraceMate export listings produced during discovery. */
object UsbExistingExportParser {

    private const val SENTINEL = "STATUS:DONE"
    private const val EXPORT_DIRECTORY = "HEAD_UNIT_USB_EXPORT_DIRECTORY_PLACEHOLDER"
    private val SESSION_DIRECTORY_REGEX = Regex("""^tracemate_export_\d{8}_\d{6}$""")

    fun parse(output: String, mountPath: String): Map<String, String>? {
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.lastOrNull()?.trim() != SENTINEL) return null

        val prefix = "$mountPath/$EXPORT_DIRECTORY/"
        return buildMap {
            lines.dropLast(1).forEach { path ->
                val stem = path.removePrefix(prefix).substringAfterLast('/', missingDelimiterValue = "")
                val sessionDir = path.removePrefix(prefix).substringBeforeLast('/', missingDelimiterValue = "")
                if (
                    path.startsWith(prefix) &&
                    SESSION_DIRECTORY_REGEX.matches(sessionDir) &&
                    TriggerArchiveParser.isStemValid(stem)
                ) {
                    putIfAbsent(stem, path)
                }
            }
        }
    }
}
