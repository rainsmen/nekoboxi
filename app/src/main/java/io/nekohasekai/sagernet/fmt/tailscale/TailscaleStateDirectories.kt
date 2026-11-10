package io.nekohasekai.sagernet.fmt.tailscale

import org.json.JSONArray
import java.security.MessageDigest

/** A build owns each directory once. No directory can silently belong to a restored/rekeyed profile. */
class TailscaleStateDirectories(private val generation: String) {
    private val occurrences = mutableMapOf<String, Int>()

    fun allocate(profileId: Long, authKey: String, controlUrl: String, role: String): String {
        val identity = JSONArray(listOf(generation, profileId, authKey, controlUrl, role)).toString()
        val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val occurrence = occurrences.getOrDefault(digest, 0)
        occurrences[digest] = occurrence + 1
        // Even repeated appearances of the same profile/chain must not open a FileStore twice.
        return "tailscale-profiles/$digest-$occurrence"
    }
}
