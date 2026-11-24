package com.v2vex.client.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class ProxyProtocol(val label: String) {
    VLESS("VLESS"),
    VMESS("VMess"),
    TROJAN("Trojan"),
    SHADOWSOCKS("SS"),
    HYSTERIA2("Hysteria2"),
    TUIC("TUIC"),
    SOCKS("SOCKS5"),
    HTTP("HTTP"),
    WIREGUARD("WireGuard"),
    RAW_JSON("Raw JSON")
}

@Serializable
enum class TransportType(val xrayName: String, val label: String) {
    TCP("tcp", "TCP"),
    WS("ws", "WebSocket"),
    GRPC("grpc", "gRPC"),
    HTTPUPGRADE("httpupgrade", "HTTPUpgrade"),
    XHTTP("xhttp", "XHTTP / SplitHTTP"),
    H2("h2", "HTTP/2"),
    KCP("kcp", "mKCP")
}

@Serializable
enum class SecurityType(val xrayName: String, val label: String) {
    NONE("none", "None"),
    TLS("tls", "TLS"),
    REALITY("reality", "REALITY")
}

@Serializable
data class ServerProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New Node",
    val protocol: ProxyProtocol = ProxyProtocol.VLESS,
    val address: String = "",
    val port: Int = 443,

    // Auth / Identity
    val uuidOrPassword: String = "",
    val encryption: String = "none",         // VLESS: none | VMess: auto/aes-128-gcm/chacha20-poly1305 | SS: cipher
    val flow: String = "",                   // e.g. xtls-rprx-vision
    val alterId: Int = 0,                    // VMess legacy
    val username: String = "",               // SOCKS/HTTP or TUIC uuid

    // Transport
    val transport: TransportType = TransportType.TCP,
    val host: String = "",                   // WS/HTTPUpgrade/XHTTP/H2 host
    val path: String = "/",                  // WS/HTTPUpgrade/XHTTP/H2 path or gRPC serviceName
    val grpcMode: String = "gun",            // gun / multi
    val xhttpMode: String = "auto",          // auto / packet-up / stream-up / stream-one
    val headerType: String = "none",         // TCP/KCP header type

    // Security (TLS / REALITY)
    val security: SecurityType = SecurityType.TLS,
    val sni: String = "",
    val fingerprint: String = "chrome",
    val alpn: String = "",
    val allowInsecure: Boolean = false,
    val verifyPeerCertByName: String = "",   // Safe SNI Spoof: verify real VPS cert CN while sending spoofed SNI
    val realityPublicKey: String = "",
    val realityShortId: String = "",
    val realitySpiderX: String = "",

    // Hysteria2 / TUIC (Sing-box Dual-Core)
    val obfsType: String = "",               // salamander
    val obfsPassword: String = "",
    val upMbps: Int = 50,
    val downMbps: Int = 200,
    val congestionControl: String = "bbr",   // TUIC: bbr / cubic / new_reno

    // WireGuard specifics
    val wgLocalAddress: String = "10.0.0.2/32",
    val wgPeerPublicKey: String = "",
    val wgPreSharedKey: String = "",
    val wgReserved: String = "",
    val wgMtu: Int = 1420,

    // Raw custom full Xray/Sing-box JSON override
    val rawJson: String = "",

    // Metadata & Telemetry
    val subscriptionId: String? = null,
    val latencyMs: Int? = null,
    val lastDownloadMbps: Double? = null,
    val lastUploadMbps: Double? = null,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun shortBadge(): String = buildString {
        append(protocol.label)
        if (protocol == ProxyProtocol.HYSTERIA2 || protocol == ProxyProtocol.TUIC) {
            append(" · QUIC · Sing-box")
        } else if (protocol != ProxyProtocol.RAW_JSON && protocol != ProxyProtocol.WIREGUARD) {
            if (security != SecurityType.NONE) append(" · ").append(security.label)
            append(" · ").append(transport.label)
        }
    }
}

@Serializable
data class Subscription(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Subscription",
    val url: String = "",
    val autoUpdateEnabled: Boolean = true,
    val updateIntervalHours: Int = 12,
    val userAgent: String = "V2Vex/1.0 (Android)",
    val lastUpdatedAt: Long = 0L,
    val uploadBytes: Long? = null,
    val downloadBytes: Long? = null,
    val totalBytes: Long? = null,
    val expireTimestamp: Long? = null
)
