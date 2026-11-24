package com.v2vex.client.core

import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.RuleMatchType
import com.v2vex.client.model.RuleTarget
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
import java.net.URI

/**
 * Generates modern Sing-box 1.12–1.14+ compliant JSON configuration for Dual-Core mode:
 * - Uses modern route actions ("sniff", "hijack-dns", "reject") instead of removed legacy fields
 * - Uses modern typed DNS servers ("https", "udp") compatible with Sing-box 1.12+ / 1.14+
 * - Supports Hysteria2, TUIC v5, VLESS (REALITY/Vision), VMess, Trojan, Shadowsocks, WireGuard
 * - Supports QUIC Guard (UDP:443 reject), Carrier NAT64 IPv4 strategy, and Custom Routing Rules
 */
object SingBoxConfigGenerator {

    private val prettyJson = Json { prettyPrint = true }

    fun generate(profile: ServerProfile, settings: AppSettings): String {
        if (profile.protocol == ProxyProtocol.RAW_JSON && profile.rawJson.isNotBlank()) {
            return profile.rawJson
        }

        val listenAddr = if (settings.allowLan) "0.0.0.0" else "127.0.0.1"
        val sbStrategy = when (settings.outboundDomainStrategy) {
            "UseIPv4" -> "ipv4_only"
            "UseIPv6" -> "ipv6_only"
            else -> null
        }

        val root = buildJsonObject {
            putJsonObject("log") {
                put(
                    "level",
                    when (settings.logLevel) {
                        "warning" -> "warn"
                        "none" -> "fatal"
                        else -> settings.logLevel
                    }
                )
                put("timestamp", true)
            }

            putJsonObject("dns") {
                putJsonArray("servers") {
                    add(buildDnsServer("remote-dns", settings.dns.remoteDns.ifBlank { "https://1.1.1.1/dns-query" }, "proxy"))
                    add(buildDnsServer("direct-dns", settings.dns.directDns.ifBlank { "1.1.1.1" }, null))
                }
                if (sbStrategy != null) {
                    put("strategy", sbStrategy)
                }
                put("final", "remote-dns")
            }

            putJsonArray("inbounds") {
                add(buildJsonObject {
                    put("type", "socks")
                    put("tag", "socks-in")
                    put("listen", listenAddr)
                    put("listen_port", settings.socksPort)
                })
                add(buildJsonObject {
                    put("type", "http")
                    put("tag", "http-in")
                    put("listen", listenAddr)
                    put("listen_port", settings.httpPort)
                })
            }

            putJsonArray("outbounds") {
                add(buildProxyOutbound(profile, sbStrategy))
                add(buildJsonObject {
                    put("type", "direct")
                    put("tag", "direct")
                    if (sbStrategy != null) put("domain_strategy", sbStrategy)
                })
            }

            putJsonObject("route") {
                put("default_domain_resolver", "direct-dns")
                putJsonArray("rules") {
                    // Modern Sing-box 1.11+ sniffing & port-53 DNS hijack actions
                    if (settings.sniffing.enabled) {
                        add(buildJsonObject {
                            put("action", "sniff")
                        })
                    }
                    add(buildJsonObject {
                        put("protocol", "dns")
                        put("action", "hijack-dns")
                    })

                    // QUIC (UDP:443) Guard
                    if (settings.blockQuic) {
                        add(buildJsonObject {
                            put("network", "udp")
                            put("port", 443)
                            put("action", "reject")
                        })
                    }

                    // User-defined custom rules (domains, IPs, ports — skip Xray-only geosite:/geoip: tokens cleanly)
                    settings.customRules.filter { it.enabled && it.values.isNotBlank() }.forEach { rule ->
                        val items = rule.values.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        when (rule.matchType) {
                            RuleMatchType.DOMAIN -> {
                                val exact = mutableListOf<String>()
                                val suffix = mutableListOf<String>()
                                val keyword = mutableListOf<String>()
                                items.forEach { raw ->
                                    when {
                                        raw.startsWith("geosite:", ignoreCase = true) -> Unit // Xray .dat only
                                        raw.startsWith("full:", ignoreCase = true) -> exact += raw.removePrefix("full:").trim()
                                        raw.startsWith("domain:", ignoreCase = true) -> suffix += raw.removePrefix("domain:").trim()
                                        raw.startsWith("keyword:", ignoreCase = true) -> keyword += raw.removePrefix("keyword:").trim()
                                        !raw.contains(":") -> suffix += raw
                                    }
                                }
                                if (exact.isNotEmpty() || suffix.isNotEmpty() || keyword.isNotEmpty()) {
                                    add(buildJsonObject {
                                        if (exact.isNotEmpty()) putJsonArray("domain") { exact.forEach { add(it) } }
                                        if (suffix.isNotEmpty()) putJsonArray("domain_suffix") { suffix.forEach { add(it) } }
                                        if (keyword.isNotEmpty()) putJsonArray("domain_keyword") { keyword.forEach { add(it) } }
                                        applyRuleTarget(this, rule.target)
                                    })
                                }
                            }
                            RuleMatchType.IP -> {
                                val cidrs = items.filter { !it.startsWith("geoip:", ignoreCase = true) }
                                    .map { if (it.contains("/")) it else if (it.contains(":")) "$it/128" else "$it/32" }
                                if (cidrs.isNotEmpty()) {
                                    add(buildJsonObject {
                                        putJsonArray("ip_cidr") { cidrs.forEach { add(it) } }
                                        applyRuleTarget(this, rule.target)
                                    })
                                }
                            }
                            RuleMatchType.PORT -> {
                                val ports = items.mapNotNull { it.toIntOrNull() }.filter { it in 1..65535 }
                                if (ports.isNotEmpty()) {
                                    add(buildJsonObject {
                                        putJsonArray("port") { ports.forEach { add(it) } }
                                        applyRuleTarget(this, rule.target)
                                    })
                                }
                            }
                            RuleMatchType.PROTOCOL -> {
                                if (items.isNotEmpty()) {
                                    add(buildJsonObject {
                                        putJsonArray("protocol") { items.forEach { add(it) } }
                                        applyRuleTarget(this, rule.target)
                                    })
                                }
                            }
                        }
                    }

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

    private fun applyRuleTarget(
        builder: kotlinx.serialization.json.JsonObjectBuilder,
        target: RuleTarget
    ) {
        when (target) {
            RuleTarget.BLOCK -> builder.put("action", "reject")
            RuleTarget.DIRECT -> builder.put("outbound", "direct")
            RuleTarget.PROXY -> builder.put("outbound", "proxy")
        }
    }

    private fun buildDnsServer(tag: String, rawAddr: String, detour: String?): JsonObject = buildJsonObject {
        put("tag", tag)
        val trimmed = rawAddr.trim()
        when {
            trimmed.startsWith("https://", ignoreCase = true) -> {
                val uri = runCatching { URI(trimmed) }.getOrNull()
                put("type", "https")
                put("server", uri?.host ?: "1.1.1.1")
                if ((uri?.port ?: -1) > 0) put("server_port", uri!!.port)
                put("path", uri?.path?.takeIf { it.isNotBlank() } ?: "/dns-query")
            }
            trimmed.startsWith("tls://", ignoreCase = true) -> {
                val uri = runCatching { URI(trimmed) }.getOrNull()
                put("type", "tls")
                put("server", uri?.host ?: "1.1.1.1")
                if ((uri?.port ?: -1) > 0) put("server_port", uri!!.port)
            }
            else -> {
                val host = trimmed.removePrefix("udp://").substringBefore(":")
                put("type", "udp")
                put("server", host.ifBlank { "1.1.1.1" })
            }
        }
        if (!detour.isNullOrBlank()) {
            put("detour", detour)
        }
    }

    private fun buildProxyOutbound(profile: ServerProfile, domainStrategy: String?): JsonObject = buildJsonObject {
        put("tag", "proxy")
        if (domainStrategy != null) put("domain_strategy", domainStrategy)
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
                // If user uses Safe SNI Spoofing (verifyPeerCertByName) where SNI != cert CN, Sing-box needs insecure=true
                val needsInsecureForSpoof = profile.allowInsecure ||
                    (profile.verifyPeerCertByName.isNotBlank() &&
                        !profile.verifyPeerCertByName.equals(profile.sni, ignoreCase = true))
                put("insecure", needsInsecureForSpoof)
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
