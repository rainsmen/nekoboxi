package io.nekohasekai.sagernet.bg.proto


/** Routing tags own counters; profiles own one historical base and all their tag contributions. */
class TrafficLedger(routes: Map<String, List<Node>>) {
    data class Node(val id: Long, val rx: Long, val tx: Long)
    data class Total(val id: Long, val rx: Long, val tx: Long)
    private val bases = routes.values.flatten().associate { it.id to (it.rx to it.tx) }.toMutableMap()
    private val byProfile = mutableMapOf<Long, MutableList<TrafficUpdater.TrafficLooperData>>()
    private fun entries(tag: String, nodes: List<Node>, ignored: Boolean) = nodes.distinctBy { it.id }.map { node ->
        TrafficUpdater.TrafficLooperData(tag, ignore = ignored).also {
            byProfile.getOrPut(node.id) { mutableListOf() }.add(it)
        }
    }
    private val direct = routes.mapValues { (tag, nodes) -> entries(tag, nodes, false) }
    val items = byProfile.values.flatten()
    val representatives get() = direct.values.mapNotNull { it.firstOrNull() }
    fun totals() = bases.map { (id, base) ->
        val contributions = byProfile[id].orEmpty()
        Total(id, base.first + contributions.sumOf { it.rx }, base.second + contributions.sumOf { it.tx })
    }
    fun clear(ids: Set<Long>) {
        for (id in ids) {
            if (id in bases) bases[id] = 0L to 0L
            byProfile[id]?.forEach { it.rx = 0; it.tx = 0; it.rxRate = 0; it.txRate = 0 }
        }
    }
}
