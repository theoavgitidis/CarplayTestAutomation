package com.example.adb_connection.data.ssh

data class MacAgentJob(
    val jobId: String,
    val outputPath: String,
    val state: String,
    val activityState: String,
    val captureBytes: Long,
    val observedLiveEvents: Int,
    val lastError: String?,
    val artifactSha256: String? = null
) {
    // Older deployed agents report a successfully stopped capture as STOPPED rather than COMPLETED.
    val isTerminal: Boolean get() = state == "COMPLETED" || state == "FAILED" || state == "STOPPED"
}

/** The CLI emits a JSON RPC envelope whose successful result is always a capture job here. */
internal fun parseMacAgentJob(response: String): MacAgentJob {
    if (!response.jsonBoolean("ok")) {
        throw IllegalStateException(response.jsonString("error") ?: "Mac agent rejected the command")
    }
    val result = response.jsonObject("result") ?: throw IllegalStateException("Mac agent returned no capture job")
    return parseMacAgentJobObject(result)
}

internal fun parseMacAgentJobs(response: String): List<MacAgentJob> {
    if (!response.jsonBoolean("ok")) {
        throw IllegalStateException(response.jsonString("error") ?: "Mac agent rejected the command")
    }
    val result = response.jsonArray("result") ?: throw IllegalStateException("Mac agent returned no capture jobs")
    return result.jsonObjects().map(::parseMacAgentJobObject)
}

private fun parseMacAgentJobObject(result: String): MacAgentJob {
    return MacAgentJob(
        jobId = result.jsonString("jobId") ?: throw IllegalStateException("Mac agent returned no job ID"),
        outputPath = result.jsonString("outputPath") ?: throw IllegalStateException("Mac agent returned no output path"),
        state = result.jsonString("state") ?: "UNKNOWN",
        activityState = result.jsonObject("activity")?.jsonString("state") ?: "UNKNOWN",
        captureBytes = result.jsonLong("captureBytes") ?: 0,
        observedLiveEvents = result.jsonLong("observedLiveEvents")?.toInt() ?: 0,
        lastError = result.jsonString("lastError"),
        artifactSha256 = result.jsonObject("artifact")?.jsonString("sha256")
    )
}

private fun String.jsonBoolean(name: String): Boolean = Regex("\\\"$name\\\"\\s*:\\s*true").containsMatchIn(this)

private fun String.jsonString(name: String): String? = Regex("\\\"$name\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"")
    .find(this)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")

private fun String.jsonLong(name: String): Long? = Regex("\\\"$name\\\"\\s*:\\s*(\\d+)")
    .find(this)?.groupValues?.get(1)?.toLongOrNull()

private fun String.jsonObject(name: String): String? {
    val start = Regex("\\\"$name\\\"\\s*:\\s*\\{").find(this)?.range?.last ?: return null
    var depth = 1
    var quoted = false
    var escaped = false
    for (index in start + 1 until length) {
        val character = this[index]
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{' -> depth++
            '}' -> if (--depth == 0) return substring(start + 1, index)
        }
    }
    return null
}

private fun String.jsonArray(name: String): String? {
    val start = Regex("\\\"$name\\\"\\s*:\\s*\\[").find(this)?.range?.last ?: return null
    var depth = 1
    var quoted = false
    var escaped = false
    for (index in start + 1 until length) {
        val character = this[index]
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '[' -> depth++
            ']' -> if (--depth == 0) return substring(start + 1, index)
        }
    }
    return null
}

private fun String.jsonObjects(): List<String> {
    val objects = mutableListOf<String>()
    var objectStart = -1
    var depth = 0
    var quoted = false
    var escaped = false
    forEachIndexed { index, character ->
        if (quoted) {
            if (escaped) escaped = false else if (character == '\\') escaped = true else if (character == '"') quoted = false
        } else when (character) {
            '"' -> quoted = true
            '{' -> if (depth++ == 0) objectStart = index
            '}' -> if (--depth == 0 && objectStart >= 0) objects += substring(objectStart, index + 1)
        }
    }
    return objects
}
