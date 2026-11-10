package io.nekohasekai.sagernet.bg

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [QuietNativeLog::class])
class ShutdownCheckpointTest {
    @Test fun blockedSynchronousSaveCannotKeepBroadcastOpenPastDeadline() = runBlocking {
        val scope = CoroutineScope(SupervisorJob())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val calls = AtomicInteger()
        val save = launchShutdownCheckpoint(scope, { calls.incrementAndGet(); finished.countDown() }, 50) {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertTrue(save.isActive)
            assertEquals(1, calls.get())
        } finally { release.countDown(); save.join(); scope.cancel() }
        assertEquals(1, calls.get())
    }

    @Test fun successFailureAndCancelledScopeAllFinishOnce() = runBlocking {
        for (mode in 0..2) {
            val scope = CoroutineScope(SupervisorJob())
            if (mode == 2) scope.cancel()
            val finished = CountDownLatch(1)
            val calls = AtomicInteger()
            launchShutdownCheckpoint(scope, { calls.incrementAndGet(); finished.countDown() }) {
                if (mode == 1) throw java.io.IOException("disk full")
            }.join()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(1, calls.get())
            scope.cancel()
        }
    }
}
