package com.example.adb_connection.data.debug

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalTime
import java.time.format.DateTimeFormatter

object DebugLogger {
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun log(message: String) {
        val timestamp = LocalTime.now().format(timeFormatter)
        _logs.update { current ->
            (listOf("[$timestamp] $message") + current).take(50)
        }
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
