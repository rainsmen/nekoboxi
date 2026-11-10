package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.socks.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, manifest = Config.NONE, sdk = [29])
class SocksRoundTripTest {
    @Test fun standardLinksPreserveLiteralCredentialsForEverySocksVersion() {
        for (protocol in listOf(SOCKSBean.PROTOCOL_SOCKS4, SOCKSBean.PROTOCOL_SOCKS4A, SOCKSBean.PROTOCOL_SOCKS5)) {
            for (username in listOf("dXNlcg==", "dXNlcjpwYXNz", "user", "user name")) {
                for (password in listOf("", " ", "secret")) {
                    val original = SOCKSBean().apply {
                        initializeDefaultValues(); this.protocol = protocol
                        serverAddress = "example.org"; serverPort = 1080
                        this.username = username; this.password = password
                    }
                    val restored = parseSOCKS(original.toUri())
                    assertEquals(protocol, restored.protocol)
                    assertEquals(username, restored.username)
                    assertEquals(password, restored.password)
                }
            }
        }
    }

    @Test fun legacyEncodedUserInfoStillImportsButUsernameAloneIsNotACredentialPair() {
        val legacy = parseSOCKS("socks://dXNlcjpwYXNz@example.org:1080")
        assertEquals("user", legacy.username)
        assertEquals("pass", legacy.password)
        val plain = parseSOCKS("socks://dXNlcg==@example.org:1080")
        assertEquals("dXNlcg==", plain.username)
        assertEquals("", plain.password)
    }
}
