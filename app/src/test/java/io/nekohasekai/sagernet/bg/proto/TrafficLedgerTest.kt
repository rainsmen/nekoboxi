package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.fmt.TAG_PROXY
import org.junit.Assert.*
import org.junit.Test

class TrafficLedgerTest {
    @Test fun sharedNodeAccumulatesAllRoutesWithOneHistoryAndCanBeCleared() {
        val shared = TrafficLedger.Node(3, 1000, 2000)
        val ledger = TrafficLedger(mapOf(
            "first" to listOf(TrafficLedger.Node(1, 0, 0), shared),
            "second" to listOf(TrafficLedger.Node(2, 0, 0), shared),
        ))
        val counters = mutableMapOf("first" to 100L, "second" to 200L)
        val updater = TrafficUpdater({ tag, direction ->
            if (direction == "downlink") counters.remove(tag) ?: 0L else 0
        }, ledger.items)
        updater.updateAll()
        assertEquals(1300L, ledger.totals().single { it.id == 3L }.rx)
        assertEquals(2000L, ledger.totals().single { it.id == 3L }.tx)
        assertEquals(300L, ledger.representatives.sumOf { it.rx })
        ledger.clear(setOf(3))
        counters["second"] = 7
        updater.updateAll()
        assertEquals(7L, ledger.totals().single { it.id == 3L }.rx)
        assertEquals(207L, ledger.totals().single { it.id == 2L }.rx)
    }
    @Test fun selectorAndExplicitMemberRoutesRemainDistinctAcrossSwitches() {
        val ledger = TrafficLedger(mapOf(
            "a" to listOf(TrafficLedger.Node(1, 10, 0), TrafficLedger.Node(3, 100, 0)),
            "b" to listOf(TrafficLedger.Node(2, 20, 0), TrafficLedger.Node(3, 100, 0)),
        ))
        val counters = mutableMapOf("a" to 40L, "b" to 5L)
        val updater = TrafficUpdater({ tag, direction ->
            if (direction == "downlink") counters.remove(tag) ?: 0L else 0
        }, ledger.items)
        updater.updateAll()
        counters["b"] = 60; updater.updateAll()
        val totals = ledger.totals().associate { it.id to it.rx }
        assertEquals(50L, totals[1]); assertEquals(85L, totals[2]); assertEquals(205L, totals[3])
        assertEquals(105L, ledger.representatives.sumOf { it.rx })
    }
}
