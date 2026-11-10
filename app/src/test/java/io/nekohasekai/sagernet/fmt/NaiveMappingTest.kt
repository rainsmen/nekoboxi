package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
class NaiveMappingTest {
    @Test fun everyUpstreamUsesTheLocalMappingWhileKeepingItsTlsIdentity() {
        DataStore.logLevel = 0
        for ((host, sni, normalized) in listOf(
            Triple("proxy.example", "", "proxy.example"),
            Triple("192.0.2.10", "", "192.0.2.10"),
            Triple("2001:0db8:0000:0000:0000:0000:0000:0010", "", "2001:db8::10"),
            Triple("192.0.2.10", "proxy.example", "proxy.example"),
            Triple("2001:db8::10", "proxy.example", "proxy.example"))) {
            val bean = NaiveBean().apply {
                initializeDefaultValues(); proto = "https"; serverAddress = host; serverPort = 443
                this.sni = sni; finalAddress = "127.0.0.1"; finalPort = 45678
            }
            val config = JSONObject(bean.buildNaiveConfig(45679))
            assertEquals("MAP $normalized 127.0.0.1", config.getString("host-resolver-rules"))
            val urlHost = if (':' in normalized) "[$normalized]" else normalized
            assertTrue(config.getString("proxy").startsWith("https://$urlHost:45678"))
            assertEquals("127.0.0.1", bean.finalAddress)
            assertEquals(45678, bean.finalPort)
        }
    }
}
