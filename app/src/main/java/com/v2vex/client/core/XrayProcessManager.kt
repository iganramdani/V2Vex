package com.v2vex.client.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Manages Xray-core / Sing-box child process with:
 * - Accurate Xray severity classification (respects [Error]/[Warning]/[Info]/[Debug] tags)
 * - Startup health probe & crash watchdog
 * - Zombie-proof SIGTERM -> SIGKILL cleanup
 */
class XrayProcessManager(
    private val context: Context,
    private val coreManager: CoreManager
) {
    private var process: Process? = null
    private var logJob: Job? = null
    private var watchdogJob: Job? = null

    val isRunning: Boolean
        get() = process?.let { runCatching { it.exitValue(); false }.getOrDefault(true) } == true

    suspend fun start(
        configJson: String,
        scope: CoroutineScope,
        useSingBox: Boolean = false,
        onUnexpectedExit: (Int) -> Unit = {}
    ): Pair<Boolean, String> {
        stop()

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

        return try {
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(coreManager.coreDir)
                .redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = coreManager.assetsDir.absolutePath

            val proc = pb.start()
            process = proc
            LogBuffer.i("Core", "Launched $engineName from $source (${binary.name})")

            logJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        val lvl = parseEngineLogLevel(line)
                        LogBuffer.log(lvl, engineName, line)
                    }
                }
            }

            delay(350)
            val exitedEarly = runCatching { proc.exitValue(); true }.getOrDefault(false)
            if (exitedEarly) {
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                LogBuffer.e("Core", "$engineName exited immediately (code $code). Open Live Log (.txt) for details.")
                stop()
                return false to "$engineName exited (code $code)"
            }

            watchdogJob = scope.launch(Dispatchers.IO) {
                val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (process === proc) {
                    LogBuffer.e("Core", "$engineName terminated unexpectedly (exit code $exitCode)")
                    onUnexpectedExit(exitCode)
                }
            }

            true to "$engineName ($source)"
        } catch (t: Throwable) {
            LogBuffer.e("Core", "Failed to launch $engineName: ${t.message}")
            stop()
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
        watchdogJob?.cancel()
        watchdogJob = null
        logJob?.cancel()
        logJob = null
        val proc = process
        process = null
        if (proc != null) {
            runCatching {
                proc.destroy()
                if (!proc.waitFor(500, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(300, TimeUnit.MILLISECONDS)
                }
            }
        }
    }
}
