package io.nekohasekai.sagernet.bg.proto

import org.junit.Assert.*
import org.junit.Test

class TrafficUpdaterTest {
    @Test fun sameTickDrainsCountersAndNeverReturnsCumulativeValuesAsDeltas() {
        var remaining = 10L
        val first = TrafficUpdater.TrafficLooperData("shared", rx = 100)
        val second = TrafficUpdater.TrafficLooperData("shared", rx = 200)
        val updater = TrafficUpdater({ _, direction ->
            if (direction == "downlink") remaining.also { remaining = 0 } else 0L
        }, listOf(first, second), { 100L })
        updater.updateAll()
        remaining = 7
        updater.updateAll() // same millisecond, including two profiles sharing one core counter
        assertEquals(117L, first.rx)
        assertEquals(217L, second.rx)
        assertEquals(0L, remaining)
        first.rx = 0; second.rx = 0 // clearing after the forced drain
        updater.updateAll()
        assertEquals(0L, first.rx)
        assertEquals(0L, second.rx)
    }
}
