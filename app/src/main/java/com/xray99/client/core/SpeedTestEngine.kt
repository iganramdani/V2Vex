package com.xray99.client.core

import com.xray99.client.model.SpeedTestPhase
import com.xray99.client.model.SpeedTestProgress
import com.xray99.client.model.SpeedTestRecord
import com.xray99.client.model.SpeedTestSettings
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
 * High-accuracy multi-stream Mbps SpeedTest Engine.
 *
 * Accuracy techniques:
 * 1. Monotonic nanosecond clock (System.nanoTime()) immune to wall-clock drift.
 * 2. Warm-up phase discard (excludes TLS handshake & TCP slow-start ramp).
 * 3. Parallel multi-stream HTTP/HTTPS sockets (saturates high-bandwidth BDP links).
 * 4. 100ms sliding-window sampling + trimmed mean (rejects burst outliers).
 * 5. RFC 3550-style consecutive-difference jitter over 5 HTTP TTFB probes.
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
            // Phase 1: Ping & Jitter (5 probes)
            onProgress(
                SpeedTestProgress(
                    phase = SpeedTestPhase.PING,
                    progressFraction = 0.05f,
                    statusDetail = "Probing latency & jitter (${if (useProxy) "via tunnel" else "direct"})..."
                )
            )

            val rtts = mutableListOf<Double>()
            repeat(5) { idx ->
                val rtt = measureHttpRttMs(settings.pingUrl, proxy)
                if (rtt != null) rtts.add(rtt)
                val avg = if (rtts.isNotEmpty()) rtts.average() else 0.0
                val jit = SpeedMath.computeJitterMs(rtts)
                onProgress(
                    SpeedTestProgress(
                        phase = SpeedTestPhase.PING,
                        progressFraction = 0.05f + ((idx + 1) / 5f) * 0.15f,
                        pingMs = avg,
                        jitterMs = jit,
                        statusDetail = "Ping sample ${idx + 1}/5: ${String.format("%.1f ms", rtt ?: 0.0)}"
                    )
                )
                delay(120)
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

            // Phase 3: Multi-Stream Upload
            val ulResult = measureMultiStreamUpload(
                urlStr = settings.uploadUrl,
                proxy = proxy,
                streams = (settings.parallelStreams / 2).coerceIn(1, 4),
                durationSec = (settings.durationSeconds - 2).coerceIn(3, 15),
                warmupMs = (settings.warmupMillis / 2).coerceIn(300L, 2000L),
                chunkMb = settings.uploadPayloadMb.coerceIn(2, 16)
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
                        statusDetail = "Uploading · ${SpeedMath.formatBytes(bytes)}"
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
                    statusDetail = "Benchmark complete · ${String.format("%.2f", dlResult.sustainedMbps)} Mbps ↓ / ${String.format("%.2f", ulResult.sustainedMbps)} Mbps ↑"
                )
            )

            LogBuffer.i(
                "SpeedLab",
                "Done [$profileName]: DL=${String.format("%.2f", record.downloadMbps)} Mbps (peak ${String.format("%.2f", record.peakDownloadMbps)}), " +
                    "UL=${String.format("%.2f", record.uploadMbps)} Mbps, Ping=${String.format("%.1f", record.pingMs)}ms, Jitter=${String.format("%.1f", record.jitterMs)}ms"
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

    private fun measureHttpRttMs(urlStr: String, proxy: Proxy): Double? {
        var conn: HttpURLConnection? = null
        return try {
            val start = System.nanoTime()
            conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = 5000
                readTimeout = 5000
                useCaches = false
                setRequestProperty("Cache-Control", "no-cache")
                setRequestProperty("Connection", "close")
            }
            conn.responseCode
            (System.nanoTime() - start) / 1_000_000.0
        } catch (_: Throwable) {
            null
        } finally {
            conn?.disconnect()
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

    private suspend fun measureMultiStreamUpload(
        urlStr: String,
        proxy: Proxy,
        streams: Int,
        durationSec: Int,
        warmupMs: Long,
        chunkMb: Int,
        onTick: (currentMbps: Double, peakMbps: Double, progress: Float, totalBytes: Long, samples: List<Double>) -> Unit
    ): TransferResult = coroutineScope {
        val totalBytes = AtomicLong(0L)
        val stopSignal = AtomicBoolean(false)
        val startNs = System.nanoTime()
        val warmupEndNs = startNs + warmupMs * 1_000_000L
        val endNs = startNs + durationSec * 1_000_000_000L

        // Pre-generate 64KB non-compressible random block once (zero GC pressure during upload)
        val randomBlock = ByteArray(64 * 1024).also { Random(42L).nextBytes(it) }
        val payloadBytesPerReq = chunkMb * 1024L * 1024L

        val workers = List(streams) {
            async(Dispatchers.IO) {
                while (!stopSignal.get() && isActive && System.nanoTime() < endNs) {
                    var conn: HttpURLConnection? = null
                    try {
                        conn = (URL(urlStr).openConnection(proxy) as HttpURLConnection).apply {
                            requestMethod = "POST"
                            doOutput = true
                            connectTimeout = 6000
                            readTimeout = 6000
                            useCaches = false
                            setChunkedStreamingMode(64 * 1024)
                            setRequestProperty("Content-Type", "application/octet-stream")
                        }
                        conn.outputStream.use { out ->
                            var sent = 0L
                            while (!stopSignal.get() && isActive && System.nanoTime() < endNs && sent < payloadBytesPerReq) {
                                out.write(randomBlock)
                                sent += randomBlock.size
                                totalBytes.addAndGet(randomBlock.size.toLong())
                            }
                            out.flush()
                        }
                        runCatching { conn.responseCode }
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
}
