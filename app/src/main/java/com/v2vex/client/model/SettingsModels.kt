package com.v2vex.client.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class RoutingPreset(val label: String, val description: String) {
    GLOBAL("Global Proxy", "Route all traffic through the active node"),
    BYPASS_LAN("Bypass LAN & Private", "Direct local/private networks, proxy all external traffic"),
    ADBLOCK_BYPASS_LAN("AdBlock + Bypass LAN", "Block ad/tracker domains via geosite:category-ads-all + bypass LAN"),
    CUSTOM("Custom Rules Only", "Evaluate user-defined routing rules before default outbound")
}

@Serializable
enum class RuleTarget(val outboundTag: String, val label: String) {
    PROXY("proxy", "Proxy"),
    DIRECT("direct", "Direct"),
    BLOCK("block", "Block")
}

@Serializable
enum class RuleMatchType(val label: String) {
    DOMAIN("Domain / Geosite"),
    IP("IP / CIDR / GeoIP"),
    PORT("Port / Range"),
    PROTOCOL("Sniffed Protocol")
}

@Serializable
data class CustomRoutingRule(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Custom Rule",
    val enabled: Boolean = true,
    val matchType: RuleMatchType = RuleMatchType.DOMAIN,
    val values: String = "", // comma-separated, e.g. "geosite:category-ads-all,domain:example.com"
    val target: RuleTarget = RuleTarget.DIRECT
)

@Serializable
enum class SplitTunnelMode(val label: String) {
    ALL_APPS("Proxy All Apps"),
    BYPASS_SELECTED("Bypass Selected Apps"),
    PROXY_ONLY_SELECTED("Proxy Only Selected Apps")
}

@Serializable
enum class ThemeMode(val label: String) {
    CYBER_DARK("Obsidian Two-Tone"),
    AMOLED_BLACK("AMOLED Monochrome"),
    LIGHT("Paper & Ink")
}

@Serializable
enum class SpeedUnit(val label: String) {
    MBPS("Mbps"),
    MB_S("MB/s")
}

@Serializable
data class FragmentConfig(
    val enabled: Boolean = false,
    val packets: String = "tlshello", // "tlshello" or "1-3"
    val length: String = "10-20",
    val interval: String = "10-20"
)

@Serializable
data class MuxConfig(
    val enabled: Boolean = false,
    val concurrency: Int = 8,
    val xudpConcurrency: Int = 16,
    val xudpProxyUDP443: String = "reject" // reject / allow / skip
)

@Serializable
data class SniffingConfig(
    val enabled: Boolean = true,
    val destOverrideHttp: Boolean = true,
    val destOverrideTls: Boolean = true,
    val destOverrideQuic: Boolean = true,
    val destOverrideFakeDns: Boolean = false,
    val routeOnly: Boolean = false
)

@Serializable
data class DnsSettings(
    val remoteDns: String = "https://1.1.1.1/dns-query",
    val backupDns: String = "https://8.8.8.8/dns-query",
    val directDns: String = "1.1.1.1",
    val enableFakeDns: Boolean = false,
    val queryStrategy: String = "UseIP" // UseIP / UseIPv4 / UseIPv6
)

@Serializable
data class SpeedTestSettings(
    val downloadUrl: String = "https://speed.cloudflare.com/__down?bytes=25000000",
    val uploadUrl: String = "https://speed.cloudflare.com/__up",
    val pingUrl: String = "https://www.gstatic.com/generate_204",
    val parallelStreams: Int = 4,
    val durationSeconds: Int = 8,
    val warmupMillis: Long = 1500L,
    val uploadPayloadMb: Int = 6
)

@Serializable
enum class CoreEnginePreference(val label: String) {
    AUTO("Auto (Dual-Core)"),
    XRAY("Xray-core"),
    SING_BOX("Sing-box")
}

@Serializable
data class AppSettings(
    // Dual-Core Engine
    val coreEngine: CoreEnginePreference = CoreEnginePreference.AUTO,

    // VPN & Local Ports
    val socksPort: Int = 10808,
    val httpPort: Int = 10809,
    val apiPort: Int = 10810,
    val allowLan: Boolean = false,
    val enableIpv6: Boolean = false,
    val vpnMtu: Int = 1500,

    // Pro Network Stability (QUIC Guard + Carrier NAT64 IPv4/IPv6 Dial Strategy)
    val blockQuic: Boolean = true,
    val outboundDomainStrategy: String = "AsIs", // AsIs / UseIPv4 / UseIPv6

    // Multi-Hop Proxy Chain (optional secondary node ID)
    val chainProfileId: String? = null,

    // Xray Observatory Auto-Failover & Least-Ping Load Balancer
    val enableAutoFailover: Boolean = false,
    val failoverNodeIds: List<String> = emptyList(),

    // Routing
    val routingPreset: RoutingPreset = RoutingPreset.BYPASS_LAN,
    val domainStrategy: String = "IPIfNonMatch", // AsIs / IPIfNonMatch / IPOnDemand
    val customRules: List<CustomRoutingRule> = emptyList(),

    // Advanced Engine Flexibility
    val fragment: FragmentConfig = FragmentConfig(),
    val mux: MuxConfig = MuxConfig(),
    val sniffing: SniffingConfig = SniffingConfig(),
    val dns: DnsSettings = DnsSettings(),
    val logLevel: String = "warning", // debug / info / warning / error / none

    // Split Tunneling
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.ALL_APPS,
    val splitTunnelPackages: Set<String> = emptySet(),

    // SpeedLab & Telemetry
    val speedTest: SpeedTestSettings = SpeedTestSettings(),
    val speedUnit: SpeedUnit = SpeedUnit.MBPS,

    // Core Auto-Update
    val customCoreRepo: String = "XTLS/Xray-core",
    val customGeoRepo: String = "Loyalsoldier/v2ray-rules-dat",

    // UI
    val themeMode: ThemeMode = ThemeMode.CYBER_DARK,
    val batteryBannerDismissed: Boolean = false
)
