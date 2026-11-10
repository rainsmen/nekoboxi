package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.TextView
import androidx.room.MultiInstanceInvalidationService
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.*
import io.nekohasekai.sagernet.bg.proto.TrafficLedger
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.appScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class StopRecoveryUiTest {
    private fun awaitCondition(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue("UI recovery did not finish", check())
    }
    private fun recovery(activity: MainActivity): Snackbar? = MainActivity::class.java
        .getDeclaredField("stopRecoveryMessage").apply { isAccessible = true }.get(activity) as Snackbar?

    @Test fun boundActivityShowsFailureAndItsActionRetriesTheRealService() = verify(false)
    @Test fun recreatedServiceStillShowsAndRecoversTheProcessOwnedFailure() = verify(true)

    private fun verify(recreate: Boolean) {
        val app = RuntimeEnvironment.getApplication()
        val shadowApp = Shadows.shadowOf(app)
        shadowApp.declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        DataStore.serviceMode = Key.MODE_PROXY
        DataStore.profileTrafficStatistics = true
        val database = SagerDatabase.instance
        database.clearAllTables()
        database.groupDao().insert(listOf(ProxyGroup(id = 1, name = "group")))
        database.proxyDao().insert(listOf(ProxyEntity(id = 1, groupId = 1, rx = 10, tx = 20).apply {
            putBean(SOCKSBean().apply { initializeDefaultValues(); name = "node" })
        }))
        var serviceController = Robolectric.buildService(TrafficStopService::class.java).create()
        var service = serviceController.get()
        val counter = java.util.concurrent.atomic.AtomicLong(7)
        val looper = TrafficLooper(service.data, appScope, database)
        looper.prepare(mapOf("node" to listOf(TrafficLedger.Node(1, 10, 20)))) { _, direction ->
            if (direction == "uplink") counter.getAndSet(0) else 0
        }
        service.traffic = looper
        val lock = File(service.createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
        service.data.runningLease = requireNotNull(ServiceMaintenanceLock.tryAcquire(lock, true))
        service.data.state = BaseService.State.Connected
        fun mapService() {
            shadowApp.setComponentNameAndServiceForBindServiceForIntent(
                Intent(app, ProxyService::class.java).setAction(Action.SERVICE),
                ComponentName(app, ProxyService::class.java), service.data.binder)
            shadowApp.setBindServiceCallsOnServiceConnectedDirectly(true)
        }
        mapService()
        var activityController: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        try {
            if (!recreate) activityController = Robolectric.buildActivity(MainActivity::class.java)
                .create(Bundle()).start().resume()
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER ui_traffic_failure BEFORE UPDATE OF tx ON proxy_entities BEGIN SELECT RAISE(ABORT, 'traffic write failed'); END")
            service.data.binder.stopForMaintenance("test", android.os.Binder())
            awaitCondition { service.data.state == BaseService.State.Stopped && ShutdownRecovery.hasPending() }
            service.data.binder.finishMaintenance("test")
            if (recreate) {
                serviceController.destroy()
                serviceController = Robolectric.buildService(TrafficStopService::class.java).create()
                service = serviceController.get()
                mapService()
                activityController = Robolectric.buildActivity(MainActivity::class.java)
                    .create(Bundle()).start().resume()
            }
            val activity = requireNotNull(activityController).get()
            awaitCondition { recovery(activity) != null }
            val initial = requireNotNull(recovery(activity))
            assertEquals(Snackbar.LENGTH_INDEFINITE, initial.duration)
            assertEquals(activity.getString(R.string.retry_save), initial.view
                .findViewById<TextView>(com.google.android.material.R.id.snackbar_action).text.toString())
            initial.view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action).performClick()
            awaitCondition { recovery(activity) !== initial && !service.data.maintenanceRequested }
            assertTrue(ShutdownRecovery.hasPending())
            assertEquals(20L, database.proxyDao().getById(1)!!.tx)
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER ui_traffic_failure")
            requireNotNull(recovery(activity)).view.findViewById<TextView>(com.google.android.material.R.id.snackbar_action).performClick()
            awaitCondition { !ShutdownRecovery.hasPending() && !service.data.maintenanceRequested &&
                service.data.binder.callbackIdMap.values.none { it == 5 } }
            assertEquals(27L, database.proxyDao().getById(1)!!.tx)
            assertTrue(service.data.binder.pendingStopError.isNullOrEmpty())
            assertFalse(service.data.pendingRestart)
            requireNotNull(ServiceMaintenanceLock.tryAcquire(lock, false)).close()
            runBlocking { looper.stop() }
            assertEquals(27L, database.proxyDao().getById(1)!!.tx)
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS ui_traffic_failure")
            runBlocking { ShutdownRecovery.retryAll() }
            activityController?.pause()?.stop()?.destroy()
            serviceController.destroy()
        }
    }
}
