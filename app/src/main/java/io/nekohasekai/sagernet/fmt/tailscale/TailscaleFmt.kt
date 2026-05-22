package io.nekohasekai.sagernet.fmt.tailscale

import moe.matsuri.nb4a.SingBoxOptions

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
        }
    }
}
