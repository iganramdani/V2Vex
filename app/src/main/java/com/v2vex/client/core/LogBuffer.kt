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
    val message: String,
    val isSystem: Boolean
)

data class RecentConnection(
    val id: Long,
    val timestamp: String,
    val network: String,
    val destination: String,
    val outbound: String
)

/**
 * Dual-Channel Real-Time Diagnostic Logger (v3.0):
 * - Pinned System Buffer (600 lines: APP, VPN, TUN, TELEM, NET, Core, SpeedLab, WARP, Sniper) -> NEVER evicted by engine flood!
 * - Engine Stream Buffer (2,400 lines: Xray-core / Sing-box)
 * - Live Connection Sniffer ring (120 recent domains/IPs for 1-Tap Firewall)
 * - Native .txt file exporter (filesDir/logs/logV2Vex.txt) for FileProvider sharing & SAF saving
 */
object LogBuffer {
    private const val MAX_SYSTEM_ENTRIES = 600
    private const val MAX_ENGINE_ENTRIES = 2400
    private const val MAX_CONNECTIONS = 120
    private const val MAX_FILE_BYTES = 1_500_000L

    private val ENGINE_SOURCES = setOf("Xray-core", "Sing-box", "Xray")

    private val lock = Any()
    private val systemBuffer = ArrayDeque<LogEntry>(MAX_SYSTEM_ENTRIES)
    private val engineBuffer = ArrayDeque<LogEntry>(MAX_ENGINE_ENTRIES)
    private val connectionBuffer = ArrayDeque<RecentConnection>(MAX_CONNECTIONS)
    private var nextId = 1L
    private var nextConnId = 1L
    private var lastSniffedDomain: String? = null

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val _recentConnections = MutableStateFlow<List<RecentConnection>>(emptyList())
    val recentConnections: StateFlow<List<RecentConnection>> = _recentConnections.asStateFlow()

    private val flushScheduled = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var logDir: File? = null

    @Volatile
    private var logFile: File? = null

    @Volatile
    private var crashFile: File? = null

    private val timeFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue(): SimpleDateFormat =
            SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    private val sniffRegex = Regex("""sniffed domain:\s*([^\s\]]+)""")
    private val detourRegex = Regex("""taking detour \[([^\]]+)\] for \[(tcp|udp):([^\]]+)\]""")

    fun init(context: Context) {
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        val rf = File(dir, "v2vex_realtime.log")
        val cf = File(dir, "last_crash.log")
        logDir = dir
        logFile = rf
        crashFile = cf

        if (cf.exists() && cf.length() > 0L) {
            val prevCrash = runCatching { cf.readText().take(2500) }.getOrNull()
            if (!prevCrash.isNullOrBlank()) {
                log(LogLevel.ERROR, "CRASH", "Previous session crash detected:\n$prevCrash")
            }
            cf.delete()
        }

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
        val isSys = source !in ENGINE_SOURCES
        synchronized(lock) {
            val entry = LogEntry(
                id = nextId++,
                timestamp = ts,
                level = level,
                source = source,
                message = message,
                isSystem = isSys
            )
            if (isSys) {
                if (systemBuffer.size >= MAX_SYSTEM_ENTRIES) systemBuffer.removeFirst()
                systemBuffer.addLast(entry)
            } else {
                if (engineBuffer.size >= MAX_ENGINE_ENTRIES) engineBuffer.removeFirst()
                engineBuffer.addLast(entry)
                extractLiveConnectionLocked(ts, message)
            }
        }
        val formattedLine = "[$ts] [${level.tag}] [$source] $message\n"
        scope.launch { appendToDisk(formattedLine) }
        scheduleFlush()
    }

    private fun extractLiveConnectionLocked(ts: String, line: String) {
        sniffRegex.find(line)?.let { m ->
            lastSniffedDomain = m.groupValues[1]
            return
        }
        detourRegex.find(line)?.let { m ->
            val outbound = m.groupValues[1]
            val net = m.groupValues[2].uppercase()
            val rawDest = m.groupValues[3]
            val hostOnly = rawDest.substringBeforeLast(":")
            val displayDest = if (hostOnly.any { it.isLetter() }) {
                rawDest
            } else {
                lastSniffedDomain?.let { "$it (${rawDest.substringAfterLast(":")})" } ?: rawDest
            }
            lastSniffedDomain = null
            if (displayDest.startsWith("www.gstatic.com")) return
            val existing = connectionBuffer.lastOrNull()
            if (existing?.destination == displayDest && existing.outbound == outbound) return
            if (connectionBuffer.size >= MAX_CONNECTIONS) connectionBuffer.removeFirst()
            connectionBuffer.addLast(
                RecentConnection(
                    id = nextConnId++,
                    timestamp = ts,
                    network = net,
                    destination = displayDest,
                    outbound = outbound
                )
            )
        }
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
                delay(120)
                flushScheduled.set(false)
                val (mergedLogs, conns) = synchronized(lock) {
                    val combined = (systemBuffer + engineBuffer).sortedBy { it.id }
                    combined to connectionBuffer.toList().asReversed()
                }
                _logs.value = mergedLogs
                _recentConnections.value = conns
            }
        }
    }

    fun d(source: String, msg: String) = log(LogLevel.DEBUG, source, msg)
    fun i(source: String, msg: String) = log(LogLevel.INFO, source, msg)
    fun w(source: String, msg: String) = log(LogLevel.WARN, source, msg)
    fun e(source: String, msg: String) = log(LogLevel.ERROR, source, msg)

    fun clear() {
        synchronized(lock) {
            systemBuffer.clear()
            engineBuffer.clear()
            connectionBuffer.clear()
        }
        _logs.value = emptyList()
        _recentConnections.value = emptyList()
        scope.launch { runCatching { logFile?.writeText("") } }
    }

    private val secretFieldRegex = Regex(
        """"(id|uuid|password|pass|user|username|secretKey|publicKey|preSharedKey|private_key|peer_public_key)"\s*:\s*"[^"]+""""
    )

    private val strictInfraFieldRegex = Regex(
        """"(address|server|serverName|server_name|verifyPeerCertByName|host|Host|path|serviceName|service_name|endpoint)"\s*:\s*"([^"]+)""""
    )

    private val safeNonPrivateValues = setOf(
        "127.0.0.1", "0.0.0.0", "localhost", "::1",
        "1.1.1.1", "1.0.0.1", "8.8.8.8", "8.8.4.4", "9.9.9.9",
        "/dns-query", "/"
    )

    /**
     * Redacts secrets (always) and optionally private infrastructure fields
     * (server addresses, SNI, bug hosts, WebSocket/HTTPUpgrade/gRPC paths) when strictPrivacy=true.
     */
    fun redactConfigJson(raw: String, strictPrivacy: Boolean = true): String {
        var out = raw.replace(secretFieldRegex, """"$1": "<REDACTED>"""")
        if (strictPrivacy) {
            out = strictInfraFieldRegex.replace(out) { m ->
                val key = m.groupValues[1]
                val value = m.groupValues[2]
                if (value in safeNonPrivateValues || value.startsWith("https://1.1.1.1") || value.startsWith("https://8.8.8.8")) {
                    m.value
                } else {
                    """"$key": "<REDACTED_HOST>""""
                }
            }
        }
        return out
    }

    fun buildFullBugReport(
        context: Context,
        extraHeader: String = "",
        strictPrivacy: Boolean = true
    ): String {
        val (sysSnap, engSnap) = synchronized(lock) {
            systemBuffer.toList() to engineBuffer.toList()
        }
        val sb = StringBuilder()
        sb.appendLine("===== V2VEX v${BuildConfig.VERSION_NAME} REAL-TIME DIAGNOSTIC REPORT (.TXT) =====")
        sb.appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date())}")
        sb.appendLine("Privacy Redaction: ${if (strictPrivacy) "STRICT (secrets + server hosts/SNI/paths masked)" else "STANDARD (secrets only)"}")
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
            val tproxyRaw = runCatching { tproxyFile.readText() }.getOrDefault("<unreadable>")
            val tproxyRedacted = tproxyRaw.replace(Regex("""(username|password):\s*.*"""), "$1: <REDACTED>")
            sb.appendLine(tproxyRedacted)
        }
        val xrayCfg = File(context.filesDir, "config.json")
        val sbCfg = File(context.filesDir, "singbox.json")
        val activeCfg = when {
            sbCfg.exists() && xrayCfg.exists() -> if (sbCfg.lastModified() >= xrayCfg.lastModified()) sbCfg else xrayCfg
            sbCfg.exists() -> sbCfg
            xrayCfg.exists() -> xrayCfg
            else -> null
        }
        if (activeCfg != null) {
            sb.appendLine("\n--- ${activeCfg.name} (${if (strictPrivacy) "strict privacy redacted" else "secrets redacted"}) ---")
            val raw = runCatching { activeCfg.readText() }.getOrDefault("")
            sb.appendLine(redactConfigJson(raw, strictPrivacy = strictPrivacy))
        }
        sb.appendLine("\n--- PINNED SYSTEM & TELEMETRY LOGS (${sysSnap.size} entries — never evicted) ---")
        sysSnap.forEach {
            sb.appendLine("[${it.timestamp}] [${it.level.tag}] [${it.source}] ${it.message}")
        }
        sb.appendLine("\n--- ENGINE STREAM LOGS (${engSnap.size} entries) ---")
        engSnap.forEach {
            sb.appendLine("[${it.timestamp}] [${it.level.tag}] [${it.source}] ${it.message}")
        }
        sb.appendLine("===== END REPORT =====")
        return sb.toString()
    }

    /**
     * Writes the full diagnostic report to filesDir/logs/logV2Vex.txt and returns the File
     * so Android FileProvider can share it as a real .txt file attachment.
     */
    fun exportTxtReportFile(
        context: Context,
        extraHeader: String = "",
        strictPrivacy: Boolean = true
    ): File {
        val dir = logDir ?: File(context.filesDir, "logs").apply { mkdirs() }
        val outFile = File(dir, "logV2Vex.txt")
        outFile.writeText(buildFullBugReport(context, extraHeader, strictPrivacy))
        return outFile
    }
}
