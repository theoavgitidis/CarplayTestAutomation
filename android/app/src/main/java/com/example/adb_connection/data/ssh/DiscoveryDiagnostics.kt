package com.example.adb_connection.data.ssh

import android.os.SystemClock
import android.util.Log
import java.time.Instant

object DiscoveryDiagnostics {
    private const val TAG = "TraceMateDiscovery"
    private const val MAX_LINES = 500
    private const val MAX_CHARS = 256 * 1024
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private var charCount = 0
    private var attempt = "none"

    fun startAttempt(): String = synchronized(lock) {
        lines.clear()
        charCount = 0
        attempt = SystemClock.elapsedRealtime().toString(16)
        appendLocked("attempt=$attempt started")
        attempt
    }

    fun log(message: String) = synchronized(lock) {
        appendLocked("attempt=$attempt $message")
    }

    fun export(): String = synchronized(lock) {
        if (lines.isEmpty()) "No TraceMate discovery diagnostics have been recorded." else lines.joinToString("\n")
    }

    private fun appendLocked(message: String) {
        val line = "${Instant.now()} $message"
        Log.d(TAG, line)
        lines.addLast(line)
        charCount += line.length + 1
        while (lines.size > MAX_LINES || charCount > MAX_CHARS) {
            charCount -= lines.removeFirst().length + 1
        }
    }
}
