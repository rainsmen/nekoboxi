package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.trojan.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [29])
class V2RayRoundTripTest {
    @Test fun vlessUdpEncodingsSurviveStandardSharing() {
        for (encoding in 0..2) {
            val bean = VMessBean().apply {
                initializeDefaultValues(); alterId = -1; packetEncoding = encoding
                uuid = "00000000-0000-0000-0000-000000000001"
                serverAddress = "example.org"; serverPort = 443
            }
            val parsed = parseV2Ray(bean.toUriVMessVLESSTrojan(false)).apply { initializeDefaultValues() }
            assertEquals(encoding, parsed.packetEncoding)
        }
        assertEquals(1, parseV2Ray("vless://id@example.org:443?packetEncoding=packet").packetEncoding)
    }

    @Test fun trojanTlsChoiceSurvivesStandardSharing() {
        for (security in listOf("none", "tls")) {
            val bean = parseTrojan("trojan://password@example.org:443?security=$security&type=tcp")
                .apply { initializeDefaultValues() }
            val parsed = parseTrojan(bean.toUriVMessVLESSTrojan(true)).apply { initializeDefaultValues() }
            assertEquals(security, parsed.security)
            assertEquals("password", parsed.password)
        }
        assertEquals("tls", parseTrojan("trojan://password@example.org:443").security)
    }
}
