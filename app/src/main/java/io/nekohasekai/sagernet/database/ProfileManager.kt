package io.nekohasekai.sagernet.database

import android.database.sqlite.SQLiteCantOpenDatabaseException
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import java.io.IOException
import java.sql.SQLException
import java.util.*


object ProfileManager {

    interface Listener {
        suspend fun onAdd(profile: ProxyEntity)
        suspend fun onUpdated(data: TrafficData)
        suspend fun onUpdated(profile: ProxyEntity, noTraffic: Boolean)
        suspend fun onRemoved(groupId: Long, profileId: Long)
    }

    interface RuleListener {
        suspend fun onAdd(rule: RuleEntity)
        suspend fun onUpdated(rule: RuleEntity)
        suspend fun onRemoved(ruleId: Long)
        suspend fun onCleared()
    }

    private val listeners = ArrayList<Listener>()
    private val ruleListeners = ArrayList<RuleListener>()

    suspend fun iterator(what: suspend Listener.() -> Unit) {
        synchronized(listeners) {
            listeners.toList()
        }.forEach { listener ->
            what(listener)
        }
    }

    suspend fun ruleIterator(what: suspend RuleListener.() -> Unit) {
        val ruleListeners = synchronized(ruleListeners) {
            ruleListeners.toList()
        }
        for (listener in ruleListeners) {
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

    fun addListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.add(listener)
        }
    }

    fun removeListener(listener: RuleListener) {
        synchronized(ruleListeners) {
            ruleListeners.remove(listener)
        }
    }

    suspend fun createProfile(groupId: Long, bean: AbstractBean,
                              expectedGeneration: String = ProfileMutations.currentGeneration()): ProxyEntity {
        return createProfiles(groupId, listOf(bean), expectedGeneration).single()
    }

    /** One import owns one restore generation and commits all nodes before notifying the UI. */
    suspend fun createProfiles(groupId: Long, beans: List<AbstractBean>, expectedGeneration: String): List<ProxyEntity> {
        val profiles = beans.map { bean ->
            ProxyEntity(groupId = groupId).putBean(bean.applyDefaultValues().clone())
        }
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            var order = database.proxyDao().nextOrder(groupId) ?: 1
            profiles.forEach { profile ->
                profile.userOrder = order++
                profile.id = database.proxyDao().addProxy(profile)
            }
        }
        profiles.forEach { profile -> iterator { onAdd(profile) } }
        return profiles
    }

    suspend fun updateProfile(profile: ProxyEntity, expected: String?, expectedGeneration: String) {
        val saved = ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            ProfileMutations.updateConfiguration(profile, database, expected)
        }
        iterator { onUpdated(saved, false) }
    }

    suspend fun updateTestResults(profiles: List<ProxyEntity>) {
        profiles.forEach { tested ->
            ProfileMutations.saveTestResult(tested)?.let { saved -> iterator { onUpdated(saved, false) } }
        }
    }

    suspend fun moveProfile(id: Long, groupId: Long, expectedGeneration: String) {
        val saved = ProfileMutations.moveProfile(id, groupId, expectedGeneration)
        iterator { onUpdated(saved, false) }
    }

    suspend fun deleteProfiles(profiles: List<ProxyEntity>, expectedGeneration: String) {
        ProfileMutations.delete(profiles.map { it.id }.toSet(), expectedGeneration = expectedGeneration)
        profiles.forEach { profile -> iterator { onRemoved(profile.groupId, profile.id) } }
        profiles.map { it.groupId }.distinct().forEach(GroupManager::rearrange)
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long, expectedGeneration: String) {
        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return
        deleteProfiles(listOf(profile), expectedGeneration)
    }

    fun getProfile(profileId: Long): ProxyEntity? {
        if (profileId == 0L) return null
        return try {
            SagerDatabase.proxyDao.getById(profileId)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            null
        }
    }

    fun getProfiles(profileIds: List<Long>): List<ProxyEntity> {
        if (profileIds.isEmpty()) return listOf()
        return try {
            SagerDatabase.proxyDao.getEntities(profileIds)
        } catch (ex: SQLiteCantOpenDatabaseException) {
            throw IOException(ex)
        } catch (ex: SQLException) {
            Logs.w(ex)
            listOf()
        }
    }

    // postUpdate: post to listeners, don't change the DB

    suspend fun postUpdate(profileId: Long, noTraffic: Boolean = false) {
        postUpdate(getProfile(profileId) ?: return, noTraffic)
    }

    suspend fun postUpdate(profile: ProxyEntity, noTraffic: Boolean = false) {
        iterator { onUpdated(profile, noTraffic) }
    }

    suspend fun postUpdate(data: TrafficData) {
        iterator { onUpdated(data) }
    }

    suspend fun createRule(rule: RuleEntity, post: Boolean = true,
                           expectedGeneration: String = ProfileMutations.currentGeneration()): RuleEntity {
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            rule.userOrder = database.rulesDao().nextOrder() ?: 1
            rule.id = database.rulesDao().createRule(rule)
        }
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity, expectedGeneration: String) {
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            val current = database.rulesDao().getById(rule.id)
                ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
            rule.userOrder = current.userOrder
            rule.enabled = current.enabled
            database.rulesDao().updateRule(rule)
        }
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long, expectedGeneration: String) {
        ProfileMutations.deleteRules(setOf(ruleId), expectedGeneration)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>, expectedGeneration: String) {
        ProfileMutations.deleteRules(rules.map { it.id }.toSet(), expectedGeneration)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    fun setRuleEnabled(id: Long, enabled: Boolean, expectedGeneration: String) =
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            requireNotNull(database.rulesDao().getById(id)) { "Rule no longer exists" }
            database.rulesDao().updateEnabled(id, enabled)
        }

    fun orderRules(orders: Map<Long, Long>, expectedGeneration: String) =
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            orders.forEach { (id, order) -> database.rulesDao().updateOrder(id, order) }
        }

    fun resetRules(expectedGeneration: String) {
        ProfileMutations.editAtGeneration(expectedGeneration) { database ->
            database.rulesDao().reset()
            insertDefaultRules(database)
        }
        DataStore.rulesFirstCreate = true
    }

    private fun insertDefaultRules(database: SagerDatabase) {
        fun createRuleNoPost(rule: RuleEntity) {
            rule.userOrder = database.rulesDao().nextOrder() ?: 1
            rule.id = database.rulesDao().createRule(rule)
        }
        createRuleNoPost(
            RuleEntity(
                name = app.getString(R.string.route_opt_block_quic),
                port = "443",
                network = "udp",
                outbound = -2
            )
        )
        createRuleNoPost(
            RuleEntity(
                name = app.getString(R.string.route_opt_block_ads),
                domains = "geosite:category-ads-all",
                outbound = -2
            )
        )
        val countries = mutableListOf("cn:中国")
        if (Locale.getDefault().country != Locale.CHINA.country) {
            // 非中文用户
            countries += "ir:Iran"
            countries += "ru:Russia"
        }
        for (c in countries) {
            val country = c.substringBefore(":")
            val displayCountry = c.substringAfter(":")
            if (country == "cn") createRuleNoPost(
                RuleEntity(
                    name = app.getString(R.string.route_play_store, displayCountry),
                    domains = "googleapis.cn",
                )
            )
            createRuleNoPost(
                RuleEntity(
                    name = app.getString(R.string.route_bypass_domain, displayCountry),
                    domains = "geosite:$country",
                    outbound = -1,
                    // bypass mainland China by default
                    enabled = country == "cn"
                )
            )
            createRuleNoPost(
                RuleEntity(
                    name = app.getString(R.string.route_bypass_ip, displayCountry),
                    ip = "geoip:$country",
                    outbound = -1,
                    // bypass mainland China by default
                    enabled = country == "cn"
                )
            )
        }
    }

    fun ensureDefaultRules(): List<RuleEntity> {
        val (rules, initialized) = ProfileMutations.edit {
            val existing = SagerDatabase.rulesDao.allRules()
            if (existing.isNotEmpty() || DataStore.rulesFirstCreate) return@edit existing to false
            insertDefaultRules(SagerDatabase.instance)
            SagerDatabase.rulesDao.allRules() to true
        }
        if (initialized) DataStore.rulesFirstCreate = true
        return rules
    }

    suspend fun getRules(): List<RuleEntity> = ensureDefaultRules()
}
