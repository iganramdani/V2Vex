package com.v2vex.client.core

import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

/**
 * Dual-mode Latency Tester:
 * 1. Real Proxy URLTest (HTTP generate_204 through active local HTTP proxy — proves tunnel actually works!)
 * 2. Fast bounded-concurrency TCP RTT batch tester for quick multi-node scanning.
 */
object LatencyTester {

    /**
     * Real end-to-end delay through the active V2Vex local HTTP proxy (validates UUID/REALITY/server!).
     */
    suspend fun testRealProxyDelay(
        httpProxyPort: Int,
        probeUrl: String = "https://www.gstatic.com/generate_204",
        timeoutMs: Int = 5000
    ): Int = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        return@withContext try {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", httpProxyPort))
            val startNs = System.nanoTime()
            conn = (URL(probeUrl).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "HEAD"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                useCaches = false
                setRequestProperty("Connection", "close")
            }
            val code = conn.responseCode
            if (code in 200..299) {
                ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            } else -1
        } catch (_: Throwable) {
            -1
        } finally {
            conn?.disconnect()
        }
    }

    suspend fun testSingle(profile: ServerProfile, timeoutMs: Int = 3000): Int = withContext(Dispatchers.IO) {
        if (profile.protocol == ProxyProtocol.RAW_JSON || profile.address.isBlank()) {
            return@withContext -1
        }
        val socket = Socket()
        return@withContext try {
            val startNs = System.nanoTime()
            socket.connect(InetSocketAddress(profile.address, profile.port), timeoutMs)
            ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
        } catch (_: Throwable) {
            -1
        } finally {
            runCatching { socket.close() }
        }
    }

    suspend fun testBatch(
        profiles: List<ServerProfile>,
        maxConcurrency: Int = 12,
        onResult: (profileId: String, latencyMs: Int) -> Unit
    ): Map<String, Int> = coroutineScope {
        val sem = Semaphore(maxConcurrency)
        profiles.map { profile ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val ms = testSingle(profile)
                    onResult(profile.id, ms)
                    profile.id to ms
                }
            }
        }.awaitAll().toMap()
    }
}
