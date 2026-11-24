package com.xray99.client.core

import com.xray99.client.model.ProxyProtocol
import com.xray99.client.model.SecurityType
import com.xray99.client.model.ServerProfile
import com.xray99.client.model.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
object UriParser {

    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Parses a single URI, raw Xray JSON, or multi-line / Base64-encoded subscription payload.
     */
    fun parseBatch(rawInput: String, subscriptionId: String? = null): List<ServerProfile> {
        val trimmed = rawInput.trim()
        if (trimmed.isEmpty()) return emptyList()

        // If input looks like a full Xray JSON object
        if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
            return listOf(
                ServerProfile(
                    name = "Custom Xray JSON",
                    protocol = ProxyProtocol.RAW_JSON,
                    rawJson = trimmed,
                    subscriptionId = subscriptionId
                )
            )
        }

        // Check if entire payload is Base64-encoded URI list (standard subscription format)
        val decodedPayload = if (!trimmed.contains("://")) {
            decodeBase64Safe(trimmed) ?: trimmed
        } else {
            trimmed
        }

        return decodedPayload
            .lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line -> parseSingle(line)?.copy(subscriptionId = subscriptionId) }
    }

    fun parseSingle(uriStr: String): ServerProfile? {
        val clean = uriStr.trim()
        return runCatching {
            when {
                clean.startsWith("vless://", ignoreCase = true) -> parseVless(clean)
                clean.startsWith("vmess://", ignoreCase = true) -> parseVmess(clean)
                clean.startsWith("trojan://", ignoreCase = true) -> parseTrojan(clean)
                clean.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(clean)
                clean.startsWith("socks://", ignoreCase = true) ||
                    clean.startsWith("socks5://", ignoreCase = true) -> parseSocks(clean)
                clean.startsWith("wireguard://", ignoreCase = true) ||
                    clean.startsWith("wg://", ignoreCase = true) -> parseWireGuard(clean)
                else -> null
            }
        }.getOrNull()
    }

    private fun parseVless(raw: String): ServerProfile {
        val uri = URI(raw)
        val query = parseQuery(uri.rawQuery)
        val remark = decodeUrl(uri.rawFragment).ifBlank { "${uri.host}:${uri.port}" }
        val transport = mapTransport(query["type"])
        val security = mapSecurity(query["security"])
        val defaultPath = when (transport) {
            TransportType.GRPC -> query["serviceName"].orEmpty()
            else -> query["path"].orEmpty().ifBlank { "/" }
        }
        return ServerProfile(
            name = remark,
            protocol = ProxyProtocol.VLESS,
            address = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 443,
            uuidOrPassword = decodeUrl(uri.rawUserInfo),
            encryption = query["encryption"].orEmpty().ifBlank { "none" },
            flow = query["flow"].orEmpty(),
            transport = transport,
            host = query["host"].orEmpty(),
            path = defaultPath,
            grpcMode = query["mode"].orEmpty().ifBlank { "gun" },
            xhttpMode = query["mode"].orEmpty().ifBlank { "auto" },
            headerType = query["headerType"].orEmpty().ifBlank { "none" },
            security = security,
            sni = query["sni"].orEmpty().ifBlank { query["host"].orEmpty() },
            fingerprint = query["fp"].orEmpty().ifBlank { "chrome" },
            alpn = query["alpn"].orEmpty(),
            allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1",
            realityPublicKey = query["pbk"].orEmpty(),
            realityShortId = query["sid"].orEmpty(),
            realitySpiderX = query["spx"].orEmpty()
        )
    }

    private fun parseVmess(raw: String): ServerProfile {
        val payload = raw.substringAfter("://").trim()
        val decoded = decodeBase64Safe(payload)
            ?: error("Invalid VMess Base64 payload")
        val obj: JsonObject = lenientJson.parseToJsonElement(decoded).jsonObject
        val address = obj["add"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val port = obj["port"]?.jsonPrimitive?.intOrNull
            ?: obj["port"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            ?: 443
        val remark = obj["ps"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "$address:$port" }
        val net = obj["net"]?.jsonPrimitive?.contentOrNull
        val tls = obj["tls"]?.jsonPrimitive?.contentOrNull
        return ServerProfile(
            name = remark,
            protocol = ProxyProtocol.VMESS,
            address = address,
            port = port,
            uuidOrPassword = obj["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            alterId = obj["aid"]?.jsonPrimitive?.intOrNull
                ?: obj["aid"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 0,
            encryption = obj["scy"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "auto" },
            transport = mapTransport(net),
            host = obj["host"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            path = obj["path"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "/" },
            headerType = obj["type"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "none" },
            security = if (tls.equals("tls", ignoreCase = true)) SecurityType.TLS else SecurityType.NONE,
            sni = obj["sni"]?.jsonPrimitive?.contentOrNull.orEmpty()
                .ifBlank { obj["host"]?.jsonPrimitive?.contentOrNull.orEmpty() },
            fingerprint = obj["fp"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "chrome" },
            alpn = obj["alpn"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            allowInsecure = obj["allowInsecure"]?.jsonPrimitive?.contentOrNull == "1"
        )
    }

    private fun parseTrojan(raw: String): ServerProfile {
        val uri = URI(raw)
        val query = parseQuery(uri.rawQuery)
        val remark = decodeUrl(uri.rawFragment).ifBlank { "${uri.host}:${uri.port}" }
        val transport = mapTransport(query["type"])
        val security = mapSecurity(query["security"] ?: "tls")
        return ServerProfile(
            name = remark,
            protocol = ProxyProtocol.TROJAN,
            address = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 443,
            uuidOrPassword = decodeUrl(uri.rawUserInfo),
            transport = transport,
            host = query["host"].orEmpty(),
            path = if (transport == TransportType.GRPC) {
                query["serviceName"].orEmpty()
            } else {
                query["path"].orEmpty().ifBlank { "/" }
            },
            grpcMode = query["mode"].orEmpty().ifBlank { "gun" },
            security = security,
            sni = query["sni"].orEmpty().ifBlank { query["peer"].orEmpty() },
            fingerprint = query["fp"].orEmpty().ifBlank { "chrome" },
            alpn = query["alpn"].orEmpty(),
            allowInsecure = query["allowInsecure"] == "1" || query["insecure"] == "1",
            realityPublicKey = query["pbk"].orEmpty(),
            realityShortId = query["sid"].orEmpty(),
            realitySpiderX = query["spx"].orEmpty()
        )
    }

    private fun parseShadowsocks(raw: String): ServerProfile {
        val withoutScheme = raw.substringAfter("://")
        val remark = decodeUrl(withoutScheme.substringAfter("#", ""))
        val mainPart = withoutScheme.substringBefore("#").substringBefore("?")

        val (method, password, host, port) = if (mainPart.contains("@")) {
            // SIP002: base64(method:password)@host:port or method:password@host:port
            val userInfoRaw = mainPart.substringBefore("@")
            val hostPort = mainPart.substringAfter("@")
            val decodedUser = if (userInfoRaw.contains(":")) {
                decodeUrl(userInfoRaw)
            } else {
                decodeBase64Safe(userInfoRaw) ?: userInfoRaw
            }
            val m = decodedUser.substringBefore(":")
            val p = decodedUser.substringAfter(":", "")
            val (h, pt) = splitHostPort(hostPort, 8388)
            Quad(m, p, h, pt)
        } else {
            // Legacy: base64(method:password@host:port)
            val decoded = decodeBase64Safe(mainPart) ?: error("Invalid legacy SS URI")
            val userInfo = decoded.substringBeforeLast("@")
            val hostPort = decoded.substringAfterLast("@")
            val m = userInfo.substringBefore(":")
            val p = userInfo.substringAfter(":", "")
            val (h, pt) = splitHostPort(hostPort, 8388)
            Quad(m, p, h, pt)
        }

        return ServerProfile(
            name = remark.ifBlank { "$host:$port" },
            protocol = ProxyProtocol.SHADOWSOCKS,
            address = host,
            port = port,
            encryption = method.ifBlank { "aes-256-gcm" },
            uuidOrPassword = password,
            transport = TransportType.TCP,
            security = SecurityType.NONE
        )
    }

    private fun parseSocks(raw: String): ServerProfile {
        val normalized = raw.replaceFirst("socks5://", "socks://", ignoreCase = true)
        val uri = URI(normalized)
        val remark = decodeUrl(uri.rawFragment).ifBlank { "${uri.host}:${uri.port}" }
        val userInfoRaw = uri.rawUserInfo.orEmpty()
        val decodedUser = if (userInfoRaw.isNotEmpty() && !userInfoRaw.contains(":")) {
            decodeBase64Safe(userInfoRaw) ?: userInfoRaw
        } else {
            decodeUrl(userInfoRaw)
        }
        val user = decodedUser.substringBefore(":", "")
        val pass = if (decodedUser.contains(":")) decodedUser.substringAfter(":") else ""
        return ServerProfile(
            name = remark,
            protocol = ProxyProtocol.SOCKS,
            address = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 1080,
            username = user,
            uuidOrPassword = pass,
            transport = TransportType.TCP,
            security = SecurityType.NONE
        )
    }

    private fun parseWireGuard(raw: String): ServerProfile {
        val normalized = raw.replaceFirst("wg://", "wireguard://", ignoreCase = true)
        val uri = URI(normalized)
        val query = parseQuery(uri.rawQuery)
        val remark = decodeUrl(uri.rawFragment).ifBlank { "WG ${uri.host}:${uri.port}" }
        return ServerProfile(
            name = remark,
            protocol = ProxyProtocol.WIREGUARD,
            address = uri.host ?: "",
            port = if (uri.port > 0) uri.port else 51820,
            uuidOrPassword = decodeUrl(uri.rawUserInfo), // privateKey
            wgPeerPublicKey = query["publickey"].orEmpty().ifBlank { query["publicKey"].orEmpty() },
            wgLocalAddress = query["address"].orEmpty().ifBlank { "10.0.0.2/32" },
            wgPreSharedKey = query["presharedkey"].orEmpty(),
            wgReserved = query["reserved"].orEmpty(),
            wgMtu = query["mtu"]?.toIntOrNull() ?: 1420,
            transport = TransportType.TCP,
            security = SecurityType.NONE
        )
    }

    fun exportUri(profile: ServerProfile): String {
        return when (profile.protocol) {
            ProxyProtocol.VLESS -> {
                val params = linkedMapOf<String, String>()
                params["encryption"] = profile.encryption.ifBlank { "none" }
                params["security"] = profile.security.xrayName
                params["type"] = profile.transport.xrayName
                if (profile.flow.isNotBlank()) params["flow"] = profile.flow
                if (profile.sni.isNotBlank()) params["sni"] = profile.sni
                if (profile.fingerprint.isNotBlank() && profile.security != SecurityType.NONE) {
                    params["fp"] = profile.fingerprint
                }
                if (profile.alpn.isNotBlank()) params["alpn"] = profile.alpn
                if (profile.allowInsecure) params["allowInsecure"] = "1"
                if (profile.security == SecurityType.REALITY) {
                    if (profile.realityPublicKey.isNotBlank()) params["pbk"] = profile.realityPublicKey
                    if (profile.realityShortId.isNotBlank()) params["sid"] = profile.realityShortId
                    if (profile.realitySpiderX.isNotBlank()) params["spx"] = profile.realitySpiderX
                }
                if (profile.host.isNotBlank()) params["host"] = profile.host
                when (profile.transport) {
                    TransportType.GRPC -> {
                        if (profile.path.isNotBlank()) params["serviceName"] = profile.path
                        if (profile.grpcMode.isNotBlank()) params["mode"] = profile.grpcMode
                    }
                    TransportType.XHTTP -> {
                        if (profile.path.isNotBlank()) params["path"] = profile.path
                        if (profile.xhttpMode.isNotBlank()) params["mode"] = profile.xhttpMode
                    }
                    else -> {
                        if (profile.path.isNotBlank() && profile.path != "/") params["path"] = profile.path
                    }
                }
                val qs = params.entries.joinToString("&") { (k, v) -> "$k=${encodeUrl(v)}" }
                "vless://${profile.uuidOrPassword}@${formatHost(profile.address)}:${profile.port}?$qs#${encodeUrl(profile.name)}"
            }

            ProxyProtocol.VMESS -> {
                val json = buildString {
                    append("{")
                    append("\"v\":\"2\",")
                    append("\"ps\":${jsonQuote(profile.name)},")
                    append("\"add\":${jsonQuote(profile.address)},")
                    append("\"port\":\"${profile.port}\",")
                    append("\"id\":${jsonQuote(profile.uuidOrPassword)},")
                    append("\"aid\":\"${profile.alterId}\",")
                    append("\"scy\":${jsonQuote(profile.encryption.ifBlank { "auto" })},")
                    append("\"net\":${jsonQuote(profile.transport.xrayName)},")
                    append("\"type\":${jsonQuote(profile.headerType.ifBlank { "none" })},")
                    append("\"host\":${jsonQuote(profile.host)},")
                    append("\"path\":${jsonQuote(profile.path)},")
                    append("\"tls\":${jsonQuote(if (profile.security == SecurityType.TLS) "tls" else "")},")
                    append("\"sni\":${jsonQuote(profile.sni)},")
                    append("\"alpn\":${jsonQuote(profile.alpn)},")
                    append("\"fp\":${jsonQuote(profile.fingerprint)}")
                    append("}")
                }
                "vmess://${Base64.encode(json.toByteArray(Charsets.UTF_8))}"
            }

            ProxyProtocol.TROJAN -> {
                val params = linkedMapOf<String, String>()
                params["security"] = profile.security.xrayName
                params["type"] = profile.transport.xrayName
                if (profile.sni.isNotBlank()) params["sni"] = profile.sni
                if (profile.fingerprint.isNotBlank()) params["fp"] = profile.fingerprint
                if (profile.host.isNotBlank()) params["host"] = profile.host
                if (profile.path.isNotBlank()) {
                    if (profile.transport == TransportType.GRPC) params["serviceName"] = profile.path
                    else params["path"] = profile.path
                }
                val qs = params.entries.joinToString("&") { (k, v) -> "$k=${encodeUrl(v)}" }
                "trojan://${encodeUrl(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}?$qs#${encodeUrl(profile.name)}"
            }

            ProxyProtocol.SHADOWSOCKS -> {
                val userInfo = Base64.UrlSafe.encode(
                    "${profile.encryption}:${profile.uuidOrPassword}".toByteArray(Charsets.UTF_8)
                ).trimEnd('=')
                "ss://$userInfo@${formatHost(profile.address)}:${profile.port}#${encodeUrl(profile.name)}"
            }

            ProxyProtocol.SOCKS -> {
                val auth = if (profile.username.isNotBlank()) {
                    "${encodeUrl(profile.username)}:${encodeUrl(profile.uuidOrPassword)}@"
                } else ""
                "socks://$auth${formatHost(profile.address)}:${profile.port}#${encodeUrl(profile.name)}"
            }

            ProxyProtocol.HTTP -> {
                val auth = if (profile.username.isNotBlank()) {
                    "${encodeUrl(profile.username)}:${encodeUrl(profile.uuidOrPassword)}@"
                } else ""
                "http://$auth${formatHost(profile.address)}:${profile.port}#${encodeUrl(profile.name)}"
            }

            ProxyProtocol.WIREGUARD -> {
                val params = linkedMapOf(
                    "publickey" to profile.wgPeerPublicKey,
                    "address" to profile.wgLocalAddress,
                    "mtu" to profile.wgMtu.toString()
                )
                if (profile.wgReserved.isNotBlank()) params["reserved"] = profile.wgReserved
                val qs = params.entries.joinToString("&") { (k, v) -> "$k=${encodeUrl(v)}" }
                "wireguard://${encodeUrl(profile.uuidOrPassword)}@${formatHost(profile.address)}:${profile.port}?$qs#${encodeUrl(profile.name)}"
            }

            ProxyProtocol.RAW_JSON -> profile.rawJson
        }
    }

    private fun mapTransport(raw: String?): TransportType = when (raw?.lowercase()) {
        "ws", "websocket" -> TransportType.WS
        "grpc" -> TransportType.GRPC
        "httpupgrade" -> TransportType.HTTPUPGRADE
        "xhttp", "splithttp" -> TransportType.XHTTP
        "h2", "http" -> TransportType.H2
        "kcp", "mkcp" -> TransportType.KCP
        else -> TransportType.TCP
    }

    private fun mapSecurity(raw: String?): SecurityType = when (raw?.lowercase()) {
        "tls" -> SecurityType.TLS
        "reality" -> SecurityType.REALITY
        else -> SecurityType.NONE
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split("&")
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) null
                else decodeUrl(part.substring(0, idx)) to decodeUrl(part.substring(idx + 1))
            }
            .toMap()
    }

    private fun splitHostPort(hostPort: String, defaultPort: Int): Pair<String, Int> {
        return if (hostPort.startsWith("[")) {
            val endBracket = hostPort.indexOf(']')
            val host = hostPort.substring(1, endBracket)
            val port = hostPort.substringAfter("]:", "").toIntOrNull() ?: defaultPort
            host to port
        } else {
            val host = hostPort.substringBeforeLast(":")
            val port = hostPort.substringAfterLast(":", "").toIntOrNull() ?: defaultPort
            host to port
        }
    }

    private fun formatHost(host: String): String =
        if (host.contains(":") && !host.startsWith("[")) "[$host]" else host

    private fun decodeUrl(s: String?): String =
        if (s.isNullOrEmpty()) "" else runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    private fun encodeUrl(s: String): String =
        URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun jsonQuote(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""

    fun decodeBase64Safe(input: String): String? {
        val cleaned = input.trim()
            .replace("\n", "")
            .replace("\r", "")
            .replace(" ", "")
        val padded = when (cleaned.length % 4) {
            2 -> "$cleaned=="
            3 -> "$cleaned="
            else -> cleaned
        }
        return runCatching {
            val bytes = if (padded.contains('-') || padded.contains('_')) {
                Base64.UrlSafe.decode(padded)
            } else {
                Base64.decode(padded)
            }
            bytes.toString(Charsets.UTF_8)
        }.getOrNull()
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
