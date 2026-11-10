package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.database.DataValidationException.Reason
import io.nekohasekai.sagernet.fmt.internal.ChainBean

object ProfileReferences {
    data class Problem(val key: String, val error: DataValidationException)

    fun problems(profiles: List<ProxyEntity>, groups: List<ProxyGroup>, rules: List<RuleEntity>): List<Problem> {
        val result = mutableListOf<Problem>()
        fun add(reason: Reason, owner: Long, target: Long, vararg details: Any) {
            result += Problem("${reason.name}:$owner:$target", DataValidationException(reason, *details))
        }
        val byId = profiles.associateBy { it.id }
        val groupIds = groups.map { it.id }.toSet()
        profiles.filter { it.groupId !in groupIds }.forEach {
            add(Reason.MISSING_GROUP, it.id, it.groupId, it.displayName(), it.groupId)
        }
        for (group in groups) {
            if (group.frontProxy > 0 && group.frontProxy !in byId)
                add(Reason.MISSING_FRONT, group.id, group.frontProxy, group.name.orEmpty(), group.id, group.frontProxy)
            if (group.landingProxy > 0 && group.landingProxy !in byId)
                add(Reason.MISSING_LANDING, group.id, group.landingProxy, group.name.orEmpty(), group.id, group.landingProxy)
        }
        rules.filter { it.outbound > 0 && it.outbound !in byId }.forEach {
            add(Reason.MISSING_RULE_TARGET, it.id, it.outbound, it.name, it.id, it.outbound)
        }
        val chains = profiles.mapNotNull { p -> (p.requireBean() as? ChainBean)?.let { p.id to it.proxies } }.toMap()
        for ((id, targets) in chains) if (targets.isEmpty()) {
            add(Reason.EMPTY_CHAIN, id, id, byId.getValue(id).displayName())
        }
        for ((id, targets) in chains) for (target in targets) if (target !in byId) {
            add(Reason.MISSING_CHAIN_TARGET, id, target, byId.getValue(id).displayName(), id, target)
        }
        // Iterative traversal also handles malformed backups with deeply nested chains.
        val states = mutableMapOf<Long, Int>()
        for (root in chains.keys.sorted()) {
            if (states[root] == 2) continue
            val stack = ArrayDeque<Pair<Long, Iterator<Long>>>()
            states[root] = 1
            stack.addLast(root to chains.getValue(root).iterator())
            while (stack.isNotEmpty()) {
                val (id, children) = stack.last()
                if (!children.hasNext()) {
                    states[id] = 2
                    stack.removeLast()
                    continue
                }
                val child = children.next()
                if (child !in chains) continue
                when (states[child]) {
                    1 -> add(Reason.CHAIN_CYCLE, id, child, byId.getValue(id).displayName(), id)
                    2 -> Unit
                    else -> {
                        states[child] = 1
                        stack.addLast(child to chains.getValue(child).iterator())
                    }
                }
            }
        }
        return result
    }

    fun requireNoNewProblems(before: List<Problem>, after: List<Problem>) {
        val previous = before.map { it.key }.toSet()
        after.firstOrNull { it.key !in previous }?.let { throw it.error }
    }
}
