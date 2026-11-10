package io.nekohasekai.sagernet.ktx

/** Also applied at export, to sanitize logs retained from previous app versions. */
object LogRedactor {
    private val links = Regex("""(?i)\b([a-z][a-z0-9+.-]*):/{2}[^\s<>"']+""")
    private val names = "realm[_-]?token|obfuscation|password|passwd|psk|uuid|private[_-]?key|user[_-]?key|token|auth(?:[_-]?(?:str|payload))?|obfs[_-]?password|authorization|subscription[_-]?url"
    private val jsonSecrets = Regex("""(?i)("(?:$names)"\s*:\s*)"(?:\\.|[^"\\])*"""")
    private val lineSecrets = Regex("""(?im)(\b(?:$names)\s*[:=]\s*)([^\r\n,}]+)""")
    private val privateKeys = Regex("""-----BEGIN [^-]*PRIVATE KEY-----[\s\S]*?-----END [^-]*PRIVATE KEY-----""")
    fun redact(text: String): String {
        var result = privateKeys.replace(text, "[private key redacted]")
        result = links.replace(result) { it.groupValues[1] + "://[redacted]" }
        result = jsonSecrets.replace(result) { it.groupValues[1] + "\"[redacted]\"" }
        result = lineSecrets.replace(result) { it.groupValues[1] + "[redacted]" }
        return result
    }
}
