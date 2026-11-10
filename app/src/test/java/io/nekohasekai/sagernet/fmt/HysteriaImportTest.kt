package io.nekohasekai.sagernet.fmt

import android.app.Application
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.group.RawUpdater
import kotlinx.coroutines.runBlocking
import moe.matsuri.nb4a.proxy.config.ConfigBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class HysteriaImportTest {
    @Test fun tuicFixedIpPreservesDomainSniAndExplicitOverride() = runBlocking {
        for (explicit in listOf("", "sni: override.example")) {
            val bean = RawUpdater.parseRaw("""
                proxies:
                  - name: tuic-ip
                    type: tuic
                    server: tuic.example
                    ip: 192.0.2.10
                    port: 443
                    uuid: 00000000-0000-0000-0000-000000000001
                    password: fixture
                    $explicit
            """.trimIndent())!!.single() as io.nekohasekai.sagernet.fmt.tuic.TuicBean
            assertEquals("192.0.2.10", bean.serverAddress)
            assertEquals(if (explicit.isEmpty()) "tuic.example" else "override.example", bean.sni)
        }
    }

    @Test fun importedDetourDependenciesSurviveSerializationAndRejectBrokenGraphs() {
        val document = JSONObject("""{"outbounds":[
            {"type":"socks","tag":"a","server":"example.org","server_port":443,"detour":"b"},
            {"type":"socks","tag":"b","server":"second.example","server_port":443}]}""")
        val original = RawUpdater.parseJSON(document).first() as ConfigBean
        val restored = original.clone()
        val arrayForm = RawUpdater.parseJSON(document.getJSONArray("outbounds")).first() as ConfigBean
        assertEquals(original, arrayForm)
        assertEquals(original.outboundDependencies, restored.outboundDependencies)
        assertEquals("b", org.json.JSONArray(restored.outboundDependencies).getJSONObject(0).getString("tag"))
        for (missingOrCycle in listOf("absent", "a")) {
            document.getJSONArray("outbounds").getJSONObject(1).put("detour", missingOrCycle)
            assertThrows(io.nekohasekai.sagernet.database.DataValidationException::class.java) {
                RawUpdater.parseJSON(document)
            }
        }
    }

    @Test fun extendedParametersSurviveSharing() {
        val original = HysteriaBean().apply {
            initializeDefaultValues()
            serverAddress = "example.com"; serverPorts = "443,8443,20000-30000"
            authPayload = "user:password"; obfsType = "gecko"; obfuscation = "secret"
            bbrProfile = "standard"; hopInterval = 10; hopIntervalMax = 20
            disableChromeParrot = true; uploadMbps = 20; downloadMbps = 100
            realmServerUrl = "https://realm.example/path"; realmId = "id"; realmToken = "token"
            realmStunServers = "stun.example:3478\nstun2.example:3478"
        }
        val copy = parseHysteria2(original.toUri()).apply { initializeDefaultValues() }
        assertEquals(original.authPayload, copy.authPayload)
        assertEquals(original.serverPorts, copy.serverPorts)
        assertEquals(original.bbrProfile, copy.bbrProfile)
        assertEquals(original.hopIntervalMax, copy.hopIntervalMax)
        assertEquals(original.realmServerUrl, copy.realmServerUrl)
        assertEquals(original.realmId, copy.realmId)
        assertEquals(original.realmToken, copy.realmToken)
        assertEquals(original.realmStunServers, copy.realmStunServers)
        assertEquals(original.disableChromeParrot, copy.disableChromeParrot)
    }
    @Test fun hoppingPreservesSinglesAndRanges() {
        assertEquals(listOf("443:443", "8443:8443"), hopPortsToSingboxList("443,8443"))
        assertEquals(listOf("443:443", "20000:30000"), hopPortsToSingboxList("443, 20000-30000"))
        for (invalid in listOf("", "0", "65536", "100:20", "443,", "abc", "1:2:3")) {
            assertThrows(IllegalArgumentException::class.java) { hopPortsToSingboxList(invalid) }
        }
    }
    @Test fun explicitJsonTypeWinsOverLegacyHeuristics() {
        val json = JSONObject("""{"type":"hysteria2","server":"example.com","server_port":443,"up_mbps":10,"password":"p"}""")
        val single = RawUpdater.parseJSON(json).single() as ConfigBean
        val wrapped = RawUpdater.parseJSON(JSONObject().put("outbounds", org.json.JSONArray().put(json))).single() as ConfigBean
        assertEquals(JSONObject(single.config).getString("type"), "hysteria2")
        assertEquals(JSONObject(single.config).getString("password"), "p")
        assertEquals(JSONObject(single.config).toString(), JSONObject(wrapped.config).toString())
    }
    @Test fun clashKeepsExtendedParameters() = runBlocking {
        val bean = RawUpdater.parseRaw("""
            proxies:
              - name: fixture
                type: hysteria2
                server: example.com
                port: 443
                ports: 443,8443
                password: p
                bbr_profile: standard
                hop_interval_max: 30s
                realm:
                  server_url: https://realm.example
                  realm_id: abc
                  token: secret
                  stun_servers: [stun.example:3478]
        """.trimIndent())!!.single() as HysteriaBean
        assertEquals("443,8443", bean.serverPorts)
        assertEquals("standard", bean.bbrProfile)
        assertEquals(30, bean.hopIntervalMax)
        assertEquals("https://realm.example", bean.realmServerUrl)
        assertEquals("abc", bean.realmId)
        assertEquals("secret", bean.realmToken)
        assertEquals("stun.example:3478", bean.realmStunServers)
    }
    @Test fun colonOnlyRangeSurvivesSharing() {
        val bean = HysteriaBean().apply {
            initializeDefaultValues(); protocolVersion = 2; serverAddress = "example.com"; serverPorts = "20000:30000"
        }
        assertEquals("20000:30000", parseHysteria2(bean.toUri()).serverPorts)
        assertEquals(20000, getFirstPort("20000-30000"))
    }
    @Test fun snellV6RetainsExtendedFields() = runBlocking {
        val bean = RawUpdater.parseRaw("""
            proxies:
              - {name: snell, type: snell, version: 6, server: example.com, port: 443, psk: dummy, userkey: user, mode: quic}
        """.trimIndent())!!.single() as moe.matsuri.nb4a.proxy.snell.SnellBean
        assertEquals("user", bean.userKey)
        assertEquals("quic", bean.mode)
    }

    @Test fun v1Base64AuthenticationSharesWithoutChangingItsTypeOrBytes() {
        val original = JSONObject("""{"server":"example.com:443","up_mbps":10,"down_mbps":50,"auth":"/wBhYmM="}""")
            .parseHysteria1Json().apply { initializeDefaultValues() }
        val link = original.toUri()
        assertTrue(link.startsWith("sn://"))
        val copy = parseUniversal(link) as HysteriaBean
        assertEquals(HysteriaBean.TYPE_BASE64, copy.authPayloadType)
        assertEquals(original.authPayload, copy.authPayload)
        assertEquals(original.protocolVersion, copy.protocolVersion)
    }
    @Test fun v1ExtendedConnectionOptionsUseLosslessLinks() {
        val base = HysteriaBean().apply {
            initializeDefaultValues(); protocolVersion = 1; serverAddress = "example.org"; serverPorts = "443"
            authPayloadType = HysteriaBean.TYPE_STRING; authPayload = "fixture"
        }
        for (bean in listOf(base.clone().apply { disableMtuDiscovery = true },
            base.clone().apply { streamReceiveWindow = 4096 },
            base.clone().apply { connectionReceiveWindow = 8192 },
            base.clone().apply { hopInterval = 20 })) {
            val link = io.nekohasekai.sagernet.database.ProxyEntity().putBean(bean).toStdLink(compact = true)
            assertTrue(link.startsWith("sn://"))
            assertEquals(bean, parseUniversal(link))
        }
    }

    @Test fun groupLinksPreserveCustomTrustAndTlsOptions() {
        val bean = HysteriaBean().apply {
            initializeDefaultValues(); protocolVersion = 2; serverAddress = "example.org"; serverPorts = "443"
            caText = "synthetic-ca"; tlsSpoofDomain = "cover.example"; tlsSpoofMethod = "synthetic-method"
        }
        val link = io.nekohasekai.sagernet.database.ProxyEntity().putBean(bean).toStdLink(compact = true)
        assertTrue(link.startsWith("sn://"))
        val restored = parseUniversal(link) as HysteriaBean
        assertEquals(bean.caText, restored.caText)
        assertEquals(bean.tlsSpoofDomain, restored.tlsSpoofDomain)
        assertEquals(bean.tlsSpoofMethod, restored.tlsSpoofMethod)
        assertFalse(restored.allowInsecure)
    }

    @Test fun disabledV1AuthenticationDoesNotLeakStalePasswordIntoShare() {
        val bean = HysteriaBean().apply {
            initializeDefaultValues(); protocolVersion = 1; serverAddress = "example.com"; serverPorts = "443"
            authPayloadType = HysteriaBean.TYPE_NONE; authPayload = "old-password"
        }
        assertFalse(bean.toUri().contains("old-password"))
    }
    @Test fun clashV2rayPluginKeepsTlsIndependentOfWebsocketMode() = runBlocking {
        for (tls in listOf(true, false)) {
            val bean = RawUpdater.parseRaw("""
                proxies:
                  - name: ss-tls
                    type: ss
                    server: example.com
                    port: 443
                    cipher: aes-128-gcm
                    password: p
                    plugin: v2ray-plugin
                    plugin-opts: {mode: websocket, tls: $tls, host: example.com}
            """.trimIndent())!!.single() as io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
            assertEquals(tls, "tls" in bean.plugin.split(';'))
            assertTrue(bean.plugin.contains("mode=websocket"))
        }
    }

    @Test fun authenticationPreservesExplicitEmptyAndWhitespaceSuffixes() {
        for (auth in listOf("user:", "user: ", ":", " : ", "user:a:b", "user@domain:password")) {
            val bean = HysteriaBean().apply {
                initializeDefaultValues(); protocolVersion = 2; serverAddress = "example.com"
                serverPorts = "443"; authPayload = auth
            }
            assertEquals(auth, parseHysteria2(bean.toUri()).authPayload)
        }
        assertEquals("user:", parseHysteria2("hy2://user:@example.com:443").authPayload)
        assertEquals("user: ", parseHysteria2("hy2://user:%20@example.com:443").authPayload)
    }

}
