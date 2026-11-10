package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.group.RawUpdater
import moe.matsuri.nb4a.Protocols
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.app.Application

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class SubscriptionDeduplicationTest {
    private fun identity(bean: AbstractBean) = Protocols.Deduplication(bean, bean.javaClass.name)
    @Test fun accountsAndProtocolParametersRemainDistinctButNamesDoNot() {
        val socks = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "example.org"; username = "one"; password = "secret" }
        assertNotEquals(identity(socks), identity(socks.clone().apply { username = "two" }))
        assertNotEquals(identity(socks), identity(socks.clone().apply { password = "different" }))
        assertNotEquals(identity(socks), identity(socks.clone().apply { protocol = 0 }))
        assertEquals(identity(socks), identity(socks.clone().apply { name = "renamed" }))
        val hy = HysteriaBean().apply { initializeDefaultValues(); serverAddress = "example.org"; serverPorts = "443"; protocolVersion = 2 }
        assertNotEquals(identity(hy), identity(hy.clone().apply { serverPorts = "8443" }))
        assertNotEquals(identity(hy), identity(hy.clone().apply { sni = "different.example" }))
        assertNotEquals(identity(hy), identity(hy.clone().apply { protocolVersion = 1 }))
    }
    @Test fun embeddedGraphsArePartOfIdentityAndLabelsAreNot() {
        fun imported(tag: String, hop: String, password: String) = RawUpdater.parseJSON(JSONObject("""{"outbounds":[
          {"type":"socks","tag":"$tag","server":"example.org","server_port":1080,"detour":"$hop"},
          {"type":"socks","tag":"$hop","server":"hop.example","server_port":1080,"password":"$password"}]}""")).first()
        assertEquals(identity(imported("A", "H", "p")), identity(imported("B", "J", "p")))
        assertNotEquals(identity(imported("A", "H", "p")), identity(imported("A", "H", "q")))
    }
}
