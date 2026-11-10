package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore

/** Validate the complete deletion before committing any part of a bulk action. */
object ProfileMutations {
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

    /** A delayed test may update its own columns only, and only for the configuration it tested. */
    fun saveTestResult(tested: ProxyEntity, database: SagerDatabase = SagerDatabase.instance): ProxyEntity? =
        database.runInTransaction(java.util.concurrent.Callable {
            val current = database.proxyDao().getById(tested.id) ?: return@Callable null
            if (current.requireBean() != tested.requireBean()) return@Callable null
            database.proxyDao().updateTestResult(tested.id, tested.status, tested.ping, tested.error)
            current.apply { status = tested.status; ping = tested.ping; error = tested.error }
        })

    fun delete(profileIds: Set<Long>, groupIds: Set<Long> = emptySet(),
               database: SagerDatabase = SagerDatabase.instance,
               settings: RoomPreferenceDataStore = DataStore.configurationStore) {
        database.runInTransaction {
            val profiles = database.proxyDao().getAll()
            val groups = database.groupDao().allGroups()
            val rules = database.rulesDao().allRules()
            val removed = profiles.filter { it.id in profileIds || it.groupId in groupIds }
            val removedIds = removed.map { it.id }.toSet()
            val remaining = profiles.filter { it.id !in removedIds }
            val remainingGroups = groups.filter { it.id !in groupIds }
            ProfileReferences.requireNoNewProblems(
                ProfileReferences.problems(profiles, groups, rules),
                ProfileReferences.problems(remaining, remainingGroups, rules),
            )
            // Clear settings before deleting. A crash can leave no selection, never a deleted ID.
            if (settings.getLong(Key.PROFILE_ID, 0L) in removedIds) settings.putLong(Key.PROFILE_ID, 0L)
            if (settings.getLong(Key.PROFILE_CURRENT, 0L) in removedIds) settings.putLong(Key.PROFILE_CURRENT, 0L)
            if (settings.getLong(Key.PROFILE_GROUP, 0L) in groupIds) settings.putLong(Key.PROFILE_GROUP, 0L)
            database.proxyDao().deleteProxy(removed)
            database.groupDao().deleteGroup(groups.filter { it.id in groupIds })
        }
    }
}
