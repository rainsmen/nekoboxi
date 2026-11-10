package io.nekohasekai.sagernet.ui

import android.os.Looper
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
class ScanImportTest {
    @org.junit.Before fun excludeRemoteRoomService() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, androidx.room.MultiInstanceInvalidationService::class.java))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("scan import did not complete", condition())
    }
    @Test fun completionWaitsForCommitAndSurvivesAReplacementObserver() {
        val store = ViewModelStore()
        val provider = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())
        val model = provider[ScanImportViewModel::class.java]
        val release = CompletableDeferred<Unit>()
        val commits = AtomicInteger()
        SagerDatabase.instance.clearAllTables()
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "manual")))
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 2, name = "subscription", type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION)))
        DataStore.selectedGroup = 2
        model.parse = { listOf(SOCKSBean().apply { initializeDefaultValues() }) }
        model.commit = { _, beans, _ -> commits.incrementAndGet(); assertEquals(2, beans.size); release.await() }
        try {
            model.start(listOf(suspend { "first" }, suspend { "second" }))
            await { commits.get() == 1 }
            assertTrue(model.state.value.running)
            assertFalse(model.state.value.complete)
            val replacement = ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[ScanImportViewModel::class.java]
            assertSame(model, replacement)
            replacement.start(listOf(suspend { "duplicate callback" }))
            release.complete(Unit)
            await { replacement.state.value.complete }
            assertEquals(1, commits.get())
            assertEquals(2, replacement.state.value.count)
            assertEquals(1L, DataStore.selectedGroup)
            assertNull(replacement.state.value.error)
            model.clearResult()
            model.parse = { throw IllegalArgumentException("entry 2 invalid") }
            model.start(listOf(suspend { "bad" }))
            await { model.state.value.complete }
            assertEquals(1, commits.get())
            assertEquals("entry 2 invalid", model.state.value.error)
        } finally { release.complete(Unit); store.clear() }
    }
    @Test fun subscriptionConfirmationNeverUsesAuthenticationUrlOrTokenAsItsLabel() {
        assertEquals("example.org", SubscriptionSourceLabel.display(null,
            "https://user:secret@example.org/private-token?token=private", "subscription"))
        assertEquals("subscription", SubscriptionSourceLabel.display(null, null, "subscription"))
        assertEquals("My nodes (example.org)", SubscriptionSourceLabel.display("My nodes",
            "https://example.org/path?key=private", "subscription"))
    }
}
