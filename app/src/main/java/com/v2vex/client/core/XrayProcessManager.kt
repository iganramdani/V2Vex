package com.v2vex.client.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.Collections
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Manages Xray-core / Sing-box child process with:
 * - Generation-guarded lifecycle (intentional restarts NEVER trigger false-positive exit-143/141 crashes!)
 * - Startup stderr capture pinned straight into System Logs (exact code-23 / Sing-box fatal reason!)
 * - Accurate Xray/Sing-box severity classification
 * - Zombie-proof SIGTERM -> SIGKILL cleanup
 */
class XrayProcessManager(
    private val context: Context,
    private val coreManager: CoreManager
) {
    private val generation = AtomicLong(0L)
    @Volatile
    private var process: Process? = null
    private var logJob: Job? = null
    private var watchdogJob: Job? = null

    val isRunning: Boolean
        get() = process?.let { runCatching { it.exitValue(); false }.getOrDefault(true) } == true

    suspend fun start(
        configJson: String,
        scope: CoroutineScope,
        useSingBox: Boolean = false,
        onUnexpectedExit: (Int, String?) -> Unit = { _, _ -> }
    ): Pair<Boolean, String> {
        val myGen = generation.incrementAndGet()
        stopInternal()

        val configFile = File(context.filesDir, if (useSingBox) "singbox.json" else "config.json")
        configFile.writeText(configJson)

        val (binary, source) = if (useSingBox) {
            coreManager.resolveSingBoxBinary()
        } else {
            coreManager.resolveExecutableBinary()
        }
        val engineName = if (useSingBox) "Sing-box" else "Xray-core"
        if (binary == null || !binary.canExecute()) {
            LogBuffer.e("Core", "No executable $engineName binary found ($source). Cannot start tunnel.")
            return false to "No executable $engineName ($source)"
        }

        val startupLines = Collections.synchronizedList(mutableListOf<String>())

        return try {
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(coreManager.coreDir)
                .redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = coreManager.assetsDir.absolutePath

            val proc = pb.start()
            if (generation.get() != myGen) {
                destroyProcess(proc)
                return false to "Superseded by newer start"
            }
            process = proc
            LogBuffer.i("Core", "Launched $engineName from $source (${binary.name})")

            logJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (startupLines.size < 20) {
                            startupLines.add(line)
                        }
                        val lvl = parseEngineLogLevel(line)
                        LogBuffer.log(lvl, engineName, line)
                    }
                }
            }

            delay(420)
            // If another start/stop superseded us during the probe delay, bail cleanly without error!
            if (generation.get() != myGen) {
                return false to "Superseded"
            }

            val exitedEarly = runCatching { proc.exitValue(); true }.getOrDefault(false)
            if (exitedEarly) {
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                // Give stdout reader a brief moment to drain the final fatal line
                delay(60)
                val reason = synchronized(startupLines) {
                    startupLines.lastOrNull {
                        it.contains("failed", ignoreCase = true) ||
                            it.contains("error", ignoreCase = true) ||
                            it.contains("FATAL", ignoreCase = true) ||
                            it.contains("invalid", ignoreCase = true)
                    } ?: startupLines.lastOrNull()
                }?.take(220)
                val detail = if (!reason.isNullOrBlank()) " — $reason" else ""
                LogBuffer.e("Core", "$engineName exited immediately (code $code)$detail")
                stopInternal()
                return false to "$engineName exited (code $code)$detail"
            }

            watchdogJob = scope.launch(Dispatchers.IO) {
                val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                // Only fire unexpected exit if THIS generation is still the active process!
                if (generation.get() == myGen && process === proc) {
                    val lastLine = synchronized(startupLines) { startupLines.lastOrNull() }?.take(160)
                    LogBuffer.e("Core", "$engineName terminated unexpectedly (exit code $exitCode)")
                    onUnexpectedExit(exitCode, lastLine)
                }
            }

            true to "$engineName ($source)"
        } catch (t: Throwable) {
            if (generation.get() == myGen) {
                LogBuffer.e("Core", "Failed to launch $engineName: ${t.message}")
                stopInternal()
            }
            false to "$engineName launch error: ${t.message}"
        }
    }

    /**
     * Strictly respects Xray/Sing-box bracket severity tags first so normal [Info] UDP pipe
     * closures are never misclassified as [ERR].
     */
    private fun parseEngineLogLevel(line: String): LogLevel = when {
        line.contains("[Error]") || line.contains("FATAL") || line.contains("panic:") -> LogLevel.ERROR
        line.contains("[Warning]") || line.contains("WARN[") -> LogLevel.WARN
        line.contains("[Debug]") || line.contains("DEBUG[") -> LogLevel.DEBUG
        line.contains("[Info]") || line.contains("INFO[") -> LogLevel.INFO
        line.contains("error", ignoreCase = true) -> LogLevel.ERROR
        else -> LogLevel.INFO
    }

    fun stop() {
        generation.incrementAndGet()
        stopInternal()
    }

    private fun stopInternal() {
        watchdogJob?.cancel()
        watchdogJob = null
        logJob?.cancel()
        logJob = null
        val proc = process
        process = null
        if (proc != null) {
            destroyProcess(proc)
        }
    }

    private fun destroyProcess(proc: Process) {
        runCatching {
            proc.destroy()
            if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
                proc.waitFor(300, TimeUnit.MILLISECONDS)
            }
        }
    }
}
