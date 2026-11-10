package io.nekohasekai.sagernet.database.backup

import io.nekohasekai.sagernet.database.AttachedSettingsTransaction
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.KeyValuePair

/** Both databases use rollback journals; ATTACH makes restoration a single atomic commit. */
object BackupRestore {
    const val GENERATION_KEY = "backupRestoreGeneration"
    fun generation(settings: PublicDatabase) = settings.keyValuePairDao()[GENERATION_KEY]?.string.orEmpty()
    fun restore(plan: BackupPlan, profiles: SagerDatabase = SagerDatabase.instance, settings: PublicDatabase = PublicDatabase.instance) {
        plan.validateStructure()
        AttachedSettingsTransaction.run(profiles, settings) { profiles, connection ->
            val db = requireNotNull(connection)
            plan.validateReferences(profiles.proxyDao().getAll(), profiles.groupDao().allGroups(), profiles.rulesDao().allRules())
            plan.profiles?.let {
                profiles.proxyDao().reset()
                profiles.groupDao().reset()
                profiles.groupDao().insert(plan.groups!!)
                profiles.proxyDao().insert(it)
            }
            plan.rules?.let { profiles.rulesDao().reset(); profiles.rulesDao().insert(it) }
            plan.settings?.let { values ->
                db.execSQL("DELETE FROM ${AttachedSettingsTransaction.TABLE}")
                for (value in values) db.execSQL(
                    "INSERT INTO ${AttachedSettingsTransaction.TABLE} (`key`, valueType, value) VALUES (?, ?, ?)",
                    arrayOf(value.key, value.valueType, value.value),
                )
            }
            // Invalidate subscription downloads that began before this restore.
            val generation = KeyValuePair(GENERATION_KEY).put(java.util.UUID.randomUUID().toString())
            db.execSQL("INSERT OR REPLACE INTO ${AttachedSettingsTransaction.TABLE} (`key`, valueType, value) VALUES (?, ?, ?)",
                arrayOf(generation.key, generation.valueType, generation.value))
            val profileIds = profiles.proxyDao().getAll().map { it.id }.toSet()
            val groupIds = profiles.groupDao().allGroups().map { it.id }.toSet()
            for (key in listOf(Key.PROFILE_ID, Key.PROFILE_GROUP, Key.PROFILE_CURRENT)) {
                val id = db.query("SELECT valueType, value FROM ${AttachedSettingsTransaction.TABLE} WHERE `key` = ?", arrayOf(key)).use { cursor ->
                    if (cursor.moveToFirst()) KeyValuePair(key).apply {
                        valueType = cursor.getInt(0)
                        value = cursor.getBlob(1)
                    }.long else null
                }
                val valid = if (key == Key.PROFILE_GROUP) groupIds else profileIds
                // A partial restore cannot transfer a selection by numeric ID across databases.
                val selectionSourceChanged = (plan.profiles == null) != (plan.settings == null)
                if (selectionSourceChanged || key == Key.PROFILE_CURRENT || id !in valid) {
                    db.execSQL("DELETE FROM ${AttachedSettingsTransaction.TABLE} WHERE `key` = ?", arrayOf(key))
                }
            }
        }
    }
}
