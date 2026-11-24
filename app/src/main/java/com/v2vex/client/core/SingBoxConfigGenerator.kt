package com.v2vex.client.core

import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Generates valid Sing-box 1.10+ JSON configuration for Dual-Core mode:
 * supports Hysteria2, TUIC v5, VLESS (REALITY/Vision), VMess, Trojan, Shadowsocks, WireGuard.
 */
object SingBoxConfigGenerator {

    private val prettyJson = Json { prettyPrint = true }

    fun generate(profile: ServerProfile, settings: AppSettings): String {
        if (profile.protocol == ProxyProtocol.RAW_JSON && profile.rawJson.isNotBlank()) {
            return profile.rawJson
        }

        val listenAddr = if (settings.allowLan) "0.0.0.0" else "127.0.0.1"

        val root = buildJsonObject {
            putJsonObject("log") {
                put("level", when (settings.logLevel) {
                    "warning" -> "warn"
                    else -> settings.logLevel
                })
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildJsonObject {
                        put("tag", "remote")
                        put("address", settings.dns.remoteDns.ifBlank { "https://1.1.1.1/dns-query" })
                        put("detour", "proxy")
                    })
                    add(buildJsonObject {
                        put("tag", "direct-dns")
                        put("address", settings.dns.directDns.ifBlank { "1.1.1.1" })
                        put("detour", "direct")
                    })
                }
                put("final", "remote")
            }

            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "socks-in")
                    put("listen", listenAddr)
                    put("listen_port", settings.socksPort)
                    put("sniff", settings.sniffing.enabled)
                })
                add(buildJsonObject {
                    put("type", "http")
                    put("tag", "http-in")
                    put("listen", listenAddr)
                    put("listen_port", settings.httpPort)
                    put("sniff", settings.sniffing.enabled)
                })
            }

            putJsonArray("outbounds") {
                add(buildProxyOutbound(profile))
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                })
                add(buildJsonObject {
                    put("type", "block")
                    put("tag", "block")
                })
            }

            putJsonObject("route") {
                putJsonArray("rules") {
                    if (settings.routingPreset == RoutingPreset.BYPASS_LAN ||
                        settings.routingPreset == RoutingPreset.ADBLOCK_BYPASS_LAN
                    ) {
                        add(buildJsonObject {
                            put("ip_is_private", true)
                            put("outbound", "direct")
                        })
                    }
                }
                put("final", "proxy")
            }
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun buildProxyOutbound(profile: ServerProfile): JsonObject = buildJsonObject {
        put("tag", "proxy")
        when (profile.protocol) {
            ProxyProtocol.HYSTERIA2 -> {
                put("type", "hysteria2")
                put("server", profile.address)
                put("server_port", profile.port)
                put("password", profile.uuidOrPassword)
                if (profile.upMbps > 0) put("up_mbps", profile.upMbps)
                if (profile.downMbps > 0) put("down_mbps", profile.downMbps)
                if (profile.obfsType.isNotBlank() && profile.obfsPassword.isNotBlank()) {
                    putJsonObject("obfs") {
                        put("type", profile.obfsType)
                        put("password", profile.obfsPassword)
                    }
                }
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", profile.sni.ifBlank { profile.address })
                    put("insecure", profile.allowInsecure)
                }
            }

            ProxyProtocol.TUIC -> {
                put("type", "tuic")
                put("server", profile.address)
                put("server_port", profile.port)
                put("uuid", profile.username.ifBlank { profile.uuidOrPassword })
                put("password", profile.uuidOrPassword)
                put("congestion_control", profile.congestionControl.ifBlank { "bbr" })
                putJsonObject("tls") {
                    put("enabled", true)
                    put("server_name", profile.sni.ifBlank { profile.address })
                    put("insecure", profile.allowInsecure)
                    if (profile.alpn.isNotBlank()) {
                        putJsonArray("alpn") {
                            profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                        }
                    } else {
                        putJsonArray("alpn") { add("h3") }
                    }
                }
            }

            ProxyProtocol.VLESS -> {
                put("type", "vless")
                put("server", profile.address)
                put("server_port", profile.port)
                put("uuid", profile.uuidOrPassword)
                if (profile.flow.isNotBlank()) put("flow", profile.flow)
                addTlsAndTransport(this, profile)
            }

            ProxyProtocol.VMESS -> {
                put("type", "vmess")
                put("server", profile.address)
                put("server_port", profile.port)
                put("uuid", profile.uuidOrPassword)
                put("security", profile.encryption.ifBlank { "auto" })
                put("alter_id", profile.alterId)
                addTlsAndTransport(this, profile)
            }

            ProxyProtocol.TROJAN -> {
                put("type", "trojan")
                put("server", profile.address)
                put("server_port", profile.port)
                put("password", profile.uuidOrPassword)
                addTlsAndTransport(this, profile)
            }

            ProxyProtocol.SHADOWSOCKS -> {
                put("type", "shadowsocks")
                put("server", profile.address)
                put("server_port", profile.port)
                put("method", profile.encryption.ifBlank { "aes-256-gcm" })
                put("password", profile.uuidOrPassword)
            }

            ProxyProtocol.SOCKS -> {
                put("type", "socks")
                put("server", profile.address)
                put("server_port", profile.port)
                if (profile.username.isNotBlank()) {
                    put("username", profile.username)
                    put("password", profile.uuidOrPassword)
                }
            }

            ProxyProtocol.HTTP -> {
                put("type", "http")
                put("server", profile.address)
                put("server_port", profile.port)
                if (profile.username.isNotBlank()) {
                    put("username", profile.username)
                    put("password", profile.uuidOrPassword)
                }
            }

            ProxyProtocol.WIREGUARD -> {
                put("type", "wireguard")
                put("server", profile.address)
                put("server_port", profile.port)
                put("private_key", profile.uuidOrPassword)
                put("peer_public_key", profile.wgPeerPublicKey)
                putJsonArray("local_address") {
                    profile.wgLocalAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(it) }
                }
                put("mtu", profile.wgMtu)
            }

            ProxyProtocol.RAW_JSON -> {
                put("type", "direct")
            }
        }
    }

    private fun addTlsAndTransport(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        profile: ServerProfile
    ) {
        if (profile.security != SecurityType.NONE) {
            builder.putJsonObject("tls") {
                put("enabled", true)
                put("server_name", profile.sni.ifBlank { profile.address })
                put("insecure", profile.allowInsecure)
                if (profile.fingerprint.isNotBlank()) {
                    putJsonObject("utls") {
                        put("enabled", true)
                        put("fingerprint", profile.fingerprint)
                    }
                }
                if (profile.security == SecurityType.REALITY) {
                    putJsonObject("reality") {
                        put("enabled", true)
                        put("public_key", profile.realityPublicKey)
                        put("short_id", profile.realityShortId)
                    }
                }
            }
        }
        when (profile.transport) {
            TransportType.WS -> builder.putJsonObject("transport") {
                put("type", "ws")
                put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) {
                    putJsonObject("headers") { put("Host", profile.host) }
                }
            }
            TransportType.GRPC -> builder.putJsonObject("transport") {
                put("type", "grpc")
                put("service_name", profile.path)
            }
            TransportType.HTTPUPGRADE -> builder.putJsonObject("transport") {
                put("type", "httpupgrade")
                put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) put("host", profile.host)
            }
            TransportType.H2 -> builder.putJsonObject("transport") {
                put("type", "http")
                put("path", profile.path.ifBlank { "/" })
                if (profile.host.isNotBlank()) putJsonArray("host") { add(profile.host) }
            }
            else -> Unit
        }
    }
}
