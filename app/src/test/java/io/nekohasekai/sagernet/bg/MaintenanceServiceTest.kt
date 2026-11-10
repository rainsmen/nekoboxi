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
        service.data.binder.stopForMaintenance()
        assertEquals(BaseService.State.Stopped, service.data.state)
        assertEquals("statistics could not be persisted", service.data.binder.maintenanceResult)
        assertFalse(service.data.pendingRestart)
        service.data.state = BaseService.State.Stopping
        assertNull(service.data.binder.maintenanceResult)
        service.data.state = BaseService.State.Stopped
        service.data.lastStopError = null
        assertEquals("", service.data.binder.maintenanceResult)
        service.data.binder.finishMaintenance()
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

    @Test fun selectorReloadDoesNotInspectUninitializedProxy() {
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        val bean = io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply { initializeDefaultValues() }
        val profile = io.nekohasekai.sagernet.database.ProxyEntity(id = 1, groupId = 1).apply { putBean(bean) }
        service.data.proxy = io.nekohasekai.sagernet.bg.proto.ProxyInstance(profile)
        service.data.state = BaseService.State.Connecting
        assertFalse(service.canReloadSelector())
        service.data.state = BaseService.State.Connected
        assertFalse(service.canReloadSelector())
        service.data.proxy = null
        service.data.state = BaseService.State.Stopped
        controller.destroy()
    }

}
