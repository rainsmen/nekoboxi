package io.nekohasekai.sagernet.fmt.socks

import io.nekohasekai.sagernet.ktx.decodeBase64UrlSafe
import io.nekohasekai.sagernet.ktx.toLink
import io.nekohasekai.sagernet.ktx.unUrlSafe
import io.nekohasekai.sagernet.ktx.urlSafe
import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.NGUtil
import moe.matsuri.nb4a.utils.Util
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

fun parseSOCKS(link: String): SOCKSBean {
    val url = ("http://" + link.substringAfter("://")).toHttpUrlOrNull()
        ?: error("Not supported: $link")

    return SOCKSBean().apply {
        protocol = when {
            link.startsWith("socks4://") -> SOCKSBean.PROTOCOL_SOCKS4
            link.startsWith("socks4a://") -> SOCKSBean.PROTOCOL_SOCKS4A
            else -> SOCKSBean.PROTOCOL_SOCKS5
        }
        name = url.fragment
        serverAddress = url.host
        serverPort = url.port
        username = url.username
        password = url.password
        // v2rayN fmt
        val authority = link.substringAfter("://").substringBefore("@").substringBefore("/")
        if (protocol == SOCKSBean.PROTOCOL_SOCKS5 && password.isNullOrEmpty() &&
            !username.isNullOrEmpty() && !authority.contains(":")) {
            try {
                val n = username.decodeBase64UrlSafe()
                if (n.contains(":")) {
                    username = n.substringBefore(":")
                    password = n.substringAfter(":")
                }
            } catch (_: Exception) {
            }
        }
    }
}

fun SOCKSBean.toUri(): String {

    val builder = HttpUrl.Builder().scheme("http").host(serverAddress).port(serverPort)
    if (!username.isNullOrEmpty()) builder.username(username)
    if (!password.isNullOrEmpty()) builder.password(password)
    if (!name.isNullOrBlank()) builder.encodedFragment(name.urlSafe())
    val link = builder.toLink("socks${protocolVersionName()}")
    // An explicit empty password distinguishes a literal username from legacy encoded userinfo.
    return if (!username.isNullOrEmpty() && password.isNullOrEmpty()) link.replaceFirst("@", ":@") else link

}

fun SOCKSBean.toV2rayN(): String {

    var link = ""
    if (username.isNotBlank()) {
        link += username.urlSafe() + ":" + password.urlSafe() + "@"
    }
    link += "$serverAddress:$serverPort"
    link = "socks://" + NGUtil.encode(link)
    if (name.isNotBlank()) {
        link += "#" + name.urlSafe()
    }

    return link

}

fun buildSingBoxOutboundSocksBean(bean: SOCKSBean): SingBoxOptions.Outbound_SocksOptions {
    return SingBoxOptions.Outbound_SocksOptions().apply {
        type = "socks"
        server = bean.serverAddress
        server_port = bean.serverPort
        username = bean.username
        password = bean.password
        version = bean.protocolVersionName()
    }
}
