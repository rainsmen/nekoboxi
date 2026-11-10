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

    suspend fun clearGroup(groupId: Long) {
        ProfileMutations.delete(SagerDatabase.proxyDao.getByGroup(groupId).map { it.id }.toSet())
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

    suspend fun createGroup(group: ProxyGroup): ProxyGroup {
        ProfileMutations.edit {
            group.userOrder = SagerDatabase.groupDao.nextOrder() ?: 1
            group.id = SagerDatabase.groupDao.createGroup(group.applyDefaultValues())
        }
        iterator { groupAdd(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
        return group
    }

    suspend fun updateGroup(group: ProxyGroup) {
        ProfileMutations.edit {
            val current = SagerDatabase.groupDao.getById(group.id)
                ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
            group.userOrder = current.userOrder
            if (current.type == group.type && current.subscription?.link == group.subscription?.link) {
                group.subscription?.apply {
                    lastUpdated = current.subscription?.lastUpdated ?: 0
                    subscriptionUserinfo = current.subscription?.subscriptionUserinfo.orEmpty()
                }
            }
            SagerDatabase.groupDao.updateGroup(group)
        }
        iterator { groupUpdated(group) }
        if (group.type == GroupType.SUBSCRIPTION) {
            SubscriptionUpdater.reconfigureUpdater()
        }
    }

    suspend fun deleteGroup(groupId: Long) {
        ProfileMutations.delete(emptySet(), setOf(groupId))
        iterator { groupRemoved(groupId) }
        SubscriptionUpdater.reconfigureUpdater()
    }

    suspend fun deleteGroup(group: List<ProxyGroup>) {
        ProfileMutations.delete(emptySet(), group.map { it.id }.toSet())
        for (proxyGroup in group) iterator { groupRemoved(proxyGroup.id) }
        SubscriptionUpdater.reconfigureUpdater()
    }

}