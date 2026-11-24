package com.v2vex.client.core

import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigInteger
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Pure-Kotlin RFC 7748 X25519 keypair generator + 1-Click Free Cloudflare WARP WireGuard
 * account generator (0 KB external crypto library bloat!).
 */
@OptIn(ExperimentalEncodingApi::class)
object WarpGenerator {

    private val P = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val A24 = BigInteger.valueOf(121665)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    data class KeyPair25519(
        val privateKeyBase64: String,
        val publicKeyBase64: String
    )

    fun generateX25519KeyPair(): KeyPair25519 {
        val priv = ByteArray(32)
        SecureRandom().nextBytes(priv)
        // RFC 7748 clamping
        priv[0] = (priv[0].toInt() and 248).toByte()
        priv[31] = (priv[31].toInt() and 127).toByte()
        priv[31] = (priv[31].toInt() or 64).toByte()

        val pub = scalarMultBase(priv)
        return KeyPair25519(
            privateKeyBase64 = Base64.encode(priv),
            publicKeyBase64 = Base64.encode(pub)
        )
    }

    /**
     * Registers a fresh Cloudflare WARP device identity and returns a ready-to-use WireGuard ServerProfile
     * (usable standalone OR as a Clean-IP Exit Hop in Multi-Hop Proxy Chain!).
     */
    suspend fun generateFreeWarpProfile(): Result<ServerProfile> = withContext(Dispatchers.IO) {
        runCatching {
            val kp = generateX25519KeyPair()
            val isoNow = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            val payload = """
                {
                  "key": "${kp.publicKeyBase64}",
                  "install_id": "",
                  "fcm_token": "",
                  "tos": "$isoNow",
                  "model": "Android",
                  "serial_number": "V2Vex3",
                  "locale": "en_US"
                }
            """.trimIndent()

            val conn = (URL("https://api.cloudflareclient.com/v0a2158/reg").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "okhttp/3.12.1")
            }

            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                if (code !in 200..299) error("Cloudflare WARP API returned HTTP $code")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = json.parseToJsonElement(body).jsonObject
                val config = root["config"]?.jsonObject ?: error("Missing WARP config object")

                val peers = config["peers"]?.jsonArray
                val peer = peers?.firstOrNull()?.jsonObject ?: error("Missing WARP peer")
                val peerPub = peer["public_key"]?.jsonPrimitive?.contentOrNull
                    ?: "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
                val v4 = config["interface"]?.jsonObject
                    ?.get("addresses")?.jsonObject
                    ?.get("v4")?.jsonPrimitive?.contentOrNull
                    ?: "172.16.0.2"
                val v6 = config["interface"]?.jsonObject
                    ?.get("addresses")?.jsonObject
                    ?.get("v6")?.jsonPrimitive?.contentOrNull

                val clientIdB64 = config["client_id"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val reservedStr = runCatching {
                    if (clientIdB64.isNotBlank()) {
                        val b = Base64.decode(clientIdB64)
                        if (b.size >= 3) "${b[0].toInt() and 0xFF},${b[1].toInt() and 0xFF},${b[2].toInt() and 0xFF}" else ""
                    } else ""
                }.getOrDefault("")

                val localAddrs = buildString {
                    append("$v4/32")
                    if (!v6.isNullOrBlank()) append(", $v6/128")
                }

                LogBuffer.i("WARP", "Generated Cloudflare WARP WireGuard node ($v4, reserved=$reservedStr)")
                ServerProfile(
                    name = "Cloudflare WARP · Clean IP",
                    protocol = ProxyProtocol.WIREGUARD,
                    address = "engage.cloudflareclient.com",
                    port = 2408,
                    uuidOrPassword = kp.privateKeyBase64,
                    wgPeerPublicKey = peerPub,
                    wgLocalAddress = localAddrs,
                    wgReserved = reservedStr,
                    wgMtu = 1280,
                    transport = TransportType.TCP,
                    security = SecurityType.NONE
                )
            } finally {
                conn.disconnect()
            }
        }
    }

    // RFC 7748 Montgomery ladder on Curve25519 (base point u = 9)
    private fun scalarMultBase(scalarLittleEndian: ByteArray): ByteArray {
        val kBytes = scalarLittleEndian.reversedArray()
        val k = BigInteger(1, kBytes)
        val u = BigInteger.valueOf(9)

        var x1 = u
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = u
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val kt = if (k.testBit(t)) 1 else 0
            swap = swap xor kt
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = kt

            val a = x2.add(z2).mod(P)
            val aa = a.multiply(a).mod(P)
            val b = x2.subtract(z2).mod(P)
            val bb = b.multiply(b).mod(P)
            val e = aa.subtract(bb).mod(P)
            val c = x3.add(z3).mod(P)
            val d = x3.subtract(z3).mod(P)
            val da = d.multiply(a).mod(P)
            val cb = c.multiply(b).mod(P)

            x3 = da.add(cb).mod(P).let { it.multiply(it).mod(P) }
            z3 = x1.multiply(da.subtract(cb).mod(P).let { it.multiply(it).mod(P) }).mod(P)
            x2 = aa.multiply(bb).mod(P)
            z2 = e.multiply(aa.add(A24.multiply(e).mod(P)).mod(P)).mod(P)
        }
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }
        val res = x2.multiply(z2.modPow(P.subtract(BigInteger.TWO), P)).mod(P)
        val bigEndian = res.toByteArray()
        val out = ByteArray(32)
        val start = if (bigEndian.size > 32) bigEndian.size - 32 else 0
        val len = minOf(32, bigEndian.size)
        for (i in 0 until len) {
            out[i] = bigEndian[bigEndian.size - 1 - i]
        }
        return out
    }
}
