package io.nekohasekai.sagernet.fmt.naive

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.LOCALHOST
import io.nekohasekai.sagernet.ktx.*
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.JavaUtil
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

fun buildSingBoxOutboundNaiveBean(bean: NaiveBean): moe.matsuri.nb4a.SingBoxOptions.SingBoxOption {
    val _hack_config_map = mutableMapOf<String, Any>()
    _hack_config_map["type"] = "naive"
    _hack_config_map["server"] = bean.serverAddress
    _hack_config_map["server_port"] = bean.serverPort
    if (bean.username.isNotBlank()) _hack_config_map["username"] = bean.username
    if (bean.password.isNotBlank()) _hack_config_map["password"] = bean.password
    if (bean.insecureConcurrency > 0) _hack_config_map["insecure_concurrency"] = bean.insecureConcurrency

    if (bean.extraHeaders.isNotBlank()) {
        val extraHeaders = mutableMapOf<String, List<String>>()
        bean.extraHeaders.split("\n").forEach { line ->
            val parts = line.split(":", limit = 2)
            if (parts.size == 2) {
                extraHeaders[parts[0].trim()] = listOf(parts[1].trim())
            }
        }
        if (extraHeaders.isNotEmpty()) {
            _hack_config_map["extra_headers"] = extraHeaders
        }
    }

    val tlsOptions = moe.matsuri.nb4a.SingBoxOptions.OutboundTLSOptions().apply {
        enabled = true
        server_name = bean.sni.ifBlank { bean.serverAddress }
        if (bean.certificates.isNotBlank()) {
            certificate = bean.certificates
        }
    }
    _hack_config_map["tls"] = tlsOptions
    
    // Naive over HTTP/3 or TCP is determined by the protocol. But Native Naive uses Cronet which handles it natively.
    // If the proto contains quic we can enable it or just let cronet decide.

    return moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption(JavaUtil.gson.toJson(_hack_config_map))
}

// External-plugin config for the bundled naive binary (libnaive.so). The plugin
// listens on a local SOCKS port and dials the upstream via finalAddress/finalPort
// (set to the local mapping inbound by ConfigBuilder); host-resolver-rules keeps the
// real SNI/host for TLS while resolving it to that mapping.
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