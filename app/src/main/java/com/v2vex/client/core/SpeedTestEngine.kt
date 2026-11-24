package com.v2vex.client.core

import com.v2vex.client.model.SpeedTestPhase
import com.v2vex.client.model.SpeedTestProgress
import com.v2vex.client.model.SpeedTestRecord
import com.v2vex.client.model.SpeedTestSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URI
import java.net.URL
import java.util.Random
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * SpeedLab v2 — Lab-Grade Multi-Stream Mbps Benchmark Engine.
 *
 * Fixes from Brutal Audit:
 * 1. Keep-Alive True RTT & Jitter: warms up 1 TLS connection first, then probes 6 consecutive
 *    HEAD requests over the warm socket (no TLS handshake inflation!).
 * 2. Server-ACK Upload Throughput: measures complete request->HTTP-200 round-trips for
 *    progressive chunks (eliminates local TCP send-buffer false spikes!).
 * 3. Multi-Stream Download with warm-up discard + trimmed mean + monotonic System.nanoTime().
 */
class SpeedTestEngine {

    suspend fun runBenchmark(
        settings: SpeedTestSettings,
        useProxy: Boolean,
        httpProxyPort: Int,
        profileId: String?,
        profileName: String,
        onProgress: (SpeedTestProgress) -> Unit
    ): SpeedTestRecord = withContext(Dispatchers.IO) {
        val proxy = if (useProxy) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpProxyPort))
        } else {
            Proxy.NO_PROXY
        }
        val endpointHost = runCatching { URI(settings.downloadUrl).host ?: "custom" }.getOrDefault("custom")

        try {
            // Phase 1: Warm Keep-Alive Ping & RFC 3550 Jitter
            onProgress(
                SpeedTestProgress(
                    phase = SpeedTestPhase.PING,
                    progressFraction = 0.04f,
                    statusDetail = "Warming TLS socket (${if (useProxy) "via tunnel" else "direct"})..."
                )
            )
            // Warm-up probe (discarded — absorbs DNS + TCP + TLS handshake)
            measureKeepAliveRttMs(settings.pingUrl, proxy)

            val rtts = mutableListOf<Double>()
            repeat(6) { idx ->
                val rtt = measureKeepAliveRttMs(settings.pingUrl, proxy)
                if (rtt != null) rtts.add(rtt)
                val avg = if (rtts.isNotEmpty()) rtts.average() else 0.0
                val jit = SpeedMath.computeJitterMs(rtts)
                onProgress(
                    SpeedTestProgress(
                        phase = SpeedTestPhase.PING,
                        progressFraction = 0.05f + ((idx + 1) / 6f) * 0.15f,
                        pingMs = avg,
                        jitterMs = jit,
                        statusDetail = "True RTT ${idx + 1}/6: ${String.format("%.1f ms", rtt ?: 0.0)}"
                    )
                )
                delay(100)
            }

            val finalPing = if (rtts.isNotEmpty()) rtts.average() else 0.0
            val finalJitter = SpeedMath.computeJitterMs(rtts)

            // Phase 2: Multi-Stream Download
            val dlResult = measureMultiStreamDownload(
                urlStr = settings.downloadUrl,
                proxy = proxy,
                streams = settings.parallelStreams.coerceIn(1, 8),
                durationSec = settings.durationSeconds.coerceIn(3, 20),
                warmupMs = settings.warmupMillis.coerceIn(300L, 4000L)
            ) { currentMbps, peakMbps, frac, bytes, samples ->
                onProgress(
                    SpeedTestProgress(
                        phase = SpeedTestPhase.DOWNLOAD,
                        progressFraction = 0.20f + frac * 0.45f,
                        currentMbps = currentMbps,
                        peakMbps = peakMbps,
                        downloadMbps = SpeedMath.trimmedMeanMbps(samples),
                        pingMs = finalPing,
                        jitterMs = finalJitter,
                        stabilityPercent = SpeedMath.computeStabilityPercent(samples),
                        activeStreams = settings.parallelStreams,
                        bytesTransferred = bytes,
                        liveSamplesMbps = samples.takeLast(40),
                        statusDetail = "Downloading (${settings.parallelStreams} streams) · ${SpeedMath.formatBytes(bytes)}"
                    )
                )
            }

            // Phase 3: Server-ACK Multi-Stream Upload (no local socket buffer bias!)
            val ulResult = measureServerAckUpload(
                urlStr = settings.uploadUrl,
                proxy = proxy,
                streams = (settings.parallelStreams / 2).coerceIn(1, 4),
                durationSec = (settings.durationSeconds - 1).coerceIn(4, 15)
            ) { currentMbps, peakMbps, frac, bytes, samples ->
                onProgress(
                    SpeedTestProgress(
                        phase = SpeedTestPhase.UPLOAD,
                        progressFraction = 0.65f + frac * 0.35f,
                        currentMbps = currentMbps,
                        peakMbps = maxOf(dlResult.peakMbps, peakMbps),
                        downloadMbps = dlResult.sustainedMbps,
                        uploadMbps = SpeedMath.trimmedMeanMbps(samples),
                        pingMs = finalPing,
                        jitterMs = finalJitter,
                        stabilityPercent = dlResult.stabilityPercent,
                        activeStreams = (settings.parallelStreams / 2).coerceIn(1, 4),
                        bytesTransferred = dlResult.totalBytes + bytes,
                        liveSamplesMbps = samples.takeLast(40),
                        statusDetail = "Server-ACK Upload · ${SpeedMath.formatBytes(bytes)}"
                    )
                )
            }

            val record = SpeedTestRecord(
                profileId = profileId,
                profileName = profileName,
                viaProxy = useProxy,
                downloadMbps = dlResult.sustainedMbps,
                peakDownloadMbps = dlResult.peakMbps,
                uploadMbps = ulResult.sustainedMbps,
                pingMs = finalPing,
                jitterMs = finalJitter,
                stabilityPercent = dlResult.stabilityPercent,
                streams = settings.parallelStreams,
                endpointHost = endpointHost
            )

            onProgress(
                SpeedTestProgress(
                    phase = SpeedTestPhase.COMPLETED,
                    progressFraction = 1f,
                    currentMbps = dlResult.sustainedMbps,
                    peakMbps = dlResult.peakMbps,
                    downloadMbps = dlResult.sustainedMbps,
                    uploadMbps = ulResult.sustainedMbps,
                    pingMs = finalPing,
                    jitterMs = finalJitter,
                    stabilityPercent = dlResult.stabilityPercent,
                    bytesTransferred = dlResult.totalBytes + ulResult.totalBytes,
                    liveSamplesMbps = dlResult.samples.takeLast(40),
                    statusDetail = "Done · ${String.format("%.2f", dlResult.sustainedMbps)} Mbps ↓ / ${String.format("%.2f", ulResult.sustainedMbps)} Mbps ↑"
                )
            )

            LogBuffer.i(
                "SpeedLab",
                "Done [$profileName]: DL=${String.format("%.2f", record.downloadMbps)} Mbps, " +
                    "UL=${String.format("%.2f", record.uploadMbps)} Mbps, RTT=${String.format("%.1f", record.pingMs)}ms, Jitter=${String.format("%.1f", record.jitterMs)}ms"
            )
            record
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            LogBuffer.e("SpeedLab", "Benchmark error: ${t.message}")
            onProgress(
                SpeedTestProgress(
                    phase = SpeedTestPhase.ERROR,
                    statusDetail = "Benchmark failed",
                    errorMessage = t.message ?: "Network error"
                )
            )
            throw t
        }
    }

    /**
     * Measures pure HTTP RTT using Connection: keep-alive so HttpURLConnection reuses the warm TLS socket.
     */
    private fun measureKeepAliveRttMs(urlStr: String, proxy: Proxy): Double? {
        return try {
            val start = System.nanoTime()
            val conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "keep-alive")
            }
            conn.responseCode
            runCatching { conn.inputStream.close() }
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: Throwable) {
            null
        }
    }

    private data class TransferResult(
        val sustainedMbps: Double,
        val peakMbps: Double,
        val stabilityPercent: Double,
        val totalBytes: Long,
        val samples: List<Double>
    )

    private suspend fun measureMultiStreamDownload(
        urlStr: String,
        proxy: Proxy,
        streams: Int,
        durationSec: Int,
        warmupMs: Long,
        onTick: (currentMbps: Double, peakMbps: Double, progress: Float, totalBytes: Long, samples: List<Double>) -> Unit
    ): TransferResult = coroutineScope {
        val totalBytes = AtomicLong(0L)
        val stopSignal = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val warmupEndNs = startNs + warmupMs * 1_000_000L
        val endNs = startNs + durationSec * 1_000_000_000L

        val workers = List(streams) { streamIdx ->
            async(Dispatchers.IO) {
                val buf = ByteArray(64 * 1024)
                while (!stopSignal.get() && isActive && System.nanoTime() < endNs) {
                    var conn: HttpURLConnection? = null
                    try {
                        val cacheBust = if (urlStr.contains("?")) "&r=$streamIdx-${System.nanoTime()}" else "?r=$streamIdx-${System.nanoTime()}"
                        conn = (URL(urlStr + cacheBust).openConnection(proxy) as HttpURLConnection).apply {
                            connectTimeout = 6000
                            readTimeout = 6000
                            useCaches = false
                            setRequestProperty("Accept-Encoding", "identity")
                            setRequestProperty("Cache-Control", "no-store")
                        }
                        conn.inputStream.use { input ->
                            while (!stopSignal.get() && isActive && System.nanoTime() < endNs) {
                                val read = input.read(buf)
                                if (read <= 0) break
                                totalBytes.addAndGet(read.toLong())
                            }
                        }
                    } catch (_: Throwable) {
                        delay(150)
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
        }

        val steadySamples = mutableListOf<Double>()
        var peakMbps = 0.0
        var prevBytes = 0L
        var prevNs = startNs

        val sampler = launch {
            while (isActive && System.nanoTime() < endNs) {
                delay(150)
                val nowNs = System.nanoTime()
                val curBytes = totalBytes.get()
                val deltaBytes = (curBytes - prevBytes).coerceAtLeast(0L)
                val deltaNs = (nowNs - prevNs).coerceAtLeast(1L)
                val instMbps = SpeedMath.bytesToMbps(deltaBytes, deltaNs)
                prevBytes = curBytes
                prevNs = nowNs

                if (nowNs >= warmupEndNs && instMbps > 0.0) {
                    steadySamples.add(instMbps)
                    if (instMbps > peakMbps) peakMbps = instMbps
                }
                val frac = ((nowNs - startNs).toFloat() / (durationSec * 1_000_000_000f)).coerceIn(0f, 1f)
                onTick(instMbps, peakMbps, frac, curBytes, steadySamples.toList())
            }
            stopSignal.set(true)
        }

        sampler.join()
        workers.forEach { it.cancel() }
        runCatching { workers.awaitAll() }

        val sustained = if (steadySamples.isNotEmpty()) {
            SpeedMath.trimmedMeanMbps(steadySamples)
        } else {
            SpeedMath.bytesToMbps(totalBytes.get(), System.nanoTime() - startNs)
        }
        TransferResult(
            sustainedMbps = sustained,
            peakMbps = maxOf(peakMbps, sustained),
            stabilityPercent = SpeedMath.computeStabilityPercent(steadySamples),
            totalBytes = totalBytes.get(),
            samples = steadySamples
        )
    }

    /**
     * Server-ACK Upload: only credits bytes AFTER the remote server returns HTTP 2xx,
     * eliminating local socket/proxy buffer bias.
     */
    private suspend fun measureServerAckUpload(
        urlStr: String,
        proxy: Proxy,
        streams: Int,
        durationSec: Int,
        onTick: (currentMbps: Double, peakMbps: Double, progress: Float, totalBytes: Long, samples: List<Double>) -> Unit
    ): TransferResult = coroutineScope {
        val ackedBytes = AtomicLong(0L)
        val stopSignal = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val endNs = startNs + durationSec * 1_000_000_000L

        // Pre-allocate 512KB non-compressible payload chunk
        val chunk = ByteArray(512 * 1024).also { Random(99L).nextBytes(it) }

        val workers = List(streams) {
            async(Dispatchers.IO) {
                while (!stopSignal.get() && isActive && System.nanoTime() < endNs) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            connectTimeout = 6000
                            readTimeout = 8000
                            useCaches = false
                            setFixedLengthStreamingMode(chunk.size)
                            setRequestProperty("Content-Type", "application/octet-stream")
                            setRequestProperty("Connection", "keep-alive")
                        }
                        conn.outputStream.use { out ->
                            out.write(chunk)
                            out.flush()
                        }
                        val code = conn.responseCode
                        runCatching { conn.inputStream.close() }
                        if (code in 200..299) {
                            ackedBytes.addAndGet(chunk.size.toLong())
                        }
                    } catch (_: Throwable) {
                        delay(150)
                    } finally {
                        conn?.disconnect()
                    }
                }
            }
        }

        val samples = mutableListOf<Double>()
        var peakMbps = 0.0
        var prevBytes = 0L
        var prevNs = startNs

        val sampler = launch {
            while (isActive && System.nanoTime() < endNs) {
                delay(300)
                val nowNs = System.nanoTime()
                val curBytes = ackedBytes.get()
                val deltaBytes = (curBytes - prevBytes).coerceAtLeast(0L)
                val deltaNs = (nowNs - prevNs).coerceAtLeast(1L)
                val instMbps = SpeedMath.bytesToMbps(deltaBytes, deltaNs)
                prevBytes = curBytes
                prevNs = nowNs

                if (instMbps > 0.0) {
                    samples.add(instMbps)
                    if (instMbps > peakMbps) peakMbps = instMbps
                }
                val frac = ((nowNs - startNs).toFloat() / (durationSec * 1_000_000_000f)).coerceIn(0f, 1f)
                onTick(instMbps, peakMbps, frac, curBytes, samples.toList())
            }
            stopSignal.set(true)
        }

        sampler.join()
        workers.forEach { it.cancel() }
        runCatching { workers.awaitAll() }

        val sustained = if (samples.isNotEmpty()) {
            SpeedMath.trimmedMeanMbps(samples)
        } else {
            SpeedMath.bytesToMbps(ackedBytes.get(), System.nanoTime() - startNs)
        }
        TransferResult(
            sustainedMbps = sustained,
            peakMbps = maxOf(peakMbps, sustained),
            stabilityPercent = SpeedMath.computeStabilityPercent(samples),
            totalBytes = ackedBytes.get(),
            samples = samples
        )
    }
}
