package com.v2vex.client

import com.v2vex.client.core.CoreManager
import com.v2vex.client.core.GeoDataInspector
import com.v2vex.client.core.LogBuffer
import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.RuleMatchType
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V2VexV330UnitTest {

    @Test
    fun strictPrivacyRedactsHostsSniPathsAndSecretsWhilePreservingLocalAddresses() {
        val profile = ServerProfile(
            name = "Private VPS",
            protocol = ProxyProtocol.VLESS,
            address = "secret-vps.mycorp.internal",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = TransportType.WS,
            host = "bughost.carrier.net",
            path = "/secret-ws-path",
            security = SecurityType.TLS,
            sni = "sni.carrier.net",
            verifyPeerCertByName = "secret-vps.mycorp.internal"
        )
        val rawJson = XrayConfigGenerator.generate(profile, AppSettings())

        val strict = LogBuffer.redactConfigJson(rawJson, strictPrivacy = true)
        assertFalse(strict.contains("11111111-2222-3333-4444-555555555555"))
        assertFalse(strict.contains("secret-vps.mycorp.internal"))
        assertFalse(strict.contains("bughost.carrier.net"))
        assertFalse(strict.contains("sni.carrier.net"))
        assertFalse(strict.contains("/secret-ws-path"))
        assertTrue(strict.contains("127.0.0.1"))
        assertTrue(strict.contains("<REDACTED_HOST>"))

        val standard = LogBuffer.redactConfigJson(rawJson, strictPrivacy = false)
        assertFalse(standard.contains("11111111-2222-3333-4444-555555555555"))
        assertTrue(standard.contains("secret-vps.mycorp.internal"))
        assertTrue(standard.contains("sni.carrier.net"))
    }

    @Test
    fun xrayLanAuthEmitsPasswordAccountsOnSocksAndHttp() {
        val profile = ServerProfile(
            name = "Node",
            protocol = ProxyProtocol.TROJAN,
            address = "node.example.com",
            port = 443,
            uuidOrPassword = "trojan-pass"
        )
        val settings = AppSettings(
            allowLan = true,
            lanProxyUsername = "admin",
            lanProxyPassword = "strong-lan-password"
        )
        val generated = XrayConfigGenerator.generate(profile, settings)
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"auth\": \"password\""))
        assertTrue(generated.contains("\"user\": \"admin\""))
        assertTrue(generated.contains("\"pass\": \"strong-lan-password\""))
    }

    @Test
    fun geoDataInspectorFlagsUnknownAndMismatchedTokens() {
        val validSites = setOf("youtube", "netflix", "category-ads-all")
        val validIps = setOf("private", "id", "us")

        val badDomainTokens = GeoDataInspector.findInvalidGeoTokens(
            matchType = RuleMatchType.DOMAIN,
            values = "geosite:youtube, geosite:nonexistent-tag, geoip:id, domain:example.com, geosite:netflix@cn",
            validGeoSiteTags = validSites,
            validGeoIpTags = validIps
        )
        assertEquals(2, badDomainTokens.size)
        assertTrue(badDomainTokens.any { it.contains("geosite:nonexistent-tag") })
        assertTrue(badDomainTokens.any { it.contains("geoip:id") })

        val validIpTokens = GeoDataInspector.findInvalidGeoTokens(
            matchType = RuleMatchType.IP,
            values = "geoip:id, 1.1.1.0/24",
            validGeoSiteTags = validSites,
            validGeoIpTags = validIps
        )
        assertTrue(validIpTokens.isEmpty())
    }

    @Test
    fun coreVersionComparatorDetectsUpToDateAndUpdates() {
        assertEquals(
            CoreManager.VersionCompareState.UP_TO_DATE,
            CoreManager.compareVersionTags("Xray 26.11.9 (Xray, Penetrates Everything.)", "v26.11.9")
        )
        assertEquals(
            CoreManager.VersionCompareState.UPDATE_AVAILABLE,
            CoreManager.compareVersionTags("sing-box version 1.14.4", "v1.14.5")
        )
        assertEquals(
            CoreManager.VersionCompareState.UPDATE_AVAILABLE,
            CoreManager.compareVersionTags("3.2.0", "v3.3.0")
        )
        assertEquals(
            CoreManager.VersionCompareState.UP_TO_DATE,
            CoreManager.compareVersionTags("3.3.0", "v3.3.0")
        )
        val formatted = CoreManager.formatVersionComparison("V2Vex", "3.2.0", "v3.3.0")
        assertTrue(formatted.contains("Update available"))
    }
}
