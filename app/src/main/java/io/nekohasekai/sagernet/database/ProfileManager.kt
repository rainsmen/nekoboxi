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

    suspend fun createProfile(groupId: Long, bean: AbstractBean): ProxyEntity {
        bean.applyDefaultValues()

        val profile = ProxyEntity(groupId = groupId).apply {
            id = 0
            putBean(bean)
            userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
        }
        ProfileMutations.edit {
            profile.userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
            profile.id = SagerDatabase.proxyDao.addProxy(profile)
        }
        iterator { onAdd(profile) }
        return profile
    }

    suspend fun updateProfile(profile: ProxyEntity, expected: String? = null) {
        val saved = ProfileMutations.updateConfiguration(profile, expected = expected)
        iterator { onUpdated(saved, false) }
    }

    suspend fun updateTestResults(profiles: List<ProxyEntity>) {
        profiles.forEach { tested ->
            ProfileMutations.saveTestResult(tested)?.let { saved -> iterator { onUpdated(saved, false) } }
        }
    }

    suspend fun moveProfile(id: Long, groupId: Long) {
        val saved = ProfileMutations.edit {
            val current = SagerDatabase.proxyDao.getById(id)
                ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
            current.groupId = groupId
            current.userOrder = SagerDatabase.proxyDao.nextOrder(groupId) ?: 1
            SagerDatabase.proxyDao.updateProxy(current)
            current
        }
        iterator { onUpdated(saved, false) }
    }

    suspend fun deleteProfiles(profiles: List<ProxyEntity>) {
        ProfileMutations.delete(profiles.map { it.id }.toSet())
        profiles.forEach { profile -> iterator { onRemoved(profile.groupId, profile.id) } }
        profiles.map { it.groupId }.distinct().forEach(GroupManager::rearrange)
    }

    suspend fun deleteProfile(groupId: Long, profileId: Long) {
        val profile = SagerDatabase.proxyDao.getById(profileId) ?: return
        deleteProfiles(listOf(profile))
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

    suspend fun createRule(rule: RuleEntity, post: Boolean = true): RuleEntity {
        ProfileMutations.edit {
            rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
            rule.id = SagerDatabase.rulesDao.createRule(rule)
        }
        if (post) {
            ruleIterator { onAdd(rule) }
        }
        return rule
    }

    suspend fun updateRule(rule: RuleEntity) {
        ProfileMutations.edit {
            val current = SagerDatabase.rulesDao.getById(rule.id)
                ?: throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
            rule.userOrder = current.userOrder
            rule.enabled = current.enabled
            SagerDatabase.rulesDao.updateRule(rule)
        }
        ruleIterator { onUpdated(rule) }
    }

    suspend fun deleteRule(ruleId: Long) {
        SagerDatabase.rulesDao.deleteById(ruleId)
        ruleIterator { onRemoved(ruleId) }
    }

    suspend fun deleteRules(rules: List<RuleEntity>) {
        SagerDatabase.rulesDao.deleteRules(rules)
        ruleIterator {
            rules.forEach {
                onRemoved(it.id)
            }
        }
    }

    private fun createRuleNoPost(rule: RuleEntity): RuleEntity {
        ProfileMutations.edit {
            rule.userOrder = SagerDatabase.rulesDao.nextOrder() ?: 1
            rule.id = SagerDatabase.rulesDao.createRule(rule)
        }
        return rule
    }

    fun ensureDefaultRules(): List<RuleEntity> {
        var rules = SagerDatabase.rulesDao.allRules()
        if (rules.isEmpty() && !DataStore.rulesFirstCreate) {
            DataStore.rulesFirstCreate = true
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
            val fuckedCountry = mutableListOf("cn:中国")
            if (Locale.getDefault().country != Locale.CHINA.country) {
                // 非中文用户
                fuckedCountry += "ir:Iran"
                fuckedCountry += "ru:Russia"
            }
            for (c in fuckedCountry) {
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
            rules = SagerDatabase.rulesDao.allRules()
        }
        return rules
    }

    suspend fun getRules(): List<RuleEntity> {
        return ensureDefaultRules()
    }

}
