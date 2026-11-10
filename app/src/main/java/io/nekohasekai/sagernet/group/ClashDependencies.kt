package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.database.DataValidationException
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.hysteria.*
import io.nekohasekai.sagernet.fmt.shadowsocks.*
import io.nekohasekai.sagernet.fmt.socks.*
import io.nekohasekai.sagernet.fmt.tuic.*
import io.nekohasekai.sagernet.fmt.v2ray.*
import io.nekohasekai.sagernet.fmt.wireguard.*
import moe.matsuri.nb4a.proxy.anytls.*
import moe.matsuri.nb4a.proxy.snell.*
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import org.json.JSONArray
import org.json.JSONObject

/** Convert a named, linear Clash dialing chain into a self-contained core node. */
internal object ClashDependencies {
    fun expand(entries: List<Map<String, Any?>>, beans: List<AbstractBean>): List<AbstractBean> {
        check(entries.size == beans.size)
        fun invalid(name: String): Nothing = throw DataValidationException(
            DataValidationException.Reason.OUTBOUND_DEPENDENCY, name)
        fun target(index: Int): String? {
            val value = entries[index]["dialer-proxy"] ?: entries[index]["dialer_proxy"] ?: return null
            return (value as? String)?.takeIf { it.isNotBlank() } ?: invalid("dialer-proxy")
        }
        val names = entries.indices.groupBy { entries[it]["name"] as? String }
        fun node(index: Int): JSONObject {
            val bean = beans[index]
            val option = when (bean) {
                is StandardV2RayBean -> buildSingBoxOutboundStandardV2RayBean(bean, globalAllowInsecure = false)
                is SOCKSBean -> buildSingBoxOutboundSocksBean(bean)
                is ShadowsocksBean -> buildSingBoxOutboundShadowsocksBean(bean)
                is HysteriaBean -> buildSingBoxOutboundHysteriaBean(bean, globalAllowInsecure = false)
                is TuicBean -> buildSingBoxOutboundTuicBean(bean, globalAllowInsecure = false)
                is WireGuardBean -> buildSingBoxEndpointWireguardBean(bean, "clash-$index")
                is AnyTLSBean -> buildSingBoxOutboundAnyTLSBean(bean)
                is SnellBean -> buildSingBoxOutboundSnellBean(bean)
                else -> invalid(bean.name)
            }
            ProxyEntity().putBean(bean).singMux()?.takeIf { it.enabled }?.let {
                option._hack_config_map["multiplex"] = it.asMap()
            }
            option._hack_custom_config = bean.customOutboundJson
            return JSONObject(gson.toJson(option.asMap())).put("tag", "clash-$index").apply {
                // Absence means inherit at runtime. Explicit source/custom true or false wins.
                val customTls = bean.customOutboundJson.takeIf { it.isNotBlank() }
                    ?.let(::JSONObject)?.optJSONObject("tls")
                val followsGlobal = bean is StandardV2RayBean || bean is HysteriaBean || bean is TuicBean
                optJSONObject("tls")?.let { tls ->
                    if (followsGlobal && !tls.optBoolean("insecure") && customTls?.has("insecure") != true &&
                        !entries[index].containsKey("skip-cert-verify"))
                        tls.remove("insecure")
                }
            }
        }
        return beans.mapIndexed { index, bean ->
            if (target(index) == null) return@mapIndexed bean
            val chain = mutableListOf<Int>()
            var current = index
            while (true) {
                if (current in chain || chain.size >= 128) invalid(bean.name)
                chain += current
                val name = target(current) ?: break
                current = names[name]?.singleOrNull() ?: invalid(name)
            }
            val nodes = chain.mapIndexed { position, source -> node(source).apply {
                if (position < chain.lastIndex) put("detour", "clash-${chain[position + 1]}")
            } }
            ConfigBean().apply {
                initializeDefaultValues()
                name = bean.name
                type = 1
                inheritGlobalTls = true
                config = nodes.first().toString()
                outboundDependencies = JSONArray(nodes.drop(1)).toString()
            }
        }
    }
}
