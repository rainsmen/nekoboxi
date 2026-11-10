package moe.matsuri.nb4a.proxy.config

import io.nekohasekai.sagernet.database.DataValidationException
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import moe.matsuri.nb4a.SingBoxOptions.Endpoint
import moe.matsuri.nb4a.SingBoxOptions.SingBoxOption
import org.json.JSONArray
import org.json.JSONObject

/** Preserve the dialing chain of a subscription outbound independently of display names and IDs. */
object OutboundDependencies {
    private fun invalid(name: String): Nothing = throw DataValidationException(
        DataValidationException.Reason.OUTBOUND_DEPENDENCY, name)

    fun collect(root: JSONObject, candidates: List<JSONObject>, validateImportedDns: Boolean = false): List<JSONObject> {
        fun validateDns(value: JSONObject) {
            if (!validateImportedDns) return
            val resolver = value.opt("domain_resolver")
            val server = when (resolver) {
                is String -> resolver
                is JSONObject -> resolver.optString("server")
                null, JSONObject.NULL -> ""
                else -> invalid("domain_resolver")
            }
            // DNS policies are not part of a node subscription; silently reusing an
            // equal application tag could also change the original resolver's meaning.
            if (server.isNotEmpty()) invalid("domain_resolver: $server")
        }
        validateDns(root)
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf(root.optString("tag")).apply { remove("") }
        var current = root
        while (current.optString("detour").isNotEmpty()) {
            val tag = current.getString("detour")
            if (!seen.add(tag) || seen.size > 128) invalid(root.optString("tag"))
            val next = candidates.filter { it.optString("tag") == tag }.singleOrNull() ?: invalid(tag)
            // Group policies require more than a linear dialing chain; do not silently discard them.
            if (next.optString("type") in listOf("selector", "urltest", "dns", "block")) invalid(tag)
            validateDns(next)
            result += next
            current = next
        }
        return result
    }

    /** Compare the actual linear dialing graph, independently of labels and JSON key order. */
    fun identity(bean: ConfigBean): String {
        val root = JSONObject(bean.config)
        val stored = JSONArray(bean.outboundDependencies.ifBlank { "[]" })
        val sources = listOf(root) + collect(root, (0 until stored.length()).map(stored::getJSONObject))
        fun canonical(value: Any?): String = when (value) {
            is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
                JSONObject.quote(it) + ":" + canonical(value.get(it))
            }
            is JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
            is String -> JSONObject.quote(value)
            null, JSONObject.NULL -> "null"
            else -> value.toString()
        }
        return (if (bean.inheritGlobalTls) "global-tls:" else "") + sources.joinToString(",", "[", "]") {
            it.remove("tag")
            it.remove("detour")
            canonical(it)
        }
    }

    /** A closed validation container: no user inbounds, services, routing or logging. */
    fun validationConfig(bean: ConfigBean): String {
        val root = JSONObject(bean.config)
        val stored = JSONArray(bean.outboundDependencies.ifBlank { "[]" })
        val sources = listOf(root) + collect(root, (0 until stored.length()).map(stored::getJSONObject), validateImportedDns = true)
        val outbounds = JSONArray()
        val endpoints = JSONArray()
        sources.forEachIndexed { index, source ->
            source.put("tag", "import-$index")
            if (index < sources.lastIndex) source.put("detour", "import-${index + 1}")
            (if (isEndpoint(source.optString("type"))) endpoints else outbounds).put(source)
        }
        return JSONObject().put("log", JSONObject().put("disabled", true))
            .put("dns", JSONObject().put("servers", JSONArray().put(JSONObject().put("type", "local").put("tag", "import-dns"))))
            .put("route", JSONObject().put("default_domain_resolver", "import-dns"))
            .put("outbounds", outbounds).put("endpoints", endpoints).toString()
    }

    fun isEndpoint(type: String) = type in setOf("wireguard", "tailscale")

    fun build(bean: ConfigBean, tag: String, globalAllowInsecure: Boolean? = null): List<SingBoxOption> {
        val root = JSONObject(bean.config)
        val stored = JSONArray(bean.outboundDependencies.ifBlank { "[]" })
        val dependencies = collect(root, (0 until stored.length()).map(stored::getJSONObject))
        val sources = listOf(root) + dependencies
        return sources.mapIndexed { index, source ->
            if (bean.inheritGlobalTls && source.optString("type") in
                setOf("http", "vmess", "vless", "trojan", "hysteria", "hysteria2", "tuic")) {
                source.optJSONObject("tls")?.let { tls ->
                    if (!tls.has("insecure")) tls.put("insecure", globalAllowInsecure ?: io.nekohasekai.sagernet.database.DataStore.globalAllowInsecure)
                }
            }
            val custom = CustomSingBoxOption(source.toString())
            val option: SingBoxOption = if (isEndpoint(source.optString("type")))
                Endpoint().apply { _hack_config_map.putAll(custom.basicMap) } else custom
            option.apply {
                _hack_config_map["tag"] = if (index == 0) tag else "$tag-dependency-$index"
                if (index < sources.lastIndex) _hack_config_map["detour"] = "$tag-dependency-${index + 1}"
            }
        }
    }
}
