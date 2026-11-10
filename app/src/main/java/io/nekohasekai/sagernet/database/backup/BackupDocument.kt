package io.nekohasekai.sagernet.database.backup

import android.os.Parcel
import android.os.Parcelable
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.RuleEntity
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

/** Version 2 preserves rules-only reference identity without exporting node credentials. */
object BackupDocument {
    fun encode(profiles: List<ProxyEntity>, groups: List<ProxyGroup>, rules: List<RuleEntity>,
               settings: List<KeyValuePair>, includeProfiles: Boolean, includeRules: Boolean,
               includeSettings: Boolean): JSONObject = JSONObject().apply {
        put("version", 2)
        fun section(name: String, values: List<Parcelable>) {
            put(name, JSONArray().apply {
                for (value in values) {
                    val parcel = Parcel.obtain()
                    try {
                        value.writeToParcel(parcel, 0)
                        put(Util.b64EncodeUrlSafe(parcel.marshall()))
                    } finally { parcel.recycle() }
                }
            })
        }
        if (includeProfiles) { section("profiles", profiles); section("groups", groups) }
        if (includeRules) {
            section("rules", rules)
            if (!includeProfiles) put("profileIdentities", JSONObject().apply {
                for (id in rules.map { it.outbound }.filter { it > 0 }.distinct()) {
                    profileIdentity(id, profiles, groups)?.let { put(id.toString(), it) }
                }
            })
        }
        if (includeSettings) section("settings", settings)
    }

    fun profileIdentity(id: Long, profiles: List<ProxyEntity>, groups: List<ProxyGroup>): String? {
        val byId = profiles.associateBy { it.id }
        val byGroup = groups.associateBy { it.id }
        val pending = ArrayDeque<Long>().apply { add(id) }
        val visited = sortedSetOf<Long>()
        while (pending.isNotEmpty()) {
            val next = pending.removeFirst()
            if (next <= 0 || !visited.add(next)) continue
            val profile = byId[next] ?: return null
            val group = byGroup[profile.groupId] ?: return null
            (profile.requireBean() as? ChainBean)?.proxies?.forEach(pending::add)
            pending.add(group.frontProxy)
            pending.add(group.landingProxy)
            if (group.isSelector) profiles.filter { it.groupId == group.id }.forEach { pending.add(it.id) }
        }
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            for (next in visited) {
                val profile = byId.getValue(next)
                val group = byGroup.getValue(profile.groupId)
                out.writeLong(next)
                out.writeInt(profile.type)
                out.writeBoolean(group.isSelector)
                out.writeLong(group.frontProxy)
                out.writeLong(group.landingProxy)
                val bean = profile.requireBean().clone().apply { name = "" }
                val encoded = KryoConverters.serialize(bean)
                out.writeInt(encoded.size)
                out.write(encoded)
            }
        }
        return MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
