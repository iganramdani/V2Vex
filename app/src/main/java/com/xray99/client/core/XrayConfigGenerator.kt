package com.xray99.client.core

import com.xray99.client.model.AppSettings
import com.xray99.client.model.ProxyProtocol
import com.xray99.client.model.RoutingPreset
import com.xray99.client.model.RuleMatchType
import com.xray99.client.model.SecurityType
import com.xray99.client.model.ServerProfile
import com.xray99.client.model.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object XrayConfigGenerator {

    private val prettyJson = Json { prettyPrint = true }

    fun generate(profile: ServerProfile, settings: AppSettings): String {
        if (profile.protocol == ProxyProtocol.RAW_JSON && profile.rawJson.isNotBlank()) {
            return profile.rawJson
        }

        val root = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", settings.logLevel)
            }

            // Built-in Stats & Policy for high-accuracy live traffic tracking
            putJsonObject("stats") {}
            putJsonObject("policy") {
                putJsonObject("system") {
                    put("statsInboundUplink", true)
                    put("statsInboundDownlink", true)
                    put("statsOutboundUplink", true)
                    put("statsOutboundDownlink", true)
                }
            }

            putJsonObject("api") {
                put("tag", "api")
                putJsonArray("services") {
                    add("StatsService")
                }
            }

            // DNS Engine
            put("dns", buildDns(settings))

            // Inbounds: Local SOCKS5 + HTTP + API
            put("inbounds", buildInbounds(settings))

            // Outbounds: Active Proxy + optional Fragment dialerProxy + Direct + Block
            put("outbounds", buildOutbounds(profile, settings))

            // Routing Engine
            put("routing", buildRouting(settings))
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    fun validateJson(raw: String): Result<Unit> = runCatching {
        prettyJson.parseToJsonElement(raw)
        Unit
    }

    private fun buildDns(settings: AppSettings): JsonObject = buildJsonObject {
        put("queryStrategy", settings.dns.queryStrategy)
        putJsonArray("servers") {
            if (settings.dns.enableFakeDns) {
                add("fakedns")
            }
            if (settings.dns.remoteDns.isNotBlank()) {
                add(settings.dns.remoteDns)
            }
            if (settings.dns.backupDns.isNotBlank()) {
                add(settings.dns.backupDns)
            }
            if (settings.dns.directDns.isNotBlank()) {
                add(buildJsonObject {
                    put("address", settings.dns.directDns)
                    putJsonArray("domains") {
                        add("geosite:private")
                    }
                })
            }
        }
    }

    private fun buildInbounds(settings: AppSettings): JsonArray = buildJsonArray {
        val listenAddr = if (settings.allowLan) "0.0.0.0" else "127.0.0.1"

        // SOCKS5 inbound
        add(buildJsonObject {
            put("tag", "socks-in")
            put("port", settings.socksPort)
            put("listen", listenAddr)
            put("protocol", "socks")
            putJsonObject("settings") {
                put("auth", "noauth")
                put("udp", true)
            }
            put("sniffing", buildSniffing(settings))
        })

        // HTTP inbound
        add(buildJsonObject {
            put("tag", "http-in")
            put("port", settings.httpPort)
            put("listen", listenAddr)
            put("protocol", "http")
            put("sniffing", buildSniffing(settings))
        })

        // Local Stats API inbound
        add(buildJsonObject {
            put("tag", "api-in")
            put("port", settings.apiPort)
            put("listen", "127.0.0.1")
            put("protocol", "dokodemo-door")
            putJsonObject("settings") {
                put("address", "127.0.0.1")
            }
        })
    }

    private fun buildSniffing(settings: AppSettings): JsonObject = buildJsonObject {
        put("enabled", settings.sniffing.enabled)
        putJsonArray("destOverride") {
            if (settings.sniffing.destOverrideHttp) add("http")
            if (settings.sniffing.destOverrideTls) add("tls")
            if (settings.sniffing.destOverrideQuic) add("quic")
            if (settings.sniffing.destOverrideFakeDns) add("fakedns")
        }
        put("routeOnly", settings.sniffing.routeOnly)
    }

    private fun buildOutbounds(profile: ServerProfile, settings: AppSettings): JsonArray = buildJsonArray {
        val useFragment = settings.fragment.enabled &&
            profile.protocol != ProxyProtocol.WIREGUARD

        // Primary proxy outbound
        add(buildPrimaryOutbound(profile, settings, useFragment))

        // Optional DPI-bypass Fragment dialerProxy outbound
        if (useFragment) {
            add(buildJsonObject {
                put("tag", "fragment")
                put("protocol", "freedom")
                putJsonObject("settings") {
                    putJsonObject("fragment") {
                        put("packets", settings.fragment.packets.ifBlank { "tlshello" })
                        put("length", settings.fragment.length.ifBlank { "10-20" })
                        put("interval", settings.fragment.interval.ifBlank { "10-20" })
                    }
                }
                putJsonObject("streamSettings") {
                    putJsonObject("sockopt") {
                        put("tcpNoDelay", true)
                    }
                }
            })
        }

        // Direct outbound
        add(buildJsonObject {
            put("tag", "direct")
            put("protocol", "freedom")
            putJsonObject("settings") {
                put("domainStrategy", "UseIP")
            }
        })

        // Block outbound
        add(buildJsonObject {
            put("tag", "block")
            put("protocol", "blackhole")
            putJsonObject("settings") {
                putJsonObject("response") {
                    put("type", "http")
                }
            }
        })
    }

    private fun buildPrimaryOutbound(
        profile: ServerProfile,
        settings: AppSettings,
        useFragment: Boolean
    ): JsonObject = buildJsonObject {
        put("tag", "proxy")
        when (profile.protocol) {
            ProxyProtocol.VLESS -> {
                put("protocol", "vless")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        add(buildJsonObject {
                            put("address", profile.address)
                            put("port", profile.port)
                            putJsonArray("users") {
                                add(buildJsonObject {
                                    put("id", profile.uuidOrPassword)
                                    put("encryption", profile.encryption.ifBlank { "none" })
                                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                                })
                            }
                        })
                    }
                }
            }

            ProxyProtocol.VMESS -> {
                put("protocol", "vmess")
                putJsonObject("settings") {
                    putJsonArray("vnext") {
                        add(buildJsonObject {
                            put("address", profile.address)
                            put("port", profile.port)
                            putJsonArray("users") {
                                add(buildJsonObject {
                                    put("id", profile.uuidOrPassword)
                                    put("alterId", profile.alterId)
                                    put("security", profile.encryption.ifBlank { "auto" })
                                })
                            }
                        })
                    }
                }
            }

            ProxyProtocol.TROJAN -> {
                put("protocol", "trojan")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            put("address", profile.address)
                            put("port", profile.port)
                            put("password", profile.uuidOrPassword)
                        })
                    }
                }
            }

            ProxyProtocol.SHADOWSOCKS -> {
                put("protocol", "shadowsocks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            put("address", profile.address)
                            put("port", profile.port)
                            put("method", profile.encryption.ifBlank { "aes-256-gcm" })
                            put("password", profile.uuidOrPassword)
                        })
                    }
                }
            }

            ProxyProtocol.SOCKS -> {
                put("protocol", "socks")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            put("address", profile.address)
                            put("port", profile.port)
                            if (profile.username.isNotBlank()) {
                                putJsonArray("users") {
                                    add(buildJsonObject {
                                        put("user", profile.username)
                                        put("pass", profile.uuidOrPassword)
                                    })
                                }
                            }
                        })
                    }
                }
            }

            ProxyProtocol.HTTP -> {
                put("protocol", "http")
                putJsonObject("settings") {
                    putJsonArray("servers") {
                        add(buildJsonObject {
                            put("address", profile.address)
                            put("port", profile.port)
                            if (profile.username.isNotBlank()) {
                                putJsonArray("users") {
                                    add(buildJsonObject {
                                        put("user", profile.username)
                                        put("pass", profile.uuidOrPassword)
                                    })
                                }
                            }
                        })
                    }
                }
            }

            ProxyProtocol.WIREGUARD -> {
                put("protocol", "wireguard")
                putJsonObject("settings") {
                    put("secretKey", profile.uuidOrPassword)
                    putJsonArray("address") {
                        profile.wgLocalAddress.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            .forEach { add(it) }
                    }
                    putJsonArray("peers") {
                        add(buildJsonObject {
                            put("publicKey", profile.wgPeerPublicKey)
                            if (profile.wgPreSharedKey.isNotBlank()) {
                                put("preSharedKey", profile.wgPreSharedKey)
                            }
                            put("endpoint", "${profile.address}:${profile.port}")
                        })
                    }
                    if (profile.wgReserved.isNotBlank()) {
                        val nums = profile.wgReserved.split(",").mapNotNull { it.trim().toIntOrNull() }
                        if (nums.size == 3) {
                            putJsonArray("reserved") { nums.forEach { add(it) } }
                        }
                    }
                    put("mtu", profile.wgMtu)
                }
            }

            ProxyProtocol.RAW_JSON -> {
                put("protocol", "freedom")
            }
        }

        if (profile.protocol != ProxyProtocol.WIREGUARD) {
            put("streamSettings", buildStreamSettings(profile, useFragment))
        }

        // Mux.Cool (disabled automatically when XTLS-Vision flow is active, per Xray spec)
        if (settings.mux.enabled && profile.flow.isBlank() && profile.protocol != ProxyProtocol.WIREGUARD) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", settings.mux.concurrency)
                put("xudpConcurrency", settings.mux.xudpConcurrency)
                put("xudpProxyUDP443", settings.mux.xudpProxyUDP443)
            }
        }
    }

    private fun buildStreamSettings(profile: ServerProfile, useFragment: Boolean): JsonObject = buildJsonObject {
        put("network", profile.transport.xrayName)
        put("security", profile.security.xrayName)

        when (profile.security) {
            SecurityType.TLS -> {
                putJsonObject("tlsSettings") {
                    if (profile.sni.isNotBlank()) put("serverName", profile.sni)
                    put("allowInsecure", profile.allowInsecure)
                    if (profile.fingerprint.isNotBlank()) put("fingerprint", profile.fingerprint)
                    if (profile.alpn.isNotBlank()) {
                        putJsonArray("alpn") {
                            profile.alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                                .forEach { add(it) }
                        }
                    }
                }
            }
            SecurityType.REALITY -> {
                putJsonObject("realitySettings") {
                    put("serverName", profile.sni.ifBlank { profile.address })
                    put("fingerprint", profile.fingerprint.ifBlank { "chrome" })
                    put("publicKey", profile.realityPublicKey)
                    put("shortId", profile.realityShortId)
                    if (profile.realitySpiderX.isNotBlank()) put("spiderX", profile.realitySpiderX)
                }
            }
            SecurityType.NONE -> Unit
        }

        when (profile.transport) {
            TransportType.WS -> {
                putJsonObject("wsSettings") {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) {
                        putJsonObject("headers") {
                            put("Host", profile.host)
                        }
                    }
                }
            }
            TransportType.GRPC -> {
                putJsonObject("grpcSettings") {
                    put("serviceName", profile.path)
                    put("multiMode", profile.grpcMode.equals("multi", ignoreCase = true))
                }
            }
            TransportType.HTTPUPGRADE -> {
                putJsonObject("httpupgradeSettings") {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) put("host", profile.host)
                }
            }
            TransportType.XHTTP -> {
                putJsonObject("xhttpSettings") {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) put("host", profile.host)
                    put("mode", profile.xhttpMode.ifBlank { "auto" })
                }
            }
            TransportType.H2 -> {
                putJsonObject("httpSettings") {
                    put("path", profile.path.ifBlank { "/" })
                    if (profile.host.isNotBlank()) {
                        putJsonArray("host") { add(profile.host) }
                    }
                }
            }
            TransportType.KCP -> {
                putJsonObject("kcpSettings") {
                    putJsonObject("header") {
                        put("type", profile.headerType.ifBlank { "none" })
                    }
                }
            }
            TransportType.TCP -> {
                if (profile.headerType.equals("http", ignoreCase = true)) {
                    putJsonObject("tcpSettings") {
                        putJsonObject("header") {
                            put("type", "http")
                            putJsonObject("request") {
                                putJsonArray("path") { add(profile.path.ifBlank { "/" }) }
                                if (profile.host.isNotBlank()) {
                                    putJsonObject("headers") {
                                        putJsonArray("Host") { add(profile.host) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        if (useFragment) {
            putJsonObject("sockopt") {
                put("dialerProxy", "fragment")
                put("tcpNoDelay", true)
            }
        }
    }

    private fun buildRouting(settings: AppSettings): JsonObject = buildJsonObject {
        put("domainStrategy", settings.domainStrategy)
        putJsonArray("rules") {
            // Route local API inbound to stats API
            add(buildJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add("api-in") }
                put("outboundTag", "api")
            })

            // User-defined custom rules (highest priority)
            settings.customRules.filter { it.enabled && it.values.isNotBlank() }.forEach { rule ->
                val items = rule.values.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                if (items.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "field")
                        when (rule.matchType) {
                            RuleMatchType.DOMAIN -> putJsonArray("domain") { items.forEach { add(it) } }
                            RuleMatchType.IP -> putJsonArray("ip") { items.forEach { add(it) } }
                            RuleMatchType.PORT -> put("port", items.joinToString(","))
                            RuleMatchType.PROTOCOL -> putJsonArray("protocol") { items.forEach { add(it) } }
                        }
                        put("outboundTag", rule.target.outboundTag)
                    })
                }
            }

            // Preset rules
            when (settings.routingPreset) {
                RoutingPreset.GLOBAL -> Unit
                RoutingPreset.BYPASS_LAN -> {
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { add("geoip:private") }
                        put("outboundTag", "direct")
                    })
                }
                RoutingPreset.ADBLOCK_BYPASS_LAN -> {
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("domain") { add("geosite:category-ads-all") }
                        put("outboundTag", "block")
                    })
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("ip") { add("geoip:private") }
                        put("outboundTag", "direct")
                    })
                }
                RoutingPreset.CUSTOM -> Unit
            }
        }
    }
}
