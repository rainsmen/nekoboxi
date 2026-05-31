package io.nekohasekai.sagernet.fmt.tailscale

import moe.matsuri.nb4a.SingBoxOptions
import moe.matsuri.nb4a.utils.listByLineOrComma

fun buildSingBoxEndpointTailscaleBean(bean: TailscaleBean): SingBoxOptions.Endpoint {
    return SingBoxOptions.Endpoint().apply {
        type = "tailscale"
        tag = "tailscale-ep"
        TailscaleOptions = SingBoxOptions.TailscaleEndpointOptions().apply {
            state_directory = "tailscale"
            auth_key = bean.authKey
            control_url = bean.controlUrl
            ephemeral = bean.ephemeral
            exit_node = bean.exitNode
            exit_node_allow_lan_access = bean.exitNodeAllowLanAccess
            accept_routes = bean.acceptRoutes
            if (bean.advertiseRoutes.isNotBlank()) advertise_routes = bean.advertiseRoutes.listByLineOrComma()
            if (bean.advertiseTags.isNotBlank()) advertise_tags = bean.advertiseTags.listByLineOrComma()
            advertise_exit_node = bean.advertiseExitNode
            if (bean.relayServerPort > 0) relay_server_port = bean.relayServerPort
        }
    }
}
