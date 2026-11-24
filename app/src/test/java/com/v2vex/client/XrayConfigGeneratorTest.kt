package com.v2vex.client

import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.CustomRoutingRule
import com.v2vex.client.model.DnsSettings
import com.v2vex.client.model.FragmentConfig
import com.v2vex.client.model.MuxConfig
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.RuleMatchType
import com.v2vex.client.model.RuleTarget
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class XrayConfigGeneratorTest {

    @Test
    fun generatesValidJsonWithFragmentRoutingAndStats() {
        val profile = ServerProfile(
            name = "Test REALITY",
            protocol = ProxyProtocol.VLESS,
            address = "edge.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = TransportType.XHTTP,
            path = "/split",
            security = SecurityType.REALITY,
            sni = "www.cloudflare.com",
            realityPublicKey = "PBK123",
            realityShortId = "ab12"
        )
        val settings = AppSettings(
            routingPreset = RoutingPreset.ADBLOCK_BYPASS_LAN,
            fragment = FragmentConfig(enabled = true, packets = "tlshello", length = "10-20", interval = "10-20"),
            mux = MuxConfig(enabled = true, concurrency = 8),
            customRules = listOf(
                CustomRoutingRule(
                    name = "Direct Local",
                    matchType = RuleMatchType.DOMAIN,
                    values = "domain:local.test",
                    target = RuleTarget.DIRECT
                )
            )
        )

        val generated = XrayConfigGenerator.generate(profile, settings, hasGeoIp = true, hasGeoSite = true)
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"dialerProxy\": \"fragment\""))
        assertTrue(generated.contains("\"xhttpSettings\""))
        assertTrue(generated.contains("\"realitySettings\""))
        assertTrue(generated.contains("geosite:category-ads-all"))
        assertTrue(generated.contains("domain:local.test"))
        assertTrue(generated.contains("\"StatsService\""))
    }

    @Test
    fun fakeDnsEmitsRootPoolAndDestOverride() {
        val profile = ServerProfile(
            name = "Node",
            protocol = ProxyProtocol.TROJAN,
            address = "t.example.com",
            port = 443,
            uuidOrPassword = "pass"
        )
        val settings = AppSettings(dns = DnsSettings(enableFakeDns = true))
        val generated = XrayConfigGenerator.generate(profile, settings, hasGeoIp = false, hasGeoSite = false)

        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"fakedns\""))
        assertTrue(generated.contains("198.18.0.0/15"))
        // Safe fallback CIDRs when geoip.dat is absent
        assertTrue(generated.contains("192.168.0.0/16"))
        assertFalse(generated.contains("geoip:private"))
    }

    @Test
    fun multiHopProxyChainWiresDialerProxyHop() {
        val primary = ServerProfile(
            id = "p1",
            name = "Exit Node",
            protocol = ProxyProtocol.VLESS,
            address = "exit.example.com",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555"
        )
        val hop = ServerProfile(
            id = "p2",
            name = "Entry Hop",
            protocol = ProxyProtocol.TROJAN,
            address = "entry.example.com",
            port = 443,
            uuidOrPassword = "hop-password"
        )
        val generated = XrayConfigGenerator.generate(primary, AppSettings(), chainProfile = hop)
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"dialerProxy\": \"proxy-hop\""))
        assertTrue(generated.contains("\"tag\": \"proxy-hop\""))
        assertTrue(generated.contains("entry.example.com"))
    }
}
