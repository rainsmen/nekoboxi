package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria2
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import moe.matsuri.nb4a.proxy.anytls.parseAnytls
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.proxy.snell.buildSingBoxOutboundSnellBean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the JSON shape sing-box 1.14 expects, via the same asMap() path as ConfigBuilder. */
class OutboundBuilderTest {

    private fun <T : AbstractBean> T.defaults() = apply { initializeDefaultValues() }

    @Test
    fun snellV4UsesObfs() {
        val map = buildSingBoxOutboundSnellBean(SnellBean().defaults().apply {
            serverAddress = "1.2.3.4"
            serverPort = 8388
            psk = "k"
            obfsMode = "http"
            obfsHost = "b.example"
            mode = "unshaped"
        }).asMap()
        assertEquals("snell", map["type"])
        assertEquals(4L, map["version"])
        assertEquals(8388L, map["server_port"])
        assertEquals("http", map["obfs_mode"])
        assertEquals("b.example", map["obfs_host"])
        assertFalse(map.containsKey("mode"))
        assertFalse(map.containsKey("userkey"))
    }

    @Test
    fun snellV6UsesMode() {
        val map = buildSingBoxOutboundSnellBean(SnellBean().defaults().apply {
            serverAddress = "1.2.3.4"
            serverPort = 1
            version = 6
            psk = "k"
            obfsMode = "tls"
            mode = "unshaped"
        }).asMap()
        assertEquals(6L, map["version"])
        assertEquals("unshaped", map["mode"])
        assertFalse(map.containsKey("obfs_mode"))
    }

    @Test
    fun wireguardIsEndpointWithPeer() {
        val map = buildSingBoxEndpointWireguardBean(WireGuardBean().defaults().apply {
            serverAddress = "1.2.3.4"
            serverPort = 51820
            localAddress = "10.0.0.2\nfd00::2"
            privateKey = "priv"
            peerPublicKey = "pub"
            reserved = "1,2,3"
            mtu = 1408
        }, "wg").asMap()
        assertEquals("wireguard", map["type"])
        assertEquals("wg", map["tag"])
        assertEquals(listOf("10.0.0.2/32", "fd00::2/128"), map["address"])
        assertEquals(1408L, map["mtu"])
        val peer = (map["peers"] as List<*>).single() as Map<*, *>
        assertEquals("1.2.3.4", peer["address"])
        assertEquals(51820L, peer["port"])
        assertEquals("pub", peer["public_key"])
        assertEquals(listOf(1L, 2L, 3L), peer["reserved"])
        assertFalse(map.containsKey("server"))
    }

    @Test
    fun hysteria2LinkKeepsObfsType() {
        val bean = parseHysteria2("hy2://pass@example.com:443/?sni=a.example&obfs=gecko&obfs-password=o#n")
        assertEquals("example.com", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
        assertEquals("pass", bean.authPayload)
        assertEquals("a.example", bean.sni)
        assertEquals("o", bean.obfuscation)
        assertEquals("gecko", bean.obfsType)

        val salamander = parseHysteria2("hysteria2://p@h:1/?obfs=salamander&obfs-password=o")
        assertEquals("", salamander.obfsType)
    }

    @Test
    fun anytlsLink() {
        val bean = parseAnytls("anytls://pw@h.example:8443/?sni=s.example&insecure=1&fp=chrome#name")
        assertEquals("h.example", bean.serverAddress)
        assertEquals(8443, bean.serverPort)
        assertEquals("pw", bean.password)
        assertEquals("s.example", bean.sni)
        assertTrue(bean.allowInsecure)
        assertEquals("chrome", bean.utlsFingerprint)
        assertNull(bean.echConfig)
    }
}
