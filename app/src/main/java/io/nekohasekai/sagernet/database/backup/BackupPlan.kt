package io.nekohasekai.sagernet.database.backup

import android.os.Parcel
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject

/** Fully decoded input. Construction never touches either database. */
data class BackupPlan(
    val profiles: List<ProxyEntity>?,
    val groups: List<ProxyGroup>?,
    val rules: List<RuleEntity>?,
    val settings: List<KeyValuePair>?,
    val referenceProfiles: List<ProxyEntity>? = profiles,
    val referenceGroups: List<ProxyGroup>? = groups,
    val referenceIdentities: Map<Long, String> = emptyMap(),
) {
    companion object {
        fun decode(json: JSONObject, profiles: Boolean, rules: Boolean, settings: Boolean): BackupPlan {
            require(json.getInt("version") in 1..2) { "Unsupported backup version" }
            fun <T> read(key: String, create: (Parcel) -> T): List<T> {
                val array = json.getJSONArray(key)
                return List(array.length()) { index ->
                    val bytes = Util.b64Decode(array.getString(index))
                    val parcel = Parcel.obtain()
                    try {
                        parcel.unmarshall(bytes, 0, bytes.size)
                        parcel.setDataPosition(0)
                        io.nekohasekai.sagernet.fmt.KryoConverters.withStrictDecoding { create(parcel) }.also {
                            require(parcel.dataAvail() == 0) { "Unexpected data in $key[$index]" }
                        }
                    } finally {
                        parcel.recycle()
                    }
                }
            }
            // Rules-only imports still need the source identities to verify their outbound IDs.
            val sourceProfiles = if (profiles || (rules && json.has("profiles"))) read("profiles", ProxyEntity.CREATOR::createFromParcel) else null
            val sourceGroups = if (profiles || (rules && json.has("groups"))) read("groups", ProxyGroup.CREATOR::createFromParcel) else null
            return BackupPlan(
                if (profiles) sourceProfiles else null,
                if (profiles) sourceGroups else null,
                if (rules) read("rules", ParcelizeBridge::createRule) else null,
                if (settings) read("settings", KeyValuePair.CREATOR::createFromParcel) else null,
                sourceProfiles, sourceGroups,
                json.optJSONObject("profileIdentities")?.let { identities ->
                    identities.keys().asSequence().associate { it.toLong() to identities.getString(it) }
                }.orEmpty(),
            ).also { it.validateStructure() }
        }
    }

    fun validateStructure() {
        require((profiles == null) == (groups == null)) { "Profiles and groups must be restored together" }
        fun unique(ids: List<Long>, section: String) {
            require(ids.all { it > 0 } && ids.distinct().size == ids.size) { "Invalid or duplicate IDs in $section" }
        }
        profiles?.let { unique(it.map { p -> p.id }, "profiles"); it.forEach { p -> p.requireBean() } }
        groups?.let { unique(it.map { g -> g.id }, "groups") }
        rules?.let { unique(it.map { r -> r.id }, "rules") }
        require(referenceIdentities.all { (id, hash) -> id > 0 && hash.matches(Regex("[0-9a-f]{64}")) }) {
            "Invalid backup reference identities"
        }
        settings?.let { values ->
            require(values.all { it.key.isNotBlank() } && values.map { it.key }.distinct().size == values.size) {
                "Invalid or duplicate settings keys"
            }
            // Force validation of typed values before any persistent change.
            values.forEach { it.validateValue() }
        }
    }

    fun validateReferences(existingProfiles: List<ProxyEntity>, existingGroups: List<ProxyGroup>, existingRules: List<RuleEntity>) {
        if ((profiles == null) != (rules == null)) {
            val retainedRules = rules ?: existingRules
            val fromProfiles = if (profiles == null) referenceProfiles else existingProfiles
            val fromGroups = if (profiles == null) referenceGroups else existingGroups
            val toProfiles = profiles ?: existingProfiles
            val toGroups = groups ?: existingGroups
            fun sameTarget(id: Long): Boolean {
                if (id <= 0) return true
                if (fromProfiles == null || fromGroups == null) return false
                val before = BackupDocument.profileIdentity(id, fromProfiles, fromGroups) ?: return false
                return before == BackupDocument.profileIdentity(id, toProfiles, toGroups)
            }
            for (rule in retainedRules) {
                val matches = if (rule.outbound <= 0) true else if (profiles == null && referenceIdentities.isNotEmpty()) {
                    referenceIdentities[rule.outbound]?.let {
                        it == BackupDocument.profileIdentity(rule.outbound, toProfiles, toGroups)
                    } ?: false
                } else sameTarget(rule.outbound)
                if (!matches) throw DataValidationException(DataValidationException.Reason.UNKNOWN_IDENTITY,
                    rule.name, rule.outbound)
            }
        }
        val finalProfiles = profiles ?: existingProfiles
        val finalGroups = groups ?: existingGroups
        val finalRules = rules ?: existingRules
        ProfileReferences.problems(finalProfiles, finalGroups, finalRules).firstOrNull()?.let {
            val ownerIsImported = when (it.error.reason) {
                DataValidationException.Reason.MISSING_RULE_TARGET -> rules != null
                else -> profiles != null
            }
            it.error.fromBackup = ownerIsImported
            throw it.error
        }
    }
}
