package com.v2vex.client.core

import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.RuleMatchType
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object XrayConfigGenerator {

    private val prettyJson = Json { prettyPrint = true }

    private val FALLBACK_PRIVATE_CIDRS = listOf(
        "10.0.0.0/8",
        "127.0.0.0/8",
        "172.16.0.0/12",
        "192.168.0.0/16",
        "169.254.0.0/16",
        "::1/128",
        "fc00::/7"
    )

    fun generate(
        profile: ServerProfile,
        settings: AppSettings,
        hasGeoIp: Boolean = true,
        hasGeoSite: Boolean = true,
        chainProfile: ServerProfile? = null,
        failoverProfiles: List<ServerProfile> = emptyList()
    ): String {
        if (profile.protocol == ProxyProtocol.RAW_JSON && profile.rawJson.isNotBlank()) {
            return profile.rawJson
        }

        val activeFailovers = if (settings.enableAutoFailover) {
            failoverProfiles.filter {
                it.id != profile.id &&
                    it.protocol != ProxyProtocol.RAW_JSON &&
                    it.protocol != ProxyProtocol.HYSTERIA2 &&
                    it.protocol != ProxyProtocol.TUIC
            }.take(4)
        } else emptyList()

        val root = buildJsonObject {
            putJsonObject("log") {
                put("loglevel", settings.logLevel)
            }

            // Built-in Stats & Policy
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

            // Xray Observatory Auto-Failover when multiple failover nodes are configured
            if (activeFailovers.isNotEmpty()) {
                putJsonObject("observatory") {
                    putJsonArray("subjectSelector") {
                        add("proxy")
                    }
                    put("probeUrl", settings.speedTest.pingUrl.ifBlank { "https://www.gstatic.com/generate_204" })
                    put("probeInterval", "30s")
                    put("enableConcurrency", true)
                }
            }

            // Proper root FakeDNS pool when enabled
            if (settings.dns.enableFakeDns) {
                putJsonArray("fakedns") {
                    add(buildJsonObject {
                        put("ipPool", "198.18.0.0/15")
                        put("poolSize", 65535)
                    })
                }
            }

            // DNS Engine
            put("dns", buildDns(settings, hasGeoSite))

            // Inbounds: Local SOCKS5 + HTTP + API
            put("inbounds", buildInbounds(settings))

            // Outbounds: Primary Proxy + Failover nodes + optional Chain Hop + Fragment + Direct + Block
            put("outbounds", buildOutbounds(profile, settings, chainProfile, activeFailovers))

            // Routing Engine (with optional leastPing balancer + safe GeoData fallbacks)
            put("routing", buildRouting(settings, hasGeoIp, hasGeoSite, activeFailovers.isNotEmpty()))
        }

        return prettyJson.encodeToString(JsonObject.serializer(), root)
    }

    fun validateJson(raw: String): Result<Unit> = runCatching {
        prettyJson.parseToJsonElement(raw)
        Unit
    }

    private fun buildDns(settings: AppSettings, hasGeoSite: Boolean): JsonObject = buildJsonObject {
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
            if (settings.dns.directDns.isNotBlank() && hasGeoSite) {
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

        add(buildJsonObject {
            put("tag", "http-in")
            put("port", settings.httpPort)
            put("listen", listenAddr)
            put("protocol", "http")
            put("sniffing", buildSniffing(settings))
        })

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
        put("enabled", settings.sniffing.enabled || settings.dns.enableFakeDns)
        putJsonArray("destOverride") {
            if (settings.sniffing.destOverrideHttp) add("http")
            if (settings.sniffing.destOverrideTls) add("tls")
            if (settings.sniffing.destOverrideQuic) add("quic")
            if (settings.sniffing.destOverrideFakeDns || settings.dns.enableFakeDns) add("fakedns")
        }
        put("routeOnly", settings.sniffing.routeOnly)
    }

    private fun buildOutbounds(
        profile: ServerProfile,
        settings: AppSettings,
        chainProfile: ServerProfile?,
        failoverProfiles: List<ServerProfile>
    ): JsonArray = buildJsonArray {
        val useFragment = settings.fragment.enabled &&
            profile.protocol != ProxyProtocol.WIREGUARD &&
            chainProfile == null

        val useChain = chainProfile != null &&
            chainProfile.id != profile.id &&
            chainProfile.protocol != ProxyProtocol.RAW_JSON

        // Primary proxy outbound
        add(
            buildOutbound(
                tag = "proxy",
                profile = profile,
                settings = settings,
                dialerProxyTag = when {
                    useChain -> "proxy-hop"
                    useFragment -> "fragment"
                    else -> null
                }
            )
        )

        // Observatory failover backup outbounds (proxy-1, proxy-2, ...)
        failoverProfiles.forEachIndexed { idx, backup ->
            add(
                buildOutbound(
                    tag = "proxy-${idx + 1}",
                    profile = backup,
                    settings = settings,
                    dialerProxyTag = if (useFragment) "fragment" else null
                )
            )
        }

        // Optional Multi-Hop Chain outbound
        if (useChain && chainProfile != null) {
            val hopFragment = settings.fragment.enabled && chainProfile.protocol != ProxyProtocol.WIREGUARD
            add(
                buildOutbound(
                    tag = "proxy-hop",
                    profile = chainProfile,
                    settings = settings,
                    dialerProxyTag = if (hopFragment) "fragment" else null
                )
            )
            if (hopFragment) {
                add(buildFragmentOutbound(settings))
            }
        } else if (useFragment) {
            add(buildFragmentOutbound(settings))
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

    private fun buildFragmentOutbound(settings: AppSettings): JsonObject = buildJsonObject {
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
    }

    private fun buildOutbound(
        tag: String,
        profile: ServerProfile,
        settings: AppSettings,
        dialerProxyTag: String?
    ): JsonObject = buildJsonObject {
        put("tag", tag)
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

            ProxyProtocol.HYSTERIA2, ProxyProtocol.TUIC, ProxyProtocol.RAW_JSON -> {
                put("protocol", "freedom")
            }
        }

        if (profile.protocol != ProxyProtocol.WIREGUARD) {
            put("streamSettings", buildStreamSettings(profile, dialerProxyTag))
        }

        // Mux.Cool (auto-disabled when XTLS-Vision flow is active)
        if (settings.mux.enabled && profile.flow.isBlank() && profile.protocol != ProxyProtocol.WIREGUARD) {
            putJsonObject("mux") {
                put("enabled", true)
                put("concurrency", settings.mux.concurrency)
                put("xudpConcurrency", settings.mux.xudpConcurrency)
                put("xudpProxyUDP443", settings.mux.xudpProxyUDP443)
            }
        }
    }

    private fun buildStreamSettings(profile: ServerProfile, dialerProxyTag: String?): JsonObject = buildJsonObject {
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
                        putJsonObject("headers") { put("Host", profile.host) }
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
                    putJsonObject("header") { put("type", profile.headerType.ifBlank { "none" }) }
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

        if (!dialerProxyTag.isNullOrBlank()) {
            putJsonObject("sockopt") {
                put("dialerProxy", dialerProxyTag)
                put("tcpNoDelay", true)
            }
        }
    }

    private fun buildRouting(
        settings: AppSettings,
        hasGeoIp: Boolean,
        hasGeoSite: Boolean,
        enableBalancer: Boolean = false
    ): JsonObject = buildJsonObject {
        put("domainStrategy", settings.domainStrategy)
        if (enableBalancer) {
            putJsonArray("balancers") {
                add(buildJsonObject {
                    put("tag", "auto-failover")
                    putJsonArray("selector") { add("proxy") }
                    putJsonObject("strategy") {
                        put("type", "leastPing")
                    }
                })
            }
        }
        putJsonArray("rules") {
            add(buildJsonObject {
                put("type", "field")
                putJsonArray("inboundTag") { add("api-in") }
                put("outboundTag", "api")
            })

            // User-defined custom rules
            settings.customRules.filter { it.enabled && it.values.isNotBlank() }.forEach { rule ->
                val items = rule.values.split(",").map { it.trim() }.filter { item ->
                    if (item.isEmpty()) false
                    else if (item.startsWith("geoip:") && !hasGeoIp) false
                    else if (item.startsWith("geosite:") && !hasGeoSite) false
                    else true
                }
                if (items.isNotEmpty()) {
                    add(buildJsonObject {
                        put("type", "field")
                        when (rule.matchType) {
                            RuleMatchType.DOMAIN -> putJsonArray("domain") { items.forEach { add(it) } }
                            RuleMatchType.IP -> putJsonArray("ip") { items.forEach { add(it) } }
                            RuleMatchType.PORT -> put("port", items.joinToString(","))
                            RuleMatchType.PROTOCOL -> putJsonArray("protocol") { items.forEach { add(it) } }
                        }
                        if (enableBalancer && rule.target.outboundTag == "proxy") {
                            put("balancerTag", "auto-failover")
                        } else {
                            put("outboundTag", rule.target.outboundTag)
                        }
                    })
                }
            }

            // Preset rules with safe CIDR fallback when GeoData is not yet downloaded
            when (settings.routingPreset) {
                RoutingPreset.GLOBAL -> Unit
                RoutingPreset.BYPASS_LAN -> {
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("ip") {
                            if (hasGeoIp) add("geoip:private")
                            else FALLBACK_PRIVATE_CIDRS.forEach { add(it) }
                        }
                        put("outboundTag", "direct")
                    })
                }
                RoutingPreset.ADBLOCK_BYPASS_LAN -> {
                    if (hasGeoSite) {
                        add(buildJsonObject {
                            put("type", "field")
                            putJsonArray("domain") { add("geosite:category-ads-all") }
                            put("outboundTag", "block")
                        })
                    }
                    add(buildJsonObject {
                        put("type", "field")
                        putJsonArray("ip") {
                            if (hasGeoIp) add("geoip:private")
                            else FALLBACK_PRIVATE_CIDRS.forEach { add(it) }
                        }
                        put("outboundTag", "direct")
                    })
                }
                RoutingPreset.CUSTOM -> Unit
            }

            if (enableBalancer) {
                add(buildJsonObject {
                    put("type", "field")
                    put("network", "tcp,udp")
                    put("balancerTag", "auto-failover")
                })
            }
        }
    }
}
