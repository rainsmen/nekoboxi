package io.nekohasekai.sagernet.bg

import android.app.Application
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [QuietNativeLog::class])
class PluginShutdownRetryTest {
    private class DelayedProcess : Process() {
        @Volatile var alive = true
        var forced = 0
        private val exited = CountDownLatch(1)
        fun exit() { alive = false; exited.countDown() }
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun getInputStream() = ByteArrayInputStream(byteArrayOf())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun waitFor(): Int { exited.await(); return 0 }
        override fun waitFor(timeout: Long, unit: TimeUnit) = !alive
        override fun exitValue(): Int { if (alive) throw IllegalThreadStateException(); return 0 }
        override fun destroy() = Unit
        override fun destroyForcibly(): Process { forced++; return this }
        override fun isAlive() = alive
    }
    @Test fun timedOutPluginCanExitLaterAndReleaseTheRetainedStop() = runBlocking {
        val process = DelayedProcess()
        val pool = GuardedProcessPool(processFactory = { _, _ -> process }, gracefulStopMillis = 1,
            forcedStopMillis = 1, dispatcher = Dispatchers.Unconfined) { fail("unexpected fatal callback") }
        pool.start(listOf("fake-plugin"))
        try {
            val first = runCatching { pool.closeAndWait() }.exceptionOrNull()
            assertTrue(first is IOException)
            assertEquals(1, process.forced)
            val owner = Any()
            var released = false
            ShutdownRecovery.retain(owner, first!!) { pool.closeAndWait(); released = true }
            try {
                assertTrue(runCatching { ShutdownRecovery.retryAll() }.isFailure)
                assertTrue(ShutdownRecovery.contains(owner))
                assertFalse(released)
                process.exit()
                ShutdownRecovery.retryAll()
                assertFalse(ShutdownRecovery.contains(owner))
                assertTrue(released)
                pool.closeAndWait()
            } finally { process.exit(); ShutdownRecovery.retryAll() }
        } finally { process.exit(); pool.closeAndWait() }
    }
}
