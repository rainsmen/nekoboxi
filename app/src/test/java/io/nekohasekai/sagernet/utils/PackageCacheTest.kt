package io.nekohasekai.sagernet.utils

import android.content.pm.ApplicationInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = android.app.Application::class)
class PackageCacheTest {
    private fun snapshot(vararg names: String) = PackageSnapshot(emptyList(), names.map {
        ApplicationInfo().apply { packageName = it; uid = 10001 }
    })
    @Test fun refreshingPublishesWholeSharedUidSnapshotAndKeepsReadersStable() {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        var generation = 0
        val store = PackageSnapshotStore {
            if (generation++ == 0) snapshot("a", "b") else {
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                snapshot("b", "c")
            }
        }
        val old = store.get()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val refresh = executor.submit<PackageSnapshot> { store.refresh() }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            repeat(1000) { assertEquals(setOf("a", "b"), store.get().uidMap[10001]) }
            release.countDown(); refresh.get(5, TimeUnit.SECONDS)
            assertEquals(setOf("b", "c"), store.get().uidMap[10001])
            assertEquals(setOf("a", "b"), old.uidMap[10001])
        } finally { release.countDown(); executor.shutdownNow() }
    }
    @Test fun failedInitialLoadCanRetryAndFailedRefreshKeepsOldSnapshot() {
        var fail = true
        val store = PackageSnapshotStore { if (fail) error("package manager unavailable") else snapshot("a", "b") }
        assertThrows(IllegalStateException::class.java) { store.get() }
        fail = false
        val current = store.get()
        fail = true
        assertThrows(IllegalStateException::class.java) { store.refresh() }
        assertSame(current, store.get())
        assertEquals(setOf("a", "b"), current.uidMap[10001])
    }
}
