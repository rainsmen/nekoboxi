package io.nekohasekai.sagernet.ui

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections

class ProfileOrderWritesTest {
    @Test fun laterDragWaitsForEarlierWriteAndSurvivesEarlierHandledFailure() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = ProfileOrderWrites(scope)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val writes = Collections.synchronizedList(mutableListOf<Int>())
            val first = queue.submit {
                entered.complete(Unit)
                release.await()
                runCatching { throw IllegalStateException("database error") }
                writes.add(1)
            }
            withTimeout(5000) { entered.await() }
            val second = queue.submit { writes.add(2) }
            assertTrue(writes.isEmpty())
            release.complete(Unit)
            withTimeout(5000) { first.join(); second.join() }
            assertEquals(listOf(1, 2), writes)
        } finally { scope.cancel() }
    }
}
