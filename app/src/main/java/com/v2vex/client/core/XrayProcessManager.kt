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
        onUnexpectedExit: (Int) -> Unit = {}
    ): Pair<Boolean, String> {
        stop()

        val configFile = File(context.filesDir, "config.json")
        configFile.writeText(configJson)

        val (binary, source) = coreManager.resolveExecutableBinary()
        if (binary == null || !binary.canExecute()) {
            LogBuffer.e(
                "Core",
                "No executable Xray binary found ($source). Cannot start tunnel."
            )
            return false to "No executable Xray-core ($source)"
        }

        return try {
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(coreManager.coreDir)
                .redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = coreManager.assetsDir.absolutePath

            val proc = pb.start()
            process = proc
            LogBuffer.i("Xray", "Launching Xray-core from $source (${binary.name})")

            logJob = scope.launch(Dispatchers.IO) {
                runCatching {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        val lvl = when {
                            line.contains("[Error]", ignoreCase = true) ||
                                line.contains("failed", ignoreCase = true) -> LogLevel.ERROR
                            line.contains("[Warning]", ignoreCase = true) -> LogLevel.WARN
                            line.contains("[Debug]", ignoreCase = true) -> LogLevel.DEBUG
                            else -> LogLevel.INFO
                        }
                        LogBuffer.log(lvl, "Xray", line)
                    }
                }
            }

            // Health probe: wait 350ms to ensure Xray didn't crash immediately on config/port error
            delay(350)
            val exitedEarly = runCatching { proc.exitValue(); true }.getOrDefault(false)
            if (exitedEarly) {
                val code = runCatching { proc.exitValue() }.getOrDefault(-1)
                LogBuffer.e("Xray", "Xray-core exited immediately (code $code). Check Live Logs.")
                stop()
                return false to "Xray exited (code $code)"
            }

            // Watchdog: notify VPN service if Xray crashes later
            watchdogJob = scope.launch(Dispatchers.IO) {
                val exitCode = runCatching { proc.waitFor() }.getOrDefault(-1)
                if (process === proc) {
                    LogBuffer.e("Xray", "Xray-core terminated unexpectedly (exit code $exitCode)")
                    onUnexpectedExit(exitCode)
                }
            }

            true to "Xray-core ($source)"
        } catch (t: Throwable) {
            LogBuffer.e("Xray", "Failed to launch Xray-core: ${t.message}")
            stop()
            false to "Core launch error: ${t.message}"
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
