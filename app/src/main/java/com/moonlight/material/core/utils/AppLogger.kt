package com.moonlight.material.core.utils

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

data class LogEntry(
    val id: Long,
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val category: String,
    val message: String
) {
    val formattedTime: String
        get() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(timestamp))

    override fun toString(): String {
        return "$formattedTime [${level.name}] [$category] $message"
    }
}

object AppLogger {
    private const val TAG = "Moonlight"
    private const val MAX_LOGS = 1000
    private var nextId = 0L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    @Synchronized
    fun log(level: LogLevel, category: String, message: String) {
        when (level) {
            LogLevel.DEBUG -> Log.d(TAG, "[$category] $message")
            LogLevel.INFO -> Log.i(TAG, "[$category] $message")
            LogLevel.WARN -> Log.w(TAG, "[$category] $message")
            LogLevel.ERROR -> Log.e(TAG, "[$category] $message")
        }

        val entry = LogEntry(
            id = nextId++,
            level = level,
            category = category.uppercase(),
            message = message
        )

        val current = _logs.value.toMutableList()
        if (current.size >= MAX_LOGS) {
            current.removeAt(0)
        }
        current.add(entry)
        _logs.value = current
    }

    fun debug(message: String, category: String = "GENERAL") = log(LogLevel.DEBUG, category, message)
    fun info(message: String, category: String = "GENERAL") = log(LogLevel.INFO, category, message)
    fun warn(message: String, category: String = "GENERAL") = log(LogLevel.WARN, category, message)
    fun error(message: String, category: String = "GENERAL") = log(LogLevel.ERROR, category, message)

    fun clear() {
        _logs.value = emptyList()
    }
}
