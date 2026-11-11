package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.RemoteException
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher

class SagerConnection(
    private var connectionId: Int,
    private var listenForDeath: Boolean = false
) : ServiceConnection, IBinder.DeathRecipient {

    companion object {
        val serviceClass
            get() = when (DataStore.serviceMode) {
                Key.MODE_PROXY -> ProxyService::class
                Key.MODE_VPN -> VpnService::class
                else -> throw UnknownError()
            }.java

        const val CONNECTION_ID_SHORTCUT = 0
        const val CONNECTION_ID_TILE = 1
        const val CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND = 2
        const val CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND = 3
        const val CONNECTION_ID_RESTART_BG = 4

        var restartingApp = false
    }

    interface Callback {
        // smaller ISagerNetServiceCallback

        fun cbSpeedUpdate(stats: SpeedDisplayData) {}
        fun cbTrafficUpdate(data: TrafficData) {}
        fun cbSelectorUpdate(id: Long) {}

        fun stateChanged(state: BaseService.State, profileName: String?, msg: String?)

        fun missingPlugin(profileName: String, pluginName: String) {}

        fun onServiceConnected(service: ISagerNetService)

        /**
         * Different from Android framework, this method will be called even when you call `detachService`.
         */
        fun onServiceDisconnected() {}
        fun onBinderDied() {}
    }

    private var connectionActive = false
    private var callback: Callback? = null
    private data class Session(val service: ISagerNetService, val binder: IBinder,
                               val callbacks: ISagerNetServiceCallback, val death: IBinder.DeathRecipient)
    @Volatile private var session: Session? = null
    @Volatile var generation = 0L
        private set
    val service: ISagerNetService? get() = session?.service

    private fun callbacks(epoch: Long) = object : ISagerNetServiceCallback.Stub() {
        private fun deliver(action: (Callback) -> Unit) {
            runOnMainDispatcher {
                if (generation == epoch && session != null) callback?.let(action)
            }
        }
        override fun stateChanged(state: Int, profileName: String?, msg: String?) {
            val value = BaseService.State.values().getOrNull(state) ?: return
            deliver { DataStore.serviceState = value; it.stateChanged(value, profileName, msg) }
        }
        override fun cbSpeedUpdate(stats: SpeedDisplayData) = deliver { it.cbSpeedUpdate(stats) }
        override fun cbTrafficUpdate(stats: TrafficData) = deliver { it.cbTrafficUpdate(stats) }
        override fun cbSelectorUpdate(id: Long) = deliver { it.cbSelectorUpdate(id) }
        override fun missingPlugin(profileName: String, pluginName: String) = deliver { it.missingPlugin(profileName, pluginName) }
    }

    /** Invalidate identity before invoking any callback which may synchronously reconnect. */
    private fun clearSession(unregister: Boolean): Session? {
        val old = session
        session = null
        generation++
        if (old != null) {
            if (unregister) try { old.service.unregisterCallback(old.callbacks) } catch (_: RemoteException) { }
            if (listenForDeath) try { old.binder.unlinkToDeath(old.death, 0) } catch (_: NoSuchElementException) { }
        }
        return old
    }

    fun updateConnectionId(id: Int) {
        connectionId = id
        val current = session ?: return
        try { current.service.registerCallback(current.callbacks, id) }
        catch (_: RemoteException) { serviceDied(current.service) }
    }

    override fun onServiceConnected(name: ComponentName?, binder: IBinder) {
        if (!connectionActive) return
        clearSession(true)
        val remote = ISagerNetService.Stub.asInterface(binder) ?: return
        val epoch = generation
        val callbacks = callbacks(epoch)
        val death = IBinder.DeathRecipient { serviceDied(remote) }
        val current = Session(remote, binder, callbacks, death)
        session = current
        try {
            if (listenForDeath) binder.linkToDeath(death, 0)
            remote.registerCallback(callbacks, connectionId)
            if (!binder.isBinderAlive) throw android.os.DeadObjectException()
        } catch (_: RemoteException) {
            serviceDied(remote)
            return
        }
        if (session === current) callback?.onServiceConnected(remote)
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        val old = session ?: return
        // A delayed framework disconnect must not tear down a live replacement binder.
        if (old.binder.isBinderAlive) return
        if (listenForDeath) serviceDied(old.service) else {
            clearSession(false)
            callback?.onServiceDisconnected()
        }
    }

    fun serviceDied(expected: ISagerNetService) {
        runOnMainDispatcher {
            if (session?.service !== expected) return@runOnMainDispatcher
            clearSession(false)
            if (!restartingApp) callback?.onBinderDied()
        }
    }

    override fun binderDied() { service?.let(::serviceDied) }

    fun connect(context: Context, callback: Callback?) {
        if (connectionActive) return
        connectionActive = true
        check(this.callback == null)
        this.callback = callback
        val intent = Intent(context, serviceClass).setAction(Action.SERVICE)
        if (!context.bindService(intent, this, Context.BIND_AUTO_CREATE)) {
            connectionActive = false
            this.callback = null
            callback?.onServiceDisconnected()
        }
    }

    fun disconnect(context: Context) {
        clearSession(true)
        callback = null
        val active = connectionActive
        connectionActive = false
        if (active) try { context.unbindService(this) } catch (_: IllegalArgumentException) { }
    }
}
