package com.example.adb_connection.data.adb

internal const val EXIT_CODE_MARKER = "TRACEMATE_EXIT:"

internal fun wrapCommandWithExitMarker(command: String): String =
    "$command; echo \"${EXIT_CODE_MARKER}\$?\""

internal fun parseShellOutput(command: String, rawOutput: String): AdbShellResult {
    val markerIndex = rawOutput.lastIndexOf(EXIT_CODE_MARKER)
    if (markerIndex < 0) {
        return AdbShellResult(
            success = false,
            command = command,
            output = rawOutput.trim(),
            exitCode = null,
            errorMessage = "Protocol error: exit-code marker missing from output"
        )
    }

    val afterMarker = rawOutput.substring(markerIndex + EXIT_CODE_MARKER.length).trim()
    val exitCode = afterMarker.toIntOrNull()
    if (exitCode == null) {
        return AdbShellResult(
            success = false,
            command = command,
            output = rawOutput.substring(0, markerIndex).trim(),
            exitCode = null,
            errorMessage = "Protocol error: invalid exit code value '$afterMarker'"
        )
    }

    val stdout = rawOutput.substring(0, markerIndex).trim()
    return AdbShellResult(
        success = exitCode == 0,
        command = command,
        output = stdout,
        exitCode = exitCode
    )
}
