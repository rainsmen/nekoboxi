package io.nekohasekai.sagernet.fmt.tailscale

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxEndpointTailscaleBean(bean: TailscaleBean): SingBoxOptions.Endpoint {
    return SingBoxOptions.Endpoint().apply {
        type = "tailscale"
        tag = "tailscale-ep"
        _hack_config_map["state_directory"] = "tailscale"
        _hack_config_map["domain_resolver"] = "dns-direct"
        if (bean.authKey.isNotBlank()) _hack_config_map["auth_key"] = bean.authKey
        if (bean.controlUrl.isNotBlank()) _hack_config_map["control_url"] = bean.controlUrl
        _hack_config_map["ephemeral"] = bean.ephemeral
        if (bean.exitNode.isNotBlank()) _hack_config_map["exit_node"] = bean.exitNode
        _hack_config_map["exit_node_allow_lan_access"] = bean.exitNodeAllowLanAccess
        _hack_config_map["accept_routes"] = bean.acceptRoutes
        if (bean.advertiseRoutes.isNotBlank()) _hack_config_map["advertise_routes"] = bean.advertiseRoutes.listByLineOrComma()
        if (bean.advertiseTags.isNotBlank()) _hack_config_map["advertise_tags"] = bean.advertiseTags.listByLineOrComma()
        _hack_config_map["advertise_exit_node"] = bean.advertiseExitNode
        if (bean.relayServerPort > 0) _hack_config_map["relay_server_port"] = bean.relayServerPort
    }
}
