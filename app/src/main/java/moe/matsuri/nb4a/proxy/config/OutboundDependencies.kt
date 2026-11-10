package moe.matsuri.nb4a.proxy.config

import io.nekohasekai.sagernet.database.DataValidationException
import moe.matsuri.nb4a.SingBoxOptions.CustomSingBoxOption
import org.json.JSONArray
import org.json.JSONObject

/** Preserve the dialing chain of a subscription outbound independently of display names and IDs. */
object OutboundDependencies {
    private fun invalid(name: String): Nothing = throw DataValidationException(
        DataValidationException.Reason.OUTBOUND_DEPENDENCY, name)

    fun collect(root: JSONObject, candidates: List<JSONObject>): List<JSONObject> {
        val result = mutableListOf<JSONObject>()
        val seen = mutableSetOf(root.optString("tag")).apply { remove("") }
        var current = root
        while (current.optString("detour").isNotEmpty()) {
            val tag = current.getString("detour")
            if (!seen.add(tag) || seen.size > 128) invalid(root.optString("tag"))
            val next = candidates.filter { it.optString("tag") == tag }.singleOrNull() ?: invalid(tag)
            // Group policies require more than a linear dialing chain; do not silently discard them.
            if (next.optString("type") in listOf("selector", "urltest", "dns", "block")) invalid(tag)
            result += next
            current = next
        }
        return result
    }

    fun build(bean: ConfigBean, tag: String): List<CustomSingBoxOption> {
        val root = JSONObject(bean.config)
        val stored = JSONArray(bean.outboundDependencies.ifBlank { "[]" })
        val dependencies = collect(root, (0 until stored.length()).map(stored::getJSONObject))
        val sources = listOf(root) + dependencies
        return sources.mapIndexed { index, source ->
            CustomSingBoxOption(source.toString()).apply {
                _hack_config_map["tag"] = if (index == 0) tag else "$tag-dependency-$index"
                if (index < sources.lastIndex) _hack_config_map["detour"] = "$tag-dependency-${index + 1}"
            }
        }
    }
}
