package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.backup.BackupRestore

/** Validate the complete deletion before committing any part of a bulk action. */
object ProfileMutations {
    fun currentGeneration(settings: PublicDatabase = PublicDatabase.instance) = BackupRestore.generation(settings)

    /** Keep the epoch with each queued item, including batches that span a restore. */
    data class PendingDeletion<T>(val item: T, val generation: String = currentGeneration())

    fun deletionGeneration(items: List<PendingDeletion<*>>): String =
        items.map { it.generation }.distinct().singleOrNull()
            ?: throw DataValidationException(DataValidationException.Reason.RESTORED_DURING_DELETE)

    private fun requireGeneration(connection: androidx.sqlite.db.SupportSQLiteDatabase, expected: String,
                                  reason: DataValidationException.Reason = DataValidationException.Reason.RESTORED_DURING_DELETE) {
        if (AttachedSettingsTransaction.read(connection, BackupRestore.GENERATION_KEY)?.string.orEmpty() != expected)
            throw DataValidationException(reason)
    }

    fun deleteRules(ids: Set<Long>, expectedGeneration: String,
                    database: SagerDatabase = SagerDatabase.instance,
                    settings: PublicDatabase = PublicDatabase.instance) {
        AttachedSettingsTransaction.run(database, settings) { isolated, connection ->
            requireGeneration(requireNotNull(connection), expectedGeneration)
            ids.forEach { isolated.rulesDao().deleteById(it) }
        }
    }

    /** Every configuration writer shares the same transaction and final-graph validation. */
    fun <T> edit(database: SagerDatabase = SagerDatabase.instance, action: () -> T): T =
        database.runInTransaction(java.util.concurrent.Callable {
            val before = ProfileReferences.problems(database.proxyDao().getAll(),
                database.groupDao().allGroups(), database.rulesDao().allRules())
            val result = action()
            ProfileReferences.requireNoNewProblems(before, ProfileReferences.problems(
                database.proxyDao().getAll(), database.groupDao().allGroups(), database.rulesDao().allRules()))
            result
        })

    /** Check restore identity under the same write lock as the configuration mutation. */
    fun <T> editAtGeneration(expectedGeneration: String,
                             database: SagerDatabase = SagerDatabase.instance,
                             settings: PublicDatabase = PublicDatabase.instance,
                             action: (SagerDatabase) -> T): T =
        AttachedSettingsTransaction.run(database, settings) { isolated, connection ->
            requireGeneration(requireNotNull(connection), expectedGeneration, DataValidationException.Reason.CONFIG_CHANGED)
            edit(isolated) { action(isolated) }
        }

    fun configurationFingerprint(bean: io.nekohasekai.sagernet.fmt.AbstractBean): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(io.nekohasekai.sagernet.fmt.KryoConverters.serialize(bean.clone()))
            .joinToString("") { "%02x".format(it) }

    fun updateConfiguration(profile: ProxyEntity, database: SagerDatabase = SagerDatabase.instance,
                            expected: String? = null): ProxyEntity = edit(database) {
        val current = database.proxyDao().getById(profile.id)
            ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
        if (expected != null && configurationFingerprint(current.requireBean()) != expected)
            throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
        current.putBean(profile.requireBean().clone())
        database.proxyDao().updateProxy(current)
        current
    }

    fun moveProfile(id: Long, groupId: Long, expectedGeneration: String,
                    database: SagerDatabase = SagerDatabase.instance,
                    settings: PublicDatabase = PublicDatabase.instance): ProxyEntity =
        AttachedSettingsTransaction.run(database, settings) { isolated, connection ->
            requireGeneration(requireNotNull(connection), expectedGeneration, DataValidationException.Reason.CONFIG_CHANGED)
            edit(isolated) {
                val current = isolated.proxyDao().getById(id)
                    ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
                current.groupId = groupId
                current.userOrder = isolated.proxyDao().nextOrder(groupId) ?: 1
                isolated.proxyDao().updateProxy(current)
                current
            }
        }

    /** A delayed test may update its own columns only, and only for the configuration it tested. */
    fun saveTestResult(tested: ProxyEntity, database: SagerDatabase = SagerDatabase.instance): ProxyEntity? =
        database.runInTransaction(java.util.concurrent.Callable {
            val current = database.proxyDao().getById(tested.id) ?: return@Callable null
            if (current.requireBean() != tested.requireBean()) return@Callable null
            database.proxyDao().updateTestResult(tested.id, tested.status, tested.ping, tested.error)
            current.apply { status = tested.status; ping = tested.ping; error = tested.error }
        })

    fun clearGroup(groupId: Long, database: SagerDatabase = SagerDatabase.instance,
                   settings: PublicDatabase = PublicDatabase.instance,
                   expectedGeneration: String = currentGeneration(settings)) =
        delete(emptySet(), database = database, settings = settings, clearGroupIds = setOf(groupId),
            expectedGeneration = expectedGeneration)

    fun delete(profileIds: Set<Long>, groupIds: Set<Long> = emptySet(),
               database: SagerDatabase = SagerDatabase.instance,
               settings: PublicDatabase = PublicDatabase.instance,
               clearGroupIds: Set<Long> = emptySet(),
               expectedGeneration: String = currentGeneration(settings)) {
        AttachedSettingsTransaction.run(database, settings) { database, attached ->
            val connection = requireNotNull(attached)
            requireGeneration(connection, expectedGeneration)
            val profiles = database.proxyDao().getAll()
            val groups = database.groupDao().allGroups()
            val rules = database.rulesDao().allRules()
            val removed = profiles.filter { it.id in profileIds || it.groupId in groupIds || it.groupId in clearGroupIds }
            val removedIds = removed.map { it.id }.toSet()
            val remaining = profiles.filter { it.id !in removedIds }
            val remainingGroups = groups.filter { it.id !in groupIds }
            ProfileReferences.requireNoNewProblems(
                ProfileReferences.problems(profiles, groups, rules),
                ProfileReferences.problems(remaining, remainingGroups, rules),
            )
            // Selection cleanup shares the same commit and rollback as the deleted entities.
            for ((key, ids) in listOf(Key.PROFILE_ID to removedIds, Key.PROFILE_CURRENT to removedIds,
                    Key.PROFILE_GROUP to groupIds)) {
                if (AttachedSettingsTransaction.read(connection, key)?.long in ids)
                    connection.execSQL("DELETE FROM ${AttachedSettingsTransaction.TABLE} WHERE `key` = ?", arrayOf(key))
            }
            database.proxyDao().deleteProxy(removed)
            database.groupDao().deleteGroup(groups.filter { it.id in groupIds })
        }
    }
}
