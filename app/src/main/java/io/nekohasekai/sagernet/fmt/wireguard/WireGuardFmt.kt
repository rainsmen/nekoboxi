package io.nekohasekai.sagernet.fmt.wireguard

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

/** Parses "1,2,3" / "[1, 2, 3]" or base64 into the 3 reserved bytes. */
fun parseReserved(anyStr: String): List<Int> {
    val list = anyStr.listByLineOrComma()
    if (list.size == 3) {
        return list.map {
            it.replace("[", "").replace("]", "").trim().toIntOrNull()?.takeIf { b -> b in 0..255 }
                ?: throw IllegalArgumentException("Invalid WireGuard reserved: $anyStr")
        }
    }
    val bytes = try {
        Util.b64Decode(anyStr.trim())
    } catch (e: Exception) {
        throw IllegalArgumentException("Invalid WireGuard reserved: $anyStr")
    }
    if (bytes.size != 3) throw IllegalArgumentException("Invalid WireGuard reserved: $anyStr")
    return bytes.map { it.toInt() and 0xff }
}

private fun toPrefix(address: String): String = when {
    address.contains('/') -> address
    address.contains(':') -> "$address/128"
    else -> "$address/32"
}

/**
 * WireGuard is an endpoint since sing-box 1.11; the outbound form was removed in 1.13.
 * An endpoint is addressed by tag like an outbound (route rules, detour, selector).
 */
fun buildSingBoxEndpointWireguardBean(bean: WireGuardBean, tag: String): SingBoxOptions.Endpoint {
    return SingBoxOptions.Endpoint().apply {
        type = "wireguard"
        this.tag = tag
        _hack_config_map["address"] = bean.localAddress.listByLineOrComma().map(::toPrefix)
        _hack_config_map["private_key"] = bean.privateKey
        if (bean.mtu > 0) _hack_config_map["mtu"] = bean.mtu
        _hack_config_map["peers"] = listOf(mutableMapOf<String, Any>(
            "address" to bean.serverAddress,
            "port" to bean.serverPort,
            "public_key" to bean.peerPublicKey,
            "allowed_ips" to listOf("0.0.0.0/0", "::/0"),
        ).apply {
            if (bean.peerPreSharedKey.isNotBlank()) put("pre_shared_key", bean.peerPreSharedKey)
            if (bean.reserved.isNotBlank()) put("reserved", parseReserved(bean.reserved))
        })
    }
}
