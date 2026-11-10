package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloseResourcesTest {
    @Test fun shutdownWaitsForPluginCleanupBeforePublishingCompletion() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val exit = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val job = launch {
            closeResources(
                { events += "plugin stop"; entered.complete(Unit); exit.await(); events += "plugin exit" },
                { events += "core closed" },
                { events += "traffic persisted" },
            )
            events += "stopped"
        }
        entered.await()
        assertFalse(job.isCompleted)
        assertEquals(listOf("plugin stop"), events)
        exit.complete(Unit)
        job.join()
        assertEquals(listOf("plugin stop", "plugin exit", "core closed", "traffic persisted", "stopped"), events)
    }

    @Test fun failedTrafficPersistenceStillClosesVpnDescriptorAndRetainsErrors() = runBlocking {
        val traffic = IOException("database unavailable")
        val descriptor = IOException("descriptor close failed")
        var attempted = false
        val result = runCatching {
            closeResources(
                { throw traffic },
                { attempted = true; throw descriptor },
            )
        }
        assertTrue(attempted)
        assertEquals(traffic.message, result.exceptionOrNull()?.message)
        assertEquals(listOf(descriptor), traffic.suppressed.toList())
    }
}
