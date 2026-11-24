package com.v2vex.client

import com.v2vex.client.core.UriParser
import com.v2vex.client.model.ProxyProtocol
import com.v2vex.client.model.SecurityType
import com.v2vex.client.model.TransportType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
class UriParserTest {

    @Test
    fun parseAndExportVlessReality() {
        val uri = "vless://11111111-2222-3333-4444-555555555555@sg.example.com:443" +
            "?encryption=none&flow=xtls-rprx-vision&security=reality&sni=www.cloudflare.com" +
            "&fp=chrome&pbk=TEST_PBK_KEY&sid=abcd1234&type=tcp#SG%20Reality"
        val profile = UriParser.parseSingle(uri)
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.VLESS, profile.protocol)
        assertEquals("sg.example.com", profile.address)
        assertEquals(443, profile.port)
        assertEquals("xtls-rprx-vision", profile.flow)
        assertEquals(SecurityType.REALITY, profile.security)
        assertEquals("TEST_PBK_KEY", profile.realityPublicKey)
        assertEquals("abcd1234", profile.realityShortId)
        assertEquals("SG Reality", profile.name)

        val exported = UriParser.exportUri(profile)
        assertTrue(exported.startsWith("vless://"))
        val reparsed = UriParser.parseSingle(exported)
        assertNotNull(reparsed)
        assertEquals(profile.realityPublicKey, reparsed!!.realityPublicKey)
        assertEquals(profile.flow, reparsed.flow)
    }

    @Test
    fun parseVmessBase64Json() {
        val vmessJson = """{"v":"2","ps":"JP WS","add":"jp.example.com","port":"443","id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee","aid":"0","net":"ws","type":"none","host":"jp.example.com","path":"/ws","tls":"tls","sni":"jp.example.com"}"""
        val b64 = kotlin.io.encoding.Base64.encode(vmessJson.toByteArray())
        val profile = UriParser.parseSingle("vmess://$b64")
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.VMESS, profile.protocol)
        assertEquals("JP WS", profile.name)
        assertEquals(TransportType.WS, profile.transport)
        assertEquals(SecurityType.TLS, profile.security)
        assertEquals("/ws", profile.path)
    }

    @Test
    fun parseTrojanGrpc() {
        val uri = "trojan://secretpass@us.example.com:8443?security=tls&sni=us.example.com&type=grpc&serviceName=mygrpc&mode=multi#US-gRPC"
        val profile = UriParser.parseSingle(uri)
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.TROJAN, profile.protocol)
        assertEquals("secretpass", profile.uuidOrPassword)
        assertEquals(TransportType.GRPC, profile.transport)
        assertEquals("mygrpc", profile.path)
        assertEquals("multi", profile.grpcMode)
    }

    @Test
    fun parseShadowsocksSip002() {
        val userInfo = kotlin.io.encoding.Base64.UrlSafe.encode("aes-256-gcm:my-ss-pass".toByteArray()).trimEnd('=')
        val uri = "ss://$userInfo@ss.example.com:8388#SS-Node"
        val profile = UriParser.parseSingle(uri)
        assertNotNull(profile)
        profile!!
        assertEquals(ProxyProtocol.SHADOWSOCKS, profile.protocol)
        assertEquals("aes-256-gcm", profile.encryption)
        assertEquals("my-ss-pass", profile.uuidOrPassword)
        assertEquals(8388, profile.port)
    }

    @Test
    fun parseBase64BatchSubscription() {
        val rawList = listOf(
            "trojan://p1@a.example.com:443#NodeA",
            "trojan://p2@b.example.com:443#NodeB"
        ).joinToString("\n")
        val encoded = kotlin.io.encoding.Base64.encode(rawList.toByteArray())
        val profiles = UriParser.parseBatch(encoded, subscriptionId = "sub-1")
        assertEquals(2, profiles.size)
        assertEquals("sub-1", profiles[0].subscriptionId)
        assertEquals("NodeB", profiles[1].name)
    }

    @Test
    fun parseHysteria2AndTuicUris() {
        val hy2 = UriParser.parseSingle("hysteria2://my-secret@hy2.example.com:8443?sni=hy2.example.com&obfs=salamander&obfs-password=obfspass#SG-Hy2")
        assertNotNull(hy2)
        assertEquals(ProxyProtocol.HYSTERIA2, hy2!!.protocol)
        assertEquals("my-secret", hy2.uuidOrPassword)
        assertEquals("salamander", hy2.obfsType)
        assertEquals("obfspass", hy2.obfsPassword)

        val tuic = UriParser.parseSingle("tuic://11111111-2222-3333-4444-555555555555:tuicpass@tuic.example.com:443?sni=tuic.example.com&congestion_control=bbr#JP-TUIC")
        assertNotNull(tuic)
        assertEquals(ProxyProtocol.TUIC, tuic!!.protocol)
        assertEquals("tuicpass", tuic.uuidOrPassword)
        assertEquals("bbr", tuic.congestionControl)
    }

    @Test
    fun parseClashYamlAndSingBoxJson() {
        val clash = """
            proxies:
              - { name: "Clash-VLESS", type: vless, server: c1.example.com, port: 443, uuid: "abc-123", tls: true, servername: c1.example.com }
              - { name: "Clash-Hy2", type: hysteria2, server: c2.example.com, port: 8443, password: "pw2", sni: c2.example.com }
        """.trimIndent()
        val clashProfiles = UriParser.parseBatch(clash)
        assertEquals(2, clashProfiles.size)
        assertEquals(ProxyProtocol.VLESS, clashProfiles[0].protocol)
        assertEquals(ProxyProtocol.HYSTERIA2, clashProfiles[1].protocol)

        val sbJson = com.v2vex.client.core.SingBoxConfigGenerator.generate(
            clashProfiles[1],
            com.v2vex.client.model.AppSettings()
        )
        assertTrue(sbJson.contains("\"type\": \"hysteria2\""))
        assertTrue(sbJson.contains("c2.example.com"))
    }
}
