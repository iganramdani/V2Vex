package com.xray99.client.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class LogLevel(val tag: String) {
    DEBUG("DBG"),
    INFO("INF"),
    WARN("WRN"),
    ERROR("ERR")
}

data class LogEntry(
    val timestamp: String,
    val level: LogLevel,
    val source: String,
    val message: String
)

object LogBuffer {
    private const val MAX_ENTRIES = 600
    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun log(level: LogLevel, source: String, message: String) {
        val entry = LogEntry(
            timestamp = timeFmt.get()?.format(Date()) ?: "",
            level = level,
            source = source,
            message = message
        )
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            buffer.addLast(entry)
            _logs.value = buffer.toList()
        }
    }

    fun d(source: String, msg: String) = log(LogLevel.DEBUG, source, msg)
    fun i(source: String, msg: String) = log(LogLevel.INFO, source, msg)
    fun w(source: String, msg: String) = log(LogLevel.WARN, source, msg)
    fun e(source: String, msg: String) = log(LogLevel.ERROR, source, msg)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
            _logs.value = emptyList()
        }
    }

    fun dumpText(): String = _logs.value.joinToString("\n") {
        "[${it.timestamp}] [${it.level.tag}] [${it.source}] ${it.message}"
    }
}
