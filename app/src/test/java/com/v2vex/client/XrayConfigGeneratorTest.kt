package com.v2vex.client

import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.CustomRoutingRule
import com.v2vex.client.model.FragmentConfig
import com.v2vex.client.model.MuxConfig
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.RoutingPreset
import com.v2vex.client.model.RuleMatchType
import com.v2vex.client.model.RuleTarget
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
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

        val generated = XrayConfigGenerator.generate(profile, settings)
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"dialerProxy\": \"fragment\""))
        assertTrue(generated.contains("\"xhttpSettings\""))
        assertTrue(generated.contains("\"realitySettings\""))
        assertTrue(generated.contains("geosite:category-ads-all"))
        assertTrue(generated.contains("domain:local.test"))
        assertTrue(generated.contains("\"StatsService\""))
    }
}
