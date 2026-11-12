package io.nekohasekai.sagernet.bg

import android.app.Service
import java.io.File
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
class MaintenanceServiceTest {
    @Before fun excludeRemoteRoomServiceFromSingleProcessFixture() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, androidx.room.MultiInstanceInvalidationService::class.java))
    }

    @Test fun stoppedStateDoesNotHideTheShutdownFailureOrKeepAPendingRestart() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        service.data.lastStopError = "statistics could not be persisted"
        service.data.pendingRestart = true
        service.data.binder.stopForMaintenance("test", android.os.Binder())
        assertEquals(BaseService.State.Stopped, service.data.state)
        assertEquals("statistics could not be persisted", service.data.binder.getMaintenanceResult("test"))
        assertFalse(service.data.pendingRestart)
        service.data.state = BaseService.State.Stopping
        assertNull(service.data.binder.getMaintenanceResult("test"))
        service.data.state = BaseService.State.Stopped
        service.data.lastStopError = null
        assertEquals("", service.data.binder.getMaintenanceResult("test"))
        service.data.binder.finishMaintenance("test")
        controller.destroy()
    }

    @Test fun newlyCreatedServiceCannotStartDuringAnotherOwnersMaintenance() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        val file = File(service.createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
        ServiceMaintenanceLock.tryAcquire(file, false)!!.use {
            assertFalse(service.data.maintenanceRequested) // brand new service has no in-memory flag
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
            assertEquals(BaseService.State.Stopped, service.data.state)
            assertNull(service.data.proxy)
            assertNull(service.data.runningLease)
            val shadow = org.robolectric.Shadows.shadowOf(service)
            assertEquals(ServiceNotification.notificationId, shadow.lastForegroundNotificationId)
            assertTrue(shadow.isForegroundStopped)
        }
        controller.destroy()
    }
    @Test fun processExitRequiresSuccessfulStoppedMaintenance() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        assertFalse(service.data.binder.canRestartProcess())
        service.data.maintenanceRequested = true
        service.data.state = BaseService.State.Stopping
        assertFalse(service.data.binder.canRestartProcess())
        service.data.state = BaseService.State.Stopped
        service.data.lastStopError = "write failed"
        assertFalse(service.data.binder.canRestartProcess())
        service.data.lastStopError = null
        assertTrue(service.data.binder.canRestartProcess())
        controller.destroy()
    }

    @Test fun pendingStopRejectionStillCompletesForegroundHandshake() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        try {
            val service = controller.get()
            service.data.lastStopError = "pending write"
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 7))
            val shadow = org.robolectric.Shadows.shadowOf(service)
            assertEquals(ServiceNotification.notificationId, shadow.lastForegroundNotificationId)
            assertTrue(shadow.isForegroundStopped)
            assertEquals(7, shadow.stopSelfId)
            assertEquals("pending write", service.data.lastStopError)
        } finally { controller.destroy() }
    }

    @Test fun finishingAnotherRequestDoesNotReleaseAnActiveMaintenanceOwner() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        try {
            val binder = controller.get().data.binder
            binder.stopForMaintenance("owner", android.os.Binder())
            binder.stopForMaintenance("waiter", android.os.Binder())
            assertEquals("", binder.getMaintenanceResult("owner"))
            binder.finishMaintenance("waiter")
            assertTrue(controller.get().data.maintenanceRequested)
            assertTrue(binder.canRestartProcess())
            binder.finishMaintenance("owner")
            assertFalse(controller.get().data.maintenanceRequested)
        } finally { controller.destroy() }
    }

    @Test fun restartDoesNotWaitForMainAndExpiredOrCanceledRequestsCannotExitLater() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val service = controller.get()
            service.data.maintenanceRequested = true
            assertTrue(service.data.binder.canRestartProcess())
            val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            main.pause()
            executor.submit {
                service.data.binder.restartStoppedProcess("canceled", android.os.SystemClock.elapsedRealtime() + 10_000)
                service.data.binder.cancelRestart("canceled")
                service.data.binder.restartStoppedProcess("expired", android.os.SystemClock.elapsedRealtime() - 1)
            }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            main.idle()
            assertTrue(service.data.binder.canRestartProcess())
        } finally { executor.shutdownNow(); controller.destroy() }
    }

    @Test fun binderMaintenanceDoesNotWaitForABlockedMainThreadOrApplyExpiredStop() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val service = controller.get()
            val main = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
            main.pause()
            // Do not pump the main looper while the background Binder-equivalent thread runs.
            executor.submit {
                service.data.binder.stopForMaintenance("expired", android.os.Binder())
                assertNull(service.data.binder.getMaintenanceResult("expired"))
                service.data.binder.finishMaintenance("expired")
            }.get(2, java.util.concurrent.TimeUnit.SECONDS)
            main.idle()
            assertFalse(service.data.maintenanceRequested)
            assertEquals(BaseService.State.Stopped, service.data.state)
        } finally { executor.shutdownNow(); controller.destroy() }
    }

    private class OwnerBinder : android.os.Binder() {
        private val deaths = mutableSetOf<android.os.IBinder.DeathRecipient>()
        private var alive = true
        override fun isBinderAlive() = alive
        override fun linkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) { deaths += recipient }
        override fun unlinkToDeath(recipient: android.os.IBinder.DeathRecipient, flags: Int) = deaths.remove(recipient)
        fun die() { alive = false; deaths.toList().forEach { it.binderDied() } }
    }

    @Test fun deadMaintenanceOwnerIsReleasedWithoutDestroyingTheBoundService() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        try {
            val service = controller.get()
            val first = OwnerBinder()
            val second = OwnerBinder()
            service.data.binder.stopForMaintenance("first", first)
            service.data.binder.stopForMaintenance("second", second)
            first.die()
            assertTrue(service.data.maintenanceRequested)
            second.die()
            assertFalse(service.data.maintenanceRequested)
            service.data.binder.stopForMaintenance("already-dead", first)
            assertFalse(service.data.maintenanceRequested)
            val live = OwnerBinder()
            service.data.binder.stopForMaintenance("new-owner", live)
            assertEquals("", service.data.binder.getMaintenanceResult("new-owner"))
            service.data.binder.finishMaintenance("new-owner")
            assertFalse(service.data.maintenanceRequested)
        } finally { controller.destroy() }
    }

    @Test fun rejectedVpnPermissionStopsTheColdServiceStart() {
        val previous = io.nekohasekai.sagernet.database.DataStore.serviceMode
        org.robolectric.shadows.ShadowVpnService.setPrepareResult(android.content.Intent("permission"))
        val controller = Robolectric.buildService(VpnService::class.java).create()
        try {
            io.nekohasekai.sagernet.database.DataStore.serviceMode = io.nekohasekai.sagernet.Key.MODE_VPN
            val service = controller.get()
            assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 42))
            assertEquals(42, org.robolectric.Shadows.shadowOf(service).stopSelfId)
            assertNull(service.data.proxy)
            assertEquals(BaseService.State.Stopped, service.data.state)
            val app = org.robolectric.RuntimeEnvironment.getApplication()
            org.robolectric.Shadows.shadowOf(app).clearStartedServices()
            SagerNet.startService()
            assertNull(org.robolectric.Shadows.shadowOf(app).nextStartedService)
        } finally {
            controller.destroy()
            org.robolectric.shadows.ShadowVpnService.setPrepareResult(null)
            io.nekohasekai.sagernet.database.DataStore.serviceMode = previous
        }
    }

}
