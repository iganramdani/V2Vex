package com.xray99.client.core

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages the Xray-core child process with live stdout/stderr log streaming.
 */
class XrayProcessManager(
    private val context: Context,
    private val coreManager: CoreManager
) {
    private var process: Process? = null
    private var logJob: Job? = null

    val isRunning: Boolean
        get() = process?.let { runCatching { it.exitValue(); false }.getOrDefault(true) } == true

    /**
     * Writes generated config.json and launches Xray-core if an executable binary is available.
     * Returns a pair of (startedSuccessfully, statusLabel).
     */
    fun start(configJson: String, scope: CoroutineScope): Pair<Boolean, String> {
        stop()

        val configFile = File(context.filesDir, "config.json")
        configFile.writeText(configJson)

        val (binary, source) = coreManager.resolveExecutableBinary()
        if (binary == null || !binary.canExecute()) {
            LogBuffer.w(
                "Core",
                "No executable Xray binary found ($source). VPN tunnel running in Config-Ready / Lightweight mode. Use Settings -> Core Manager to install Xray-core."
            )
            return false to "Config-Ready (Install Xray-core)"
        }

        return try {
            val pb = ProcessBuilder(binary.absolutePath, "run", "-c", configFile.absolutePath)
                .directory(coreManager.coreDir)
                .redirectErrorStream(true)
            pb.environment()["XRAY_LOCATION_ASSET"] = coreManager.assetsDir.absolutePath

            val proc = pb.start()
            process = proc
            LogBuffer.i("Xray", "Started Xray-core from $source (${binary.name})")

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
            true to "Xray-core ($source)"
        } catch (t: Throwable) {
            LogBuffer.e("Xray", "Failed to launch Xray-core: ${t.message}")
            false to "Core Launch Error"
        }
    }

    fun stop() {
        logJob?.cancel()
        logJob = null
        process?.let { proc ->
            runCatching {
                proc.destroy()
            }
        }
        process = null
    }
}
