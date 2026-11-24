package com.v2vex.client.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLParameters
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

data class BugHostProbeResult(
    val host: String,
    val reachable: Boolean,
    val rttMs: Int,
    val httpCode: Int = 0,
    val serverHeader: String = "",
    val tlsProtocol: String = "",
    val certSubject: String = "",
    val error: String? = null
)

/**
 * BugHost / CDN Sniper Studio (v3.0 Pro Tool):
 * Concurrent TCP + TLS SNI + HTTP HEAD scanner to discover zero-rated / clean CDN front hosts
 * (Cloudflare, Akamai, TikTok/Byte-nginx, Fastly) and 1-tap inject into V2Vex nodes.
 */
object BugHostSniper {

    val DEFAULT_CANDIDATES = listOf(
        "www.cloudflare.com",
        "speed.cloudflare.com",
        "api24-normal-alisg.tiktokv.com",
        "www.tiktok.com",
        "m.youtube.com",
        "graph.instagram.com",
        "ava.game.naver.com",
        "quiz.vidio.com"
    )

    suspend fun scanBatch(
        hosts: List<String>,
        port: Int = 443,
        maxConcurrency: Int = 8,
        onProgress: (BugHostProbeResult) -> Unit = {}
    ): List<BugHostProbeResult> = coroutineScope {
        val sem = Semaphore(maxConcurrency)
        val cleanHosts = hosts.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.distinct()
        cleanHosts.map { host ->
            async(Dispatchers.IO) {
                sem.withPermit {
                    val res = probeSingleTlsHost(host, port)
                    onProgress(res)
                    res
                }
            }
        }.awaitAll().sortedWith(compareBy<BugHostProbeResult> { !it.reachable }.thenBy { it.rttMs })
    }

    private fun probeSingleTlsHost(host: String, port: Int): BugHostProbeResult = withContextSync {
        var sslSocket: SSLSocket? = null
        try {
            val startNs = System.nanoTime()
            val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
            val rawSocket = java.net.Socket()
            rawSocket.connect(InetSocketAddress(host, port), 3500)
            rawSocket.soTimeout = 3500

            sslSocket = (factory.createSocket(rawSocket, host, port, true) as SSLSocket).apply {
                val params = SSLParameters()
                params.serverNames = listOf(SNIHostName(host))
                sslParameters = params
                startHandshake()
            }
            val rttMs = ((System.nanoTime() - startNs) / 1_000_000L).toInt().coerceAtLeast(1)
            val session = sslSocket.session
            val tlsProto = session.protocol ?: ""
            val certSubj = runCatching {
                val cert = session.peerCertificates.firstOrNull() as? java.security.cert.X509Certificate
                cert?.subjectX500Principal?.name?.substringAfter("CN=", "")?.substringBefore(",") ?: ""
            }.getOrDefault("")

            // Lightweight HTTP HEAD probe over the open TLS socket
            val writer = OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8)
            writer.write("HEAD / HTTP/1.1\r\nHost: $host\r\nUser-Agent: V2Vex/3.0\r\nConnection: close\r\n\r\n")
            writer.flush()

            val reader = BufferedReader(InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
            val statusLine = reader.readLine().orEmpty()
            val httpCode = statusLine.split(" ").getOrNull(1)?.toIntOrNull() ?: 0
            var serverHdr = ""
            var line: String?
            var headerLines = 0
            while (reader.readLine().also { line = it } != null && line!!.isNotEmpty() && headerLines < 20) {
                headerLines++
                if (line!!.startsWith("Server:", ignoreCase = true)) {
                    serverHdr = line!!.substringAfter(":").trim()
                }
            }

            LogBuffer.i("SNIPER", "$host -> ${rttMs}ms | HTTP $httpCode | Server=${serverHdr.ifBlank { "hidden" }} | $tlsProto")
            BugHostProbeResult(
                host = host,
                reachable = true,
                rttMs = rttMs,
                httpCode = httpCode,
                serverHeader = serverHdr,
                tlsProtocol = tlsProto,
                certSubject = certSubj
            )
        } catch (t: Throwable) {
            BugHostProbeResult(
                host = host,
                reachable = false,
                rttMs = -1,
                error = t.javaClass.simpleName
            )
        } finally {
            runCatching { sslSocket?.close() }
        }
    }

    private inline fun <T> withContextSync(block: () -> T): T = block()
}
