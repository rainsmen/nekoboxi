package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.bg.SubscriptionUpdater
import io.nekohasekai.sagernet.ktx.applyDefaultValues

object GroupManager {

    interface Listener {
        suspend fun groupAdd(group: ProxyGroup)
        suspend fun groupUpdated(group: ProxyGroup)

        suspend fun groupRemoved(groupId: Long)
        suspend fun groupUpdated(groupId: Long)
    }

    interface Interface {
        suspend fun confirm(message: String): Boolean
        suspend fun alert(message: String)
        suspend fun onUpdateSuccess(
            group: ProxyGroup,
            changed: Int,
            added: List<String>,
            updated: Map<String, String>,
            deleted: List<String>,
            duplicate: List<String>,
            byUser: Boolean
        )

        suspend fun onUpdateFailure(group: ProxyGroup, error: Throwable)
    }

    private val listeners = ArrayList<Listener>()
    var userInterface: Interface? = null

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    fun addListener(listener: Listener) {
        synchronized(listeners) {
            listeners.add(listener)
        }
    }

    fun removeListener(listener: Listener) {
        synchronized(listeners) {
            listeners.remove(listener)
        }
    }

    suspend fun clearGroup(groupId: Long, database: SagerDatabase = SagerDatabase.instance,
                           settings: io.nekohasekai.sagernet.database.preference.PublicDatabase = io.nekohasekai.sagernet.database.preference.PublicDatabase.instance,
                           expectedGeneration: String = ProfileMutations.currentGeneration(settings)) {
        ProfileMutations.clearGroup(groupId, database, settings, expectedGeneration)
        iterator { groupUpdated(groupId) }
    }

    fun rearrange(groupId: Long) {
        SagerDatabase.instance.runInTransaction {
            SagerDatabase.proxyDao.getByGroup(groupId).forEachIndexed { index, entity ->
                SagerDatabase.proxyDao.updateOrder(entity.id, groupId, index + 1L)
            }
        }
    }

    suspend fun postUpdate(group: ProxyGroup) {
        iterator { groupUpdated(group) }
    }

    suspend fun postUpdate(groupId: Long) {
        postUpdate(SagerDatabase.groupDao.getById(groupId) ?: return)
    }

    suspend fun postReload(groupId: Long) {
        iterator { groupUpdated(groupId) }
    }

    suspend fun createGroup(group: ProxyGroup,
                            expectedGeneration: String = ProfileMutations.currentGeneration()): ProxyGroup {
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            group.userOrder = database.groupDao().nextOrder() ?: 1
            group.id = database.groupDao().createGroup(group.applyDefaultValues())
        }
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup, expectedGeneration: String) {
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            val current = database.groupDao().getById(group.id)
                ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
            group.userOrder = current.userOrder
            group.ungrouped = current.ungrouped
            val requested = group.subscription
            val previous = current.subscription
            if (current.type == group.type && previous != null && requested != null) {
                val sameSource = previous.link == requested.link
                group.subscription = previous.apply {
                    link = requested.link
                    forceResolve = requested.forceResolve
                    deduplication = requested.deduplication
                    updateWhenConnectedOnly = requested.updateWhenConnectedOnly
                    customUserAgent = requested.customUserAgent
                    autoUpdate = requested.autoUpdate
                    autoUpdateDelay = requested.autoUpdateDelay
                    if (!sameSource) {
                        lastUpdated = 0; lastAttempted = 0L; lastError = ""; subscriptionUserinfo = ""
                        bytesUsed = 0L; bytesRemaining = 0L; expiryDate = 0
                    }
                }
            }
            database.groupDao().updateGroup(group)
        }
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long, expectedGeneration: String) {
        ProfileMutations.delete(emptySet(), setOf(groupId), expectedGeneration = expectedGeneration)
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>, expectedGeneration: String) {
        ProfileMutations.delete(emptySet(), group.map { it.id }.toSet(), expectedGeneration = expectedGeneration)
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}