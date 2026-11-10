package io.nekohasekai.sagernet.bg.proto

class TrafficUpdater(
    private val queryStats: (String, String) -> Long,
    val items: List<TrafficLooperData>, // contain "bypass"
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    constructor(box: libcore.BoxInstance, items: List<TrafficLooperData>) : this(box::queryStats, items)

    class TrafficLooperData(
        // Don't associate proxyEntity
        var tag: String,
        var tx: Long = 0,
        var rx: Long = 0,
        var txBase: Long = 0,
        var rxBase: Long = 0,
        var txRate: Long = 0,
        var rxRate: Long = 0,
        var lastUpdate: Long = 0,
        var ignore: Boolean = false,
    )

    private fun updateOne(item: TrafficLooperData): TrafficLooperData {
        // last update
        val now = clock()
        val interval = now - item.lastUpdate
        item.lastUpdate = now
        // Always drain counters, even when two operations share a clock tick.
        val tx = queryStats(item.tag, "uplink")
        val rx = queryStats(item.tag, "downlink")

        // add diff
        item.rx += rx
        item.tx += tx
        item.rxRate = if (interval > 0) rx * 1000 / interval else 0
        item.txRate = if (interval > 0) tx * 1000 / interval else 0

        // return diff
        return TrafficLooperData(
            tag = item.tag,
            rx = rx,
            tx = tx,
            rxRate = item.rxRate,
            txRate = item.txRate,
        )
    }

    fun updateAll() {
        val updated = mutableMapOf<String, TrafficLooperData>() // diffs
        items.forEach { item ->
            if (item.ignore) return@forEach
            var diff = updated[item.tag]
            // query a tag only once
            if (diff == null) {
                diff = updateOne(item)
                updated[item.tag] = diff
            } else {
                item.rx += diff.rx
                item.tx += diff.tx
                item.rxRate = diff.rxRate
                item.txRate = diff.txRate
            }
        }
    }
}
