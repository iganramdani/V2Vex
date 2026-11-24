package com.xray99.client.core

import com.xray99.client.model.ProxyProtocol
import com.xray99.client.model.ServerProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Ultra-fast concurrent TCP handshake RTT tester with bounded concurrency.
 */
object LatencyTester {

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
