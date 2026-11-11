package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildNaiveConfig
import io.nekohasekai.sagernet.fmt.naive.toUri
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
class NaiveMappingTest {
    @Test fun headerValidationPreservesDuplicatesAndNeverPrintsInvalidValues() {
        val parsed = io.nekohasekai.sagernet.fmt.naive.parseNaiveHeaders("X-Test: one:two\r\nX-Test: three\n")
        assertEquals(listOf("X-Test" to "one:two", "X-Test" to "three"), parsed)
        for (bad in listOf("secret.example", "Bad Name: secret", "X: secret\rbroken", "X: secret\u0000")) {
            val error = assertThrows(io.nekohasekai.sagernet.database.DataValidationException::class.java) {
                io.nekohasekai.sagernet.fmt.naive.parseNaiveHeaders(bad)
            }
            assertFalse(error.message.orEmpty().contains("secret"))
        }
    }

    @Test fun legacyMissingColonLinesBuildPluginConfigWithoutRewritingTheSavedBean() {
        DataStore.logLevel = 0
        val bean = NaiveBean().apply {
            initializeDefaultValues(); serverAddress = "proxy.example"; serverPort = 443
            finalAddress = "127.0.0.1"; finalPort = 45678
            extraHeaders = "legacy.example\nX-Test: a+b%0A\r\nX-Test: keep:colon"
        }
        val saved = bean.extraHeaders
        var warnings = emptyList<Int>()
        val config = JSONObject(bean.buildNaiveConfig(45679) { warnings = it })
        assertEquals(listOf(1), warnings)
        assertEquals("X-Test: a+b%0A\r\nX-Test: keep:colon", config.getString("extra-headers"))
        assertEquals(saved, bean.extraHeaders)
        assertEquals("", bean.sni)
        bean.extraHeaders = "legacy.example"
        assertFalse(JSONObject(bean.buildNaiveConfig(45679)).has("extra-headers"))
        assertThrows(io.nekohasekai.sagernet.database.DataValidationException::class.java) {
            io.nekohasekai.sagernet.fmt.naive.parseNaiveHeaders(bean.extraHeaders)
        }
    }

    @Test fun standardLinksDecodeHeaderValuesExactlyOnce() {
        val original = NaiveBean().apply {
            initializeDefaultValues(); serverAddress = "proxy.example"; serverPort = 443
            finalAddress = serverAddress; finalPort = serverPort
            extraHeaders = "X-Test: a+b%0A%25\nX-Second: colon:value"
        }
        val parsed = io.nekohasekai.sagernet.fmt.naive.parseNaive(original.toUri())
        assertEquals(original.extraHeaders, parsed.extraHeaders)
        assertEquals(2, io.nekohasekai.sagernet.fmt.naive.parseNaiveHeaders(parsed.extraHeaders).size)
    }

    @Test fun legacyCompatibilityStillRejectsControlsAndMalformedNamedHeaders() {
        for (header in listOf("legacy.example\r", "Bad Name: value", "X-Test: value\u0000", "\u000b")) {
            assertThrows(io.nekohasekai.sagernet.database.DataValidationException::class.java) {
                io.nekohasekai.sagernet.fmt.naive.readNaiveHeaders(header, allowLegacyLines = true)
            }
        }
    }

    @Test fun invalidUriDoesNotIncludeCredentialsInTheError() {
        val error = assertThrows(IllegalStateException::class.java) {
            io.nekohasekai.sagernet.fmt.naive.parseNaive("naive+https://secret-user:secret-password@[]")
        }
        assertFalse(error.message.orEmpty().contains("secret"))
    }

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
