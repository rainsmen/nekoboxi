package io.nekohasekai.sagernet.group

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.ProfileReferences
import io.nekohasekai.sagernet.database.DataValidationException
import io.nekohasekai.sagernet.database.DataValidationException.Reason
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.database.backup.BackupRestore
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.AttachedSettingsTransaction

/** A successful download is not sufficient: only a nonempty parsed snapshot can replace nodes. */
object SubscriptionUpdate {
    data class Result(val changed: Int, val added: List<String>, val updated: Map<String, String>, val deleted: List<String>)

    private fun requestSettings(value: io.nekohasekai.sagernet.database.SubscriptionBean?) = value?.let {
        listOf(it.type, it.link, it.customUserAgent, it.forceResolve, it.deduplication, it.updateWhenConnectedOnly)
    }

    fun recordFailure(database: SagerDatabase, request: ProxyGroup, message: String,
        expectedGeneration: String? = null, settings: PublicDatabase? = null) {
        if (expectedGeneration != null) requireNotNull(settings)
        AttachedSettingsTransaction.run(database, settings) { database, connection ->
            if (expectedGeneration != null && connection?.let {
                AttachedSettingsTransaction.read(it, BackupRestore.GENERATION_KEY)?.string
            }.orEmpty() != expectedGeneration) return@run
            val latest = database.groupDao().getById(request.id) ?: return@run
            if (latest.type != request.type || requestSettings(latest.subscription) != requestSettings(request.subscription))
                return@run
            latest.subscription?.apply {
                lastAttempted = System.currentTimeMillis()
                lastError = io.nekohasekai.sagernet.ktx.LogRedactor.redact(message).take(2000)
            }
            database.groupDao().updateGroup(latest)
        }
    }

    fun commit(database: SagerDatabase, group: ProxyGroup, beans: List<AbstractBean>,
        expectedGeneration: String? = null, settings: PublicDatabase? = null, checkActive: () -> Unit = {}): Result {
        if (beans.isEmpty()) throw DataValidationException(Reason.EMPTY_SUBSCRIPTION)
        if (expectedGeneration != null) requireNotNull(settings)
        return AttachedSettingsTransaction.run(database, settings) { database, transactionDb ->
            checkActive()
            fun setting(key: String) = transactionDb?.let { AttachedSettingsTransaction.read(it, key) }
            if (expectedGeneration != null && setting(BackupRestore.GENERATION_KEY)?.string.orEmpty() != expectedGeneration)
                throw DataValidationException(Reason.RESTORED_DURING_UPDATE)
            val currentGroup = database.groupDao().getById(group.id) ?: throw DataValidationException(Reason.GROUP_REMOVED)
            if (currentGroup.type != group.type || requestSettings(currentGroup.subscription) != requestSettings(group.subscription))
                throw DataValidationException(Reason.SUBSCRIPTION_CHANGED)
            val previousProblems = ProfileReferences.problems(
                database.proxyDao().getAll(), database.groupDao().allGroups(), database.rulesDao().allRules())
            val existing = database.proxyDao().getByGroup(group.id)
            val byName = existing.groupBy { it.displayName() }
            val unmatched = existing.toMutableList()
            fun normalized(bean: AbstractBean) = bean.clone().apply {
                customOutboundJson = ""; customConfigJson = ""
                if (this is moe.matsuri.nb4a.proxy.config.ConfigBean && type == 1) {
                    // Old manually edited entries may be incomplete; never invent an identity for them.
                    runCatching { moe.matsuri.nb4a.proxy.config.OutboundDependencies.identity(this) }
                        .onSuccess { config = it; outboundDependencies = "" }
                }
            }
            // Names synthesized from subscription order are not identities. Resolve every
            // duplicate-name family by content before assigning unique display names.
            val rawNames = beans.map { it.displayName() }
            val families = rawNames.distinct().associateWith { name ->
                val numbered = Regex("""${Regex.escape(name)} \([0-9]+\)""")
                existing.filter { it.displayName() == name || numbered.matches(it.displayName()) }
            }
            val duplicateIndices = beans.indices.filter { i ->
                rawNames.count { it == rawNames[i] } > 1 || byName[rawNames[i]].orEmpty().size > 1
            }.toSet()
            val matched = arrayOfNulls<ProxyEntity>(beans.size)
            // Resolve unique content globally before names: two nodes can exchange names.
            for (index in beans.indices) {
                val content = normalized(beans[index])
                val candidates = unmatched.filter { normalized(it.requireBean()) == content }
                if (candidates.size == 1 && beans.count { normalized(it) == content } == 1) {
                    matched[index] = candidates.single()
                    unmatched.remove(candidates.single())
                }
            }
            for (index in duplicateIndices.filter { matched[it] == null }) {
                val candidates = families.getValue(rawNames[index]).filter {
                    normalized(it.requireBean()) == normalized(beans[index])
                }
                if (candidates.size > 1 || (candidates.size == 1 && beans.indices.count {
                    rawNames[it] == rawNames[index] && normalized(beans[it]) == normalized(beans[index])
                } > 1)) throw DataValidationException(Reason.AMBIGUOUS_NODE, rawNames[index])
                candidates.singleOrNull()?.let {
                    if (!unmatched.remove(it)) throw DataValidationException(Reason.AMBIGUOUS_NODE, rawNames[index])
                    matched[index] = it
                }
            }
            for (index in beans.indices.filter { it !in duplicateIndices && matched[it] == null }) {
                val sameName = byName[rawNames[index]].orEmpty().filter { it in unmatched }
                val directMatch = sameName.singleOrNull()
                val renamed = if (directMatch == null || normalized(directMatch.requireBean()) != normalized(beans[index])) unmatched.filter {
                    it.displayName() !in rawNames && normalized(it.requireBean()) == normalized(beans[index])
                }.singleOrNull()?.takeIf { candidate ->
                    beans.count { normalized(it) == normalized(candidate.requireBean()) } == 1
                } else null
                matched[index] = renamed ?: directMatch
                matched[index]?.let { unmatched.remove(it) }
            }
            for (index in duplicateIndices) {
                if (matched[index] == null && families.getValue(rawNames[index]).any { it in unmatched })
                    throw DataValidationException(Reason.AMBIGUOUS_NODE, rawNames[index])
            }
            val named = beans.map { it.clone() }
            val usedNames = mutableSetOf<String>()
            fun nameInput(index: Int, preferred: String) {
                var name = preferred
                var suffix = 0
                while (!usedNames.add(name)) { suffix++; name = "${rawNames[index]} ($suffix)" }
                named[index].name = name
            }
            beans.indices.filter { it !in duplicateIndices }.forEach { nameInput(it, rawNames[it]) }
            duplicateIndices.filter { matched[it] != null }.forEach { nameInput(it, matched[it]!!.displayName()) }
            duplicateIndices.filter { matched[it] == null }.forEach { nameInput(it, rawNames[it]) }
            val added = mutableListOf<String>()
            val updated = linkedMapOf<String, String>()
            val updates = mutableListOf<ProxyEntity>()
            var changed = 0
            named.forEachIndexed { index, bean ->
                val name = bean.displayName()
                val entity = matched[index]
                val order = index + 1L
                if (entity == null) {
                    database.proxyDao().addProxy(ProxyEntity(groupId = group.id, userOrder = order).apply { putBean(bean) })
                    added.add(name)
                    changed++
                } else {
                    val previous = entity.requireBean()
                    bean.customOutboundJson = previous.customOutboundJson
                    bean.customConfigJson = previous.customConfigJson
                    val oldName = entity.displayName()
                    val configChanged = previous != bean || oldName != name
                    val orderChanged = entity.userOrder != order
                    if (configChanged || orderChanged) {
                        entity.putBean(bean)
                        entity.userOrder = order
                        updates.add(entity)
                    }
                    if (configChanged || orderChanged) { changed++; updated[oldName] = name }
                }
            }
            database.proxyDao().updateProxy(updates)
            val removed = unmatched
            val selected = setting(Key.PROFILE_ID)?.long
            if (removed.any { it.id == selected }) throw DataValidationException(Reason.SELECTED_NODE_REMOVED)
            val removedIds = removed.map { it.id }.toSet()
            ProfileReferences.requireNoNewProblems(previousProblems, ProfileReferences.problems(
                database.proxyDao().getAll().filter { it.id !in removedIds },
                database.groupDao().allGroups(), database.rulesDao().allRules()))
            changed += removed.size
            currentGroup.subscription?.apply {
                subscriptionUserinfo = group.subscription?.subscriptionUserinfo.orEmpty()
                lastUpdated = (System.currentTimeMillis() / 1000).toInt()
                lastAttempted = System.currentTimeMillis()
                lastError = ""
            }
            if (currentGroup.name?.startsWith("Subscription #") == true) currentGroup.name = group.name
            database.groupDao().updateGroup(currentGroup)
            // Validate all reference and metadata changes before clearing cross-database state.
            if (setting(Key.PROFILE_CURRENT)?.long in removedIds)
                transactionDb?.execSQL("DELETE FROM ${AttachedSettingsTransaction.TABLE} WHERE `key` = ?", arrayOf(Key.PROFILE_CURRENT))
            database.proxyDao().deleteProxy(removed)
            checkActive()
            Result(changed, added, updated, removed.map { it.displayName() })
        }
    }
}
