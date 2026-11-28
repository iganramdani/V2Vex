package com.v2vex.client

import com.v2vex.client.core.SingBoxConfigGenerator
import com.v2vex.client.core.XrayConfigGenerator
import com.v2vex.client.model.AppSettings
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.ServerProfile
import com.v2vex.client.model.TransportType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingBoxConfigGeneratorTest {

    @Test
    fun neverSilentlyEnablesInsecureOnSafeSniSpoof() {
        val profile = ServerProfile(
            name = "Safe SNI Node",
            protocol = ProxyProtocol.VLESS,
            address = "vps.example.id",
            port = 443,
            uuidOrPassword = "11111111-2222-3333-4444-555555555555",
            transport = TransportType.WS,
            security = SecurityType.TLS,
            sni = "bug.cdn.com",
            verifyPeerCertByName = "vps.example.id",
            allowInsecure = false
        )

        assertTrue(SingBoxConfigGenerator.requiresUnsupportedSafeSniSpoof(profile))

        val generated = SingBoxConfigGenerator.generate(profile, AppSettings())
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"insecure\": false"))
        assertFalse(generated.contains("\"insecure\": true"))
    }

    @Test
    fun safeSniCheckAllowsMatchingCertOrExplicitInsecure() {
        val matching = ServerProfile(
            name = "Normal TLS",
            protocol = ProxyProtocol.VLESS,
            address = "vps.example.id",
            port = 443,
            uuidOrPassword = "uuid",
            security = SecurityType.TLS,
            sni = "vps.example.id",
            verifyPeerCertByName = "vps.example.id",
            allowInsecure = false
        )
        assertFalse(SingBoxConfigGenerator.requiresUnsupportedSafeSniSpoof(matching))

        val explicitInsecure = matching.copy(sni = "bug.cdn.com", allowInsecure = true)
        assertFalse(SingBoxConfigGenerator.requiresUnsupportedSafeSniSpoof(explicitInsecure))
    }

    @Test
    fun multiHopChainWiresDetourOutbound() {
        val exitNode = ServerProfile(
            id = "exit-1",
            name = "WARP Clean IP",
            protocol = ProxyProtocol.WIREGUARD,
            address = "162.159.192.1",
            port = 2408,
            uuidOrPassword = "privkey",
            wgPeerPublicKey = "pubkey"
        )
        val hopNode = ServerProfile(
            id = "hop-1",
            name = "Entry Bug Node",
            protocol = ProxyProtocol.TROJAN,
            address = "entry.example.com",
            port = 443,
            uuidOrPassword = "hop-password"
        )
        val generated = SingBoxConfigGenerator.generate(exitNode, AppSettings(), chainProfile = hopNode)
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"detour\": \"proxy-hop\""))
        assertTrue(generated.contains("\"tag\": \"proxy-hop\""))
        assertTrue(generated.contains("entry.example.com"))
    }

    @Test
    fun allowLanWithCredentialsEmitsInboundUsers() {
        val profile = ServerProfile(
            name = "Hy2",
            protocol = ProxyProtocol.HYSTERIA2,
            address = "hy2.example.com",
            port = 443,
            uuidOrPassword = "secret"
        )
        val settings = AppSettings(
            allowLan = true,
            lanProxyUsername = "v2user",
            lanProxyPassword = "v2secretpassword"
        )
        val generated = SingBoxConfigGenerator.generate(profile, settings)
        assertTrue(XrayConfigGenerator.validateJson(generated).isSuccess)
        assertTrue(generated.contains("\"listen\": \"0.0.0.0\""))
        assertTrue(generated.contains("\"username\": \"v2user\""))
        assertTrue(generated.contains("\"password\": \"v2secretpassword\""))
    }
}
