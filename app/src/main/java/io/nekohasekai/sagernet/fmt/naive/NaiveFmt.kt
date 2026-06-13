package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject

fun parseNaive(link: String): NaiveBean {
    val proto = link.substringAfter("+").substringBefore(":")
    val url = ("https://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Invalid naive link: $link")
    return NaiveBean().also {
        it.proto = proto
    }.apply {
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        sni = url.queryParameter("sni")
        certificates = url.queryParameter("cert")
        extraHeaders = url.queryParameter("extra-headers")?.unUrlSafe()?.replace("\r\n", "\n")
        insecureConcurrency = url.queryParameter("insecure-concurrency")?.toIntOrNull()
        name = url.fragment
        initializeDefaultValues()
    }
}

fun NaiveBean.toUri(proxyOnly: Boolean = false): String {
    val builder = linkBuilder().host(finalAddress).port(finalPort)
    if (username.isNotBlank()) {
        builder.username(username)
        if (password.isNotBlank()) {
            builder.password(password)
        }
    }
    if (!proxyOnly) {
        if (sni.isNotBlank()) {
            builder.addQueryParameter("sni", sni)
        }
        if (certificates.isNotBlank()) {
            builder.addQueryParameter("cert", certificates)
        }
        if (extraHeaders.isNotBlank()) {
            builder.addQueryParameter("extra-headers", extraHeaders)
        }
        if (name.isNotBlank()) {
            builder.encodedFragment(name.urlSafe())
        }
        if (insecureConcurrency > 0) {
            builder.addQueryParameter("insecure-concurrency", "$insecureConcurrency")
        }
    }
    return builder.toLink(if (proxyOnly) proto else "naive+$proto", false)
}

/**
 * Build a domain_resolver configuration for outbounds that need to resolve domain names
 * to IP addresses using a specific DNS server and strategy.
 *
 * This is particularly useful for Naive outbound when the server address is a domain,
 * as it ensures proper domain resolution with the configured strategy.
 *
 * @param server The DNS server to use (default: "dns-direct")
 * @param strategy The domain resolution strategy (e.g., "ipv4_only", "ipv6_only", "prefer_ipv4", "prefer_ipv6")
 *                 If empty or forTest is true, no strategy is set.
 * @param forTest Whether this is for testing (skips strategy configuration)
 * @return A map representing the domain_resolver configuration
 */
fun buildDomainResolver(
    server: String = "dns-direct",
    strategy: String = "",
    forTest: Boolean = false
): Map<String, Any> {
    val resolver = mutableMapOf<String, Any>("server" to server)
    if (!forTest && strategy.isNotEmpty()) {
        resolver["strategy"] = strategy
    }
    return resolver
}

fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): moe.matsuri.nb4a.SingBoxOptions.Outbound_NaiveOptions {
    return moe.matsuri.nb4a.SingBoxOptions.Outbound_NaiveOptions().apply {
        type = "naive"
        server = bean.serverAddress
        server_port = bean.serverPort

        if (bean.username.isNotBlank()) username = bean.username
        if (bean.password.isNotBlank()) password = bean.password
        if (bean.insecureConcurrency > 0) insecure_concurrency = bean.insecureConcurrency
        if (bean.proto == "quic") quic = true

        // Parse extra headers
        if (bean.extraHeaders.isNotBlank()) {
            val headers = mutableMapOf<String, List<String>>()
            bean.extraHeaders.split("\n").forEach { line ->
                val parts = line.split(":", limit = 2)
                if (parts.size == 2) {
                    headers[parts[0].trim()] = listOf(parts[1].trim())
                }
            }
            if (headers.isNotEmpty()) {
                extra_headers = headers
            }
        }

        // TLS configuration
        tls = moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions().apply {
            enabled = true
            server_name = bean.sni.ifBlank { bean.serverAddress }
            if (bean.certificates.isNotBlank()) {
                certificate = bean.certificates
            }
        }
    }
}

// External-plugin config for the bundled naive binary (libnaive.so). The plugin
// listens on a local SOCKS port and dials the upstream via finalAddress/finalPort
// (set to the local mapping inbound by ConfigBuilder); host-resolver-rules keeps the
// real SNI/host for TLS while resolving it to that mapping.
//
// DEPRECATED: External naive plugin is replaced by native sing-box outbound.
// This function is retained for potential rollback but is no longer called in the current code path.
// Use buildSingBoxOutboundNaiveBean() instead.
@Deprecated(
    message = "External naive plugin is replaced by native sing-box outbound. Use buildSingBoxOutboundNaiveBean() instead.",
    level = DeprecationLevel.WARNING
)
fun NaiveBean.buildNaiveConfig(port: Int): String {
    return JSONObject().also { conf ->
        conf.put("listen", "socks://$LOCALHOST:$port")

        val mappedAddress = finalAddress
        val mappedPort = finalPort
        val upstreamHost = sni.ifBlank { serverAddress }

        if (!upstreamHost.isIpAddress()) {
            conf.put("host-resolver-rules", "MAP $upstreamHost $mappedAddress")
        }

        finalAddress = upstreamHost
        finalPort = mappedPort
        conf.put("proxy", toUri(true))
        finalAddress = mappedAddress
        finalPort = mappedPort

        if (extraHeaders.isNotBlank()) {
            conf.put("extra-headers", extraHeaders.split("\n").joinToString("\r\n"))
        }
        if (DataStore.logLevel > 0) {
            conf.put("log", "")
        }
        if (insecureConcurrency > 0) {
            conf.put("insecure-concurrency", insecureConcurrency)
        }
    }.toString()
}