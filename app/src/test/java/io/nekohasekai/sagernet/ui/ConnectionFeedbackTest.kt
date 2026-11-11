package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.content.Intent
import android.os.*
import android.widget.TextView
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.*
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.widget.StatsBar
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConnectionFeedbackTest {
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue("connection feedback did not settle", check())
    }
    @Test fun staleTestAndDelayedConnectedStateCannotOverwriteReplacementOrDisconnection() = verify(false)
    @Test fun destroyedActivityRejectsLateSynchronousBinderResult() = verify(true)

    private fun verify(destroyWhileTesting: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val shadow = Shadows.shadowOf(app)
        shadow.declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        DataStore.serviceMode = Key.MODE_PROXY
        DataStore.rulesFirstCreate = true
        SagerDatabase.instance.clearAllTables()
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "fixture", ungrouped = true)))
        val first = FixtureBinder()
        val component = ComponentName(app, ProxyService::class.java)
        shadow.setComponentNameAndServiceForBindServiceForIntent(Intent(app, ProxyService::class.java).setAction(Action.SERVICE), component, first)
        shadow.setBindServiceCallsOnServiceConnectedDirectly(true)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val returned = CountDownLatch(1)
        first.test = { entered.countDown(); check(release.await(10, TimeUnit.SECONDS)); returned.countDown(); 9999 }
        val controller = Robolectric.buildActivity(MainActivity::class.java).create(Bundle()).start().resume()
        var destroyed = false
        try {
            val activity = controller.get()
            val status = activity.findViewById<TextView>(R.id.status)
            var parent: android.view.ViewParent? = status.parent
            while (parent !is StatsBar) parent = parent!!.parent
            val bar = parent
            await { DataStore.serviceState.connected }
            bar.changeState(BaseService.State.Connected)
            bar.testConnection()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            assertEquals(activity.getString(R.string.connection_test_testing), status.text.toString())
            if (destroyWhileTesting) {
                controller.pause().stop().destroy()
                destroyed = true
                release.countDown()
                assertTrue(returned.await(5, TimeUnit.SECONDS))
                Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
                assertFalse(status.text.contains("9999"))
                assertNull(activity.connection.service)
                return
            }
            val replacement = FixtureBinder().apply { test = { 456 } }
            activity.connection.onServiceConnected(component, replacement)
            await { bar.isEnabled }
            bar.testConnection()
            await { status.text.contains("456") }
            release.countDown()
            assertTrue(returned.await(5, TimeUnit.SECONDS))
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertFalse(status.text.contains("9999"))
            assertTrue(status.text.contains("456"))
            // A pending Connected presentation must not reappear after Idle.
            bar.changeState(BaseService.State.Connected)
            bar.changeState(BaseService.State.Idle)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
            assertEquals(activity.getString(R.string.not_connected), status.text.toString())
            assertFalse(bar.isEnabled)
        } finally { release.countDown(); if (!destroyed) controller.pause().stop().destroy() }
    }
}
