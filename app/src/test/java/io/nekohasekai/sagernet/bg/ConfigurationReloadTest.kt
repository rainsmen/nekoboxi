package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.ComponentName
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

class ReloadFixtureService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "reload fixture"
    override var wakeLock: android.os.PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null
    override fun acquireWakeLock() = Unit
    override fun createNotification(profileName: String) = ServiceNotification(this, profileName, "service-proxy")
    var stops = 0
    var starts = 0
    var restarting = false
    override fun stopRunner(restart: Boolean, msg: String?) { stops++; restarting = restart }
    override fun startRunner() { starts++ }
    override fun onBind(intent: android.content.Intent) = data.binder
    override fun onStartCommand(intent: android.content.Intent?, flags: Int, startId: Int) =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class, BlockingResetCore::class])
class ConfigurationReloadTest {
    @Test fun reloadRebuildsEvenWhenCurrentNodeBelongsToTheSameSelector() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        val database = SagerDatabase.instance
        database.clearAllTables()
        database.groupDao().insert(listOf(ProxyGroup(id = 1, name = "selector", isSelector = true)))
        val profile = ProxyEntity(id = 1, groupId = 1).apply { putBean(SOCKSBean().apply { initializeDefaultValues() }) }
        database.proxyDao().insert(listOf(profile))
        DataStore.selectProxy(1)
        DataStore.rulesFirstCreate = true
        val controller = Robolectric.buildService(ReloadFixtureService::class.java).create()
        val service = controller.get()
        try {
            service.data.proxy = ProxyInstance(profile, service).apply {
                config = ConfigBuildResult("{}", emptyList(), 1, emptyMap(), mapOf(1L to "profile-1"), 1)
                box = libcore.BoxInstance()
            }
            for (state in listOf(BaseService.State.Connected, BaseService.State.Connecting)) {
                service.data.state = state
                PerAppProxySettings.setMode(true, false)
                service.reload()
                assertTrue(service.restarting)
            }
            assertEquals(2, service.stops)
            service.data.state = BaseService.State.Stopped
            service.reload()
            assertEquals(1, service.starts)
            service.data.maintenanceRequested = true
            service.reload()
            assertEquals(1, service.starts)
        } finally {
            service.data.proxy = null
            service.data.state = BaseService.State.Stopped
            controller.destroy()
        }
    }
}
