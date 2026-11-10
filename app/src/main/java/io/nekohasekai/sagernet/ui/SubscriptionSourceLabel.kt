package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.ktx.LogRedactor
import java.net.URI

internal object SubscriptionSourceLabel {
    /** Subscription credentials are never used as a fallback display name. */
    fun display(name: String?, link: String?, fallback: String): String {
        val safeName = name?.takeIf { it.isNotBlank() }?.let(LogRedactor::redact)
        val host = link?.let { runCatching { URI(it).host }.getOrNull() }
        return when {
            safeName != null && host != null -> "$safeName ($host)"
            safeName != null -> safeName
            host != null -> host
            else -> fallback
        }
    }
}
