package moe.matsuri.nb4a.proxy.snell

import moe.matsuri.nb4a.SingBoxOptions

fun buildSingBoxOutboundSnellBean(bean: SnellBean): SingBoxOptions.Outbound {
    return SingBoxOptions.Outbound().apply {
        type = "snell"
        _hack_config_map["server"] = bean.serverAddress
        _hack_config_map["server_port"] = bean.serverPort
        _hack_config_map["version"] = bean.version
        _hack_config_map["psk"] = bean.psk
        if (bean.userKey.isNotBlank()) _hack_config_map["userkey"] = bean.userKey
        if (bean.reuse) _hack_config_map["reuse"] = true
        if (bean.version == 6) {
            if (bean.mode.isNotBlank()) _hack_config_map["mode"] = bean.mode
        } else {
            if (bean.obfsMode.isNotBlank() && bean.obfsMode != "none") {
                _hack_config_map["obfs_mode"] = bean.obfsMode
                if (bean.obfsHost.isNotBlank()) _hack_config_map["obfs_host"] = bean.obfsHost
            }
        }
    }
}
