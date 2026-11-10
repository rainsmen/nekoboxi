package io.nekohasekai.sagernet.ktx

import org.junit.Assert.*
import org.junit.Test

class LogRedactorTest {
    @Test fun removesCredentialsFromConfigurationAndLinks() {
        val input = """{"password":"secret-pw","uuid":"secret-uuid","private_key":"secret-key","auth_str":"secret-auth","obfs-password":"secret-obfs","token":"secret-token","realmToken":"secret-realm","obfuscation":"secret-obfuscation"}
            |failed hy2://user:secret-pw@example.com/?auth=secret-auth
            |vmess://encoded-secret
            |https://subscription.example/path/secret-token
            |password: secret-yaml
            |Authorization: Bearer secret-header
            |connected; elapsed=24ms
        """.trimMargin()
        val output = LogRedactor.redact(input)
        assertFalse(output, output.contains("secret-"))
        assertFalse(output, output.contains("encoded-secret"))
        assertTrue(output.contains("elapsed=24ms"))
        assertEquals(output, LogRedactor.redact(output))
    }
    @Test fun urlTokensDoNotEraseTheFollowingFailureReason() {
        val result = LogRedactor.redact("Get https://sub.example/private?token=secret: timeout")
        assertFalse(result.contains("secret"))
        assertFalse(result.contains("private"))
        assertTrue(result.contains("timeout"))
    }
    @Test fun removesMultilinePrivateKeys() {
        val output = LogRedactor.redact("-----BEGIN PRIVATE KEY-----\nprivate-material\n-----END PRIVATE KEY-----")
        assertFalse(output.contains("private-material"))
    }
}
