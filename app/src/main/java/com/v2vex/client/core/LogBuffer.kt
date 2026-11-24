package com.v2vex.client.core

import android.content.Context
import android.os.Build
import com.v2vex.client.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

enum class LogLevel(val tag: String) {
    DEBUG("DBG"),
    INFO("INF"),
    WARN("WRN"),
    ERROR("ERR")
}

data class LogEntry(
    val id: Long,
    val timestamp: String,
    val level: LogLevel,
    val source: String,
    val message: String
)

/**
 * Real-time diagnostic ring buffer (3000 entries) with:
 * - 100ms batched live UI emission
 * - Async rolling file persistence (filesDir/logs/v2vex_realtime.log)
 * - Global uncaught crash recorder (last_crash.log)
 * - Full structured Bug Report generator (sanitized)
 */
object LogBuffer {
    private const val MAX_ENTRIES = 3000
    private const val MAX_FILE_BYTES = 1_500_000L

    private val lock = Any()
    private val buffer = ArrayDeque<LogEntry>(MAX_ENTRIES)
    private var nextId = 1L

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val flushScheduled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var crashFile: File? = null

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val rf = File(dir, "v2vex_realtime.log")
        val cf = File(dir, "last_crash.log")
        logFile = rf
        crashFile = cf

        // Load previous crash if any
        if (cf.exists() && cf.length() > 0L) {
            val prevCrash = runCatching { cf.readText().take(2500) }.getOrNull()
            if (!prevCrash.isNullOrBlank()) {
                log(LogLevel.ERROR, "CRASH", "Previous session crash detected:\n$prevCrash")
            }
            cf.delete()
        }

        // Install global crash handler so fatal bugs are never lost
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                val sw = StringWriter()
                throwable.printStackTrace(PrintWriter(sw))
                val text = "Thread: ${thread.name}\n$sw"
                cf.writeText(text)
                rf.appendText("\n[CRASH] $text\n")
            }
            previousHandler?.uncaughtException(thread, throwable)
        }

        i(
            "APP",
            "Device=${Build.MANUFACTURER} ${Build.MODEL} | Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) | ABI=${Build.SUPPORTED_ABIS.joinToString()} | V2Vex ${BuildConfig.VERSION_NAME}"
        )
    }

    fun log(level: LogLevel, source: String, message: String) {
        val ts = timeFmt.get()?.format(Date()) ?: ""
        val entry: LogEntry
        synchronized(lock) {
            if (buffer.size >= MAX_ENTRIES) {
                buffer.removeFirst()
            }
            entry = LogEntry(
                id = nextId++,
                timestamp = ts,
                level = level,
                source = source,
                message = message
            )
            buffer.addLast(entry)
        }
        val formattedLine = "[$ts] [${level.tag}] [$source] $message\n"
        scope.launch {
            appendToDisk(formattedLine)
        }
        scheduleFlush()
    }

    private fun appendToDisk(line: String) {
        val f = logFile ?: return
        runCatching {
            if (f.exists() && f.length() > MAX_FILE_BYTES) {
                val tail = f.readText().takeLast(500_000)
                f.writeText("--- log rotated ---\n$tail")
            }
            f.appendText(line)
        }
    }

    private fun scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) {
            scope.launch {
                delay(100)
                flushScheduled.set(false)
                val snapshot = synchronized(lock) { buffer.toList() }
                _logs.value = snapshot
            }
        }
    }

    fun d(source: String, msg: String) = log(LogLevel.DEBUG, source, msg)
    fun i(source: String, msg: String) = log(LogLevel.INFO, source, msg)
    fun w(source: String, msg: String) = log(LogLevel.WARN, source, msg)
    fun e(source: String, msg: String) = log(LogLevel.ERROR, source, msg)

    fun clear() {
        synchronized(lock) {
            buffer.clear()
        }
        _logs.value = emptyList()
        scope.launch { runCatching { logFile?.writeText("") } }
    }

    fun dumpText(): String = synchronized(lock) { buffer.toList() }.joinToString("\n") {
        "[${it.timestamp}] [${it.level.tag}] [${it.source}] ${it.message}"
    }

    /**
     * Generates a complete, sanitized diagnostic report ready to paste for rapid bug analysis.
     */
    fun buildFullBugReport(context: Context, extraHeader: String = ""): String {
        val sb = StringBuilder()
        sb.appendLine("===== V2VEX REAL-TIME DIAGNOSTIC REPORT =====")
        sb.appendLine("App: V2Vex ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("ABIs: ${Build.SUPPORTED_ABIS.joinToString()}")
        if (extraHeader.isNotBlank()) {
            sb.appendLine(extraHeader)
        }
        val tproxyFile = File(context.filesDir, "tproxy.yml")
        if (tproxyFile.exists()) {
            sb.appendLine("\n--- tproxy.yml ---")
            sb.appendLine(runCatching { tproxyFile.readText() }.getOrDefault("<unreadable>"))
        }
        val cfgFile = File(context.filesDir, "config.json")
        if (cfgFile.exists()) {
            sb.appendLine("\n--- config.json (secrets redacted) ---")
            val raw = runCatching { cfgFile.readText() }.getOrDefault("")
            val redacted = raw
                .replace(Regex(""""(id|password|secretKey|publicKey|preSharedKey)"\s*:\s*"[^"]+""""), """"$1": "<REDACTED>"""")
            sb.appendLine(redacted)
        }
        sb.appendLine("\n--- REAL-TIME LOGS (${buffer.size} entries) ---")
        sb.appendLine(dumpText())
        sb.appendLine("===== END REPORT =====")
        return sb.toString()
    }
}
