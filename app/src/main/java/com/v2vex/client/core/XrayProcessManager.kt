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
 * Manages the Xray-core child process with:
 * - Startup health probe (never reports fake ACTIVE on immediate crash)
 * - Live stdout/stderr streaming to batched LogBuffer
 * - Exit watchdog callback
 * - Zombie-proof SIGTERM -> SIGKILL shutdown
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
            LogBuffer.e(
                "Core",
                "No executable $engineName binary found ($source). Cannot start tunnel."
            )
            return false to "No executable $engineName ($source)"
        }

        return try {
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(coreManager.coreDir)
                .redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = coreManager.assetsDir.absolutePath

            val proc = pb.start()
            process = proc
            LogBuffer.i(engineName, "Launching $engineName from $source (${binary.name})")

            logJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        val lvl = when {
                            line.contains("[Error]", ignoreCase = true) ||
                                line.contains("FATAL", ignoreCase = true) ||
                                line.contains("failed", ignoreCase = true) -> LogLevel.ERROR
                            line.contains("[Warning]", ignoreCase = true) ||
                                line.contains("WARN", ignoreCase = true) -> LogLevel.WARN
                            line.contains("[Debug]", ignoreCase = true) -> LogLevel.DEBUG
                            else -> LogLevel.INFO
                        }
                        LogBuffer.log(lvl, engineName, line)
                    }
                }
            }

            // Health probe: wait 350ms to ensure core didn't crash immediately on config/port error
            delay(350)
            val exitedEarly = runCatching { proc.exitValue(); true }.getOrDefault(false)
            if (exitedEarly) {
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                LogBuffer.e(engineName, "$engineName exited immediately (code $code). Check Live Logs.")
                stop()
                return false to "$engineName exited (code $code)"
            }

            // Watchdog: notify VPN service if core crashes later
            watchdogJob = scope.launch(Dispatchers.IO) {
                val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (process === proc) {
                    LogBuffer.e(engineName, "$engineName terminated unexpectedly (exit code $exitCode)")
                    onUnexpectedExit(exitCode)
                }
            }

            true to "$engineName ($source)"
        } catch (t: Throwable) {
            LogBuffer.e(engineName, "Failed to launch $engineName: ${t.message}")
            stop()
            false to "$engineName launch error: ${t.message}"
        }
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
