package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.SingBoxOptions.DNSServerOptions
import java.net.URI

/**
 * Converts a DNS address as typed in settings ("https://dns.google/dns-query",
 * "tls://1.1.1.1", "8.8.8.8", "local", ...) into a typed sing-box DNS server.
 *
 * sing-box 1.14 removed the legacy `address` format. This mirrors the conversion
 * sing-box 1.12/1.13 did internally (option/dns.go, DNSServerOptions.Upgrade),
 * so existing user settings keep the same meaning.
 */
object DnsServerParser {

    private val defaultPorts = mapOf(
        "udp" to 53, "tcp" to 53, "tls" to 853, "quic" to 853, "https" to 443, "h3" to 443,
    )

    class InvalidDnsAddressException(address: String, reason: String) :
        IllegalArgumentException("Invalid DNS address \"$address\": $reason")

    fun parse(address: String, tag: String): DNSServerOptions {
        val server = DNSServerOptions()
        server.tag = tag
        val trimmed = address.trim()
        when (trimmed) {
            "local" -> return server.apply { type = "local" }
            "fakeip" -> return server.apply { type = "fakeip" }
        }
        val schemeEnd = trimmed.indexOf("://")
        if (schemeEnd < 0) {
            // Plain "host" or "host:port" means UDP.
            val (host, port) = splitHostPort(trimmed, address)
            return server.apply {
                type = "udp"
                this.server = host
                server_port = port?.takeIf { it != defaultPorts["udp"] }
            }
        }
        val scheme = trimmed.substring(0, schemeEnd).lowercase()
        val uri = try {
            URI(trimmed)
        } catch (e: Exception) {
            throw InvalidDnsAddressException(address, e.message ?: "malformed URL")
        }
        when (scheme) {
            "udp", "tcp", "tls", "quic", "https", "h3" -> {
                val authority = uri.rawAuthority?.substringAfterLast('@')
                    ?: throw InvalidDnsAddressException(address, "missing host")
                val (host, port) = splitHostPort(authority, address)
                server.type = scheme
                server.server = host
                server.server_port = port?.takeIf { it != defaultPorts[scheme] }
                if (scheme == "https" || scheme == "h3") {
                    val path = uri.rawPath.orEmpty()
                    if (path.isNotEmpty() && path != "/dns-query") server.path = path
                }
            }

            "dhcp" -> {
                server.type = "dhcp"
                val iface = uri.rawAuthority.orEmpty()
                if (iface.isNotEmpty() && iface != "auto") server.interface_ = iface
            }

            "rcode" -> throw InvalidDnsAddressException(
                address, "rcode:// servers were removed in sing-box 1.14, use a block rule instead"
            )

            else -> throw InvalidDnsAddressException(address, "unsupported scheme $scheme")
        }
        return server
    }

    /** Network DNS server types, which accept dial fields (`detour`, `domain_resolver`). */
    fun isRemote(server: DNSServerOptions) = server.type in defaultPorts.keys

    /** Hostname of a DNS address, or null for local/fakeip/dhcp or an unparsable address. */
    fun hostOf(address: String): String? = try {
        parse(address, "").takeIf { isRemote(it) }?.server
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun splitHostPort(authority: String, address: String): Pair<String, Int?> {
        if (authority.isEmpty()) throw InvalidDnsAddressException(address, "missing host")
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 0) throw InvalidDnsAddressException(address, "unclosed IPv6 bracket")
            val host = authority.substring(1, end)
            val rest = authority.substring(end + 1)
            if (rest.isEmpty()) return host to null
            if (!rest.startsWith(":")) throw InvalidDnsAddressException(address, "bad port")
            return host to parsePort(rest.substring(1), address)
        }
        val colons = authority.count { it == ':' }
        return when {
            colons == 0 -> authority to null
            // Bare IPv6 without brackets: no port.
            colons > 1 -> authority to null
            else -> authority.substringBefore(':') to parsePort(authority.substringAfter(':'), address)
        }
    }

    private fun parsePort(port: String, address: String): Int {
        val value = port.toIntOrNull()
        if (value == null || value !in 1..65535) throw InvalidDnsAddressException(address, "bad port $port")
        return value
    }
}
