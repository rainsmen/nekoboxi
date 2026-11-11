package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.os.*
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.aidl.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.fmt.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

internal open class FixtureBinder : ISagerNetService.Stub() {
    lateinit var callbacks: ISagerNetServiceCallback
    var rejectRegistration = false
    var alive = true
    override fun isBinderAlive() = alive
    var test: () -> Int = { 123 }
    override fun getState() = BaseService.State.Connected.ordinal
    override fun getProfileName() = "fixture"
    override fun getPendingStopError(): String? = null
    override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
        if (rejectRegistration) throw DeadObjectException()
        callbacks = cb
    }
    override fun unregisterCallback(cb: ISagerNetServiceCallback) = Unit
    override fun urlTest() = test()
    override fun stopForMaintenance(requestId: String, owner: IBinder) = Unit
    override fun getMaintenanceResult(requestId: String) = ""
    override fun finishMaintenance(requestId: String) = Unit
    override fun restartStoppedProcess(requestId: String, deadline: Long) = Unit
    override fun cancelRestart(requestId: String) = Unit
    override fun clearTraffic(groupId: Long) = false
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
class SagerConnectionTest {
    @Test fun failedRegistrationNeverPublishesConnectedAndOldCallbacksCannotAffectReplacement() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, androidx.room.MultiInstanceInvalidationService::class.java))
        val context = object : android.content.ContextWrapper(app) {
            override fun bindService(intent: android.content.Intent, connection: android.content.ServiceConnection, flags: Int) = true
            override fun unbindService(connection: android.content.ServiceConnection) = Unit
        }
        DataStore.serviceMode = Key.MODE_PROXY
        val component = ComponentName(app, ProxyService::class.java)
        Shadows.shadowOf(app).declareComponentUnbindable(component)
        val connection = SagerConnection(2, true)
        var connected = 0; var deaths = 0; var states = 0
        connection.connect(context, object : SagerConnection.Callback {
            override fun onServiceConnected(service: ISagerNetService) { connected++ }
            override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) { states++ }
            override fun missingPlugin(profileName: String, pluginName: String) = Unit
            override fun onBinderDied() { deaths++; assertNull(connection.service) }
        })
        try {
            val failed = FixtureBinder().apply { rejectRegistration = true }
            connection.onServiceConnected(component, failed)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, connected); assertEquals(1, deaths); assertNull(connection.service)
            val a = FixtureBinder(); val b = FixtureBinder()
            connection.onServiceConnected(component, a)
            val oldCallbacks = a.callbacks
            a.alive = false
            connection.onServiceDisconnected(component)
            connection.serviceDied(a)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(2, deaths)
            connection.onServiceConnected(component, b)
            DataStore.serviceState = BaseService.State.Connected
            oldCallbacks.stateChanged(BaseService.State.Stopped.ordinal, "old", null)
            connection.serviceDied(a)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertSame(b, connection.service)
            assertEquals(BaseService.State.Connected, DataStore.serviceState)
            assertEquals(0, states)
            b.callbacks.stateChanged(BaseService.State.Stopped.ordinal, "current", null)
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, states)
            assertEquals(BaseService.State.Stopped, DataStore.serviceState)
        } finally { connection.disconnect(context) }
    }
}
