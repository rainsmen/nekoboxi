package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.*
import android.widget.Toast
import androidx.core.content.ContextCompat
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.BootReceiver
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.ISagerNetServiceCallback
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.plugin.PluginManager
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.utils.Util
import java.net.UnknownHostException

class BaseService {

    enum class State(
        val canStop: Boolean = false,
        val started: Boolean = false,
        val connected: Boolean = false,
    ) {
        /**
         * Idle state is only used by UI and will never be returned by BaseService.
         */
        Idle, Connecting(true, true, false), Connected(true, true, true), Stopping, Stopped,
    }

    interface ExpectedException

    class Data internal constructor(val service: Interface) {
        @Volatile var state = State.Stopped
        var proxy: ProxyInstance? = null
        var notification: ServiceNotification? = null
        var pendingRestart = false
        var maintenanceRequested = false
        @Volatile var lastStopError: String? = null
        var runningLease: ServiceMaintenanceLock.Lease? = null

        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SHUTDOWN -> {
                    val pending = goAsync()
                    launchShutdownCheckpoint(appScope, { pending?.finish() }) { service.persistStats() }
                }
                Action.RELOAD -> service.reload()
                // Action.SWITCH_WAKE_LOCK -> runOnDefaultDispatcher { service.switchWakeLock() }
                PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && proxy?.isInitialized() == true) {
                        if (SagerNet.power.isDeviceIdleMode) {
                            proxy?.box?.sleep()
                        } else {
                            proxy?.box?.wake()
                            if (DataStore.wakeResetConnections) {
                                service.requestAutomaticConnectionReset()
                            }
                        }
                    }
                }

                Action.RESET_UPSTREAM_CONNECTIONS -> runOnDefaultDispatcher {
                    val error = runCatching { Libcore.resetAllConnections(true) }.exceptionOrNull()
                    runOnMainDispatcher {
                        Util.collapseStatusBar(ctx)
                        Toast.makeText(ctx, if (error == null) ctx.getString(R.string.connections_reset_done)
                            else ctx.getString(R.string.connections_reset_failed, error.readableMessage), Toast.LENGTH_SHORT).show()
                    }
                }

                else -> service.stopRunner()
            }
            }
        }
        var closeReceiverRegistered = false

        val binder = Binder(this)
        var connectingJob: Job? = null

        fun changeState(s: State, msg: String? = null) {
            if (state == s && msg == null) return
            state = s
            DataStore.serviceState = s
            binder.stateChanged(s, msg)
        }
    }

    class Binder(@Volatile private var data: Data? = null) : ISagerNetService.Stub(), CoroutineScope,
        AutoCloseable {
        private val callbacks = object : RemoteCallbackList<ISagerNetServiceCallback>() {
            override fun onCallbackDied(callback: ISagerNetServiceCallback?, cookie: Any?) {
                super.onCallbackDied(callback, cookie)
            }
        }

        val callbackIdMap = mutableMapOf<ISagerNetServiceCallback, Int>()

        override val coroutineContext = Dispatchers.Main.immediate + Job()

        override fun getState(): Int = (data?.state ?: State.Idle).ordinal
        override fun getProfileName(): String = data?.proxy?.displayProfileName ?: "Idle"

        override fun registerCallback(cb: ISagerNetServiceCallback, id: Int) {
            if (!callbackIdMap.contains(cb)) {
                callbacks.register(cb)
            }
            callbackIdMap[cb] = id
        }

        private val broadcastMutex = Mutex()

        suspend fun broadcast(work: (ISagerNetServiceCallback) -> Unit) {
            broadcastMutex.withLock {
                val count = callbacks.beginBroadcast()
                try {
                    repeat(count) {
                        try {
                            work(callbacks.getBroadcastItem(it))
                        } catch (_: RemoteException) {
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    callbacks.finishBroadcast()
                }
            }
        }

        override fun unregisterCallback(cb: ISagerNetServiceCallback) {
            callbackIdMap.remove(cb)
            callbacks.unregister(cb)
        }

        override fun getPendingStopError(): String? =
            ShutdownRecovery.failure()?.readableMessage ?: data?.lastStopError

        private class MaintenanceRequest(val owner: IBinder) {
            lateinit var death: IBinder.DeathRecipient
            val result = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val refreshPending = java.util.concurrent.atomic.AtomicBoolean(false)
        }
        private val maintenanceRequests = java.util.concurrent.ConcurrentHashMap<String, MaintenanceRequest>()

        override fun stopForMaintenance(requestId: String, owner: IBinder) {
            val request = MaintenanceRequest(owner)
            request.death = IBinder.DeathRecipient { finishMaintenance(requestId) }
            if (maintenanceRequests.putIfAbsent(requestId, request) != null) return
            try {
                owner.linkToDeath(request.death, 0)
                if (!owner.isBinderAlive || maintenanceRequests[requestId] !== request) {
                    runCatching { owner.unlinkToDeath(request.death, 0) }
                    finishMaintenance(requestId)
                    return
                }
            } catch (_: RemoteException) {
                finishMaintenance(requestId)
                return
            }
            launch {
                if (maintenanceRequests[requestId] !== request) return@launch
                val current = data
                if (current == null) request.result.set("Service is unavailable")
                else {
                    current.maintenanceRequested = true
                    current.service.stopRunner()
                }
            }
        }

        // Binder threads never wait for the service main thread. A blocked main thread
        // leaves the result pending and the caller's timeout remains effective.
        override fun getMaintenanceResult(requestId: String): String? {
            val request = maintenanceRequests[requestId] ?: return null
            if (request.refreshPending.compareAndSet(false, true)) launch {
                try {
                    if (maintenanceRequests[requestId] !== request) return@launch
                    val current = data
                    request.result.set(when {
                        current == null -> "Service is unavailable"
                        current.state != State.Stopped -> null
                        else -> (ShutdownRecovery.failure()?.readableMessage ?: current.lastStopError).orEmpty()
                    })
                } finally { request.refreshPending.set(false) }
            }
            return request.result.get()
        }

        override fun finishMaintenance(requestId: String) {
            maintenanceRequests.remove(requestId)?.let { request ->
                runCatching { request.owner.unlinkToDeath(request.death, 0) }
            }
            launch { data?.maintenanceRequested = maintenanceRequests.isNotEmpty() }
        }

        internal fun canRestartProcess(): Boolean = data?.let {
            it.maintenanceRequested && it.state == State.Stopped && it.lastStopError == null && it.runningLease == null && !ShutdownRecovery.hasPending()
        } == true

        private val restartRequests = java.util.concurrent.ConcurrentHashMap<String, Long>()

        override fun restartStoppedProcess(requestId: String, deadlineElapsedRealtime: Long) {
            restartRequests[requestId] = deadlineElapsedRealtime
            launch {
                if (restartRequests.remove(requestId) != deadlineElapsedRealtime ||
                    android.os.SystemClock.elapsedRealtime() >= deadlineElapsedRealtime || !canRestartProcess()) return@launch
                Runtime.getRuntime().exit(0)
            }
        }

        override fun cancelRestart(requestId: String) { restartRequests.remove(requestId) }

        override fun clearTraffic(groupId: Long): Boolean = runBlocking {
            val current = withContext(Dispatchers.Main.immediate) {
                data?.takeIf { !it.maintenanceRequested && !ShutdownRecovery.hasPending() &&
                    it.lastStopError == null && (it.state == State.Stopped || it.state == State.Connected) }
            } ?: return@runBlocking false
            val looper = current.proxy?.looper
            if (looper != null) looper.clearTraffic(groupId)
            else withContext(Dispatchers.IO) {
                val context = current.service as Context
                val file = java.io.File(context.createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
                val lease = ServiceMaintenanceLock.tryAcquire(file, shared = false) ?: return@withContext false
                lease.use { SagerDatabase.proxyDao.clearTraffic(groupId) }
                true
            }
        }

        override fun urlTest(): Int {
            if (data?.proxy?.isInitialized() != true) {
                error("core not started")
            }
            try {
                return Libcore.urlTest(
                    data!!.proxy!!.box, DataStore.connectionTestURL, 3000
                )
            } catch (e: Exception) {
                error(Protocols.genFriendlyMsg(e.readableMessage))
            }
        }

        fun stateChanged(s: State, msg: String?) = launch {
            val profileName = profileName
            broadcast { it.stateChanged(s.ordinal, profileName, msg) }
        }

        fun missingPlugin(pluginName: String) = launch {
            val profileName = profileName
            broadcast { it.missingPlugin(profileName, pluginName) }
        }

        override fun close() {
            maintenanceRequests.keys.toList().forEach(::finishMaintenance)
            restartRequests.clear()
            callbacks.kill()
            cancel()
            data = null
        }
    }

    interface Interface {
        val data: Data
        val tag: String
        fun createNotification(profileName: String): ServiceNotification

        fun onBind(intent: Intent): IBinder? =
            if (intent.action == Action.SERVICE) data.binder else null

        fun reload() {
            if (DataStore.selectedProxy == 0L) {
                stopRunner(false, (this as Context).getString(R.string.profile_empty))
                return
            }
            if (data.maintenanceRequested) return
            if (canReloadSelector()) {
                val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
                val tag = data.proxy!!.config.profileTagMap[ent?.id] ?: ""
                if (tag.isNotBlank() && ent != null) {
                    // select from GUI
                    data.proxy!!.box.selectOutbound(tag)
                    // or select from webui
                    // => selector_OnProxySelected
                }
                return
            }
            val s = data.state
            when {
                s == State.Stopped -> startRunner()
                s.canStop -> stopRunner(true)
                else -> Logs.w("Illegal state $s when invoking use")
            }
        }

        fun canReloadSelector(): Boolean {
            val proxy = data.proxy ?: return false
            if (data.state != State.Connected || !proxy.isInitialized()) return false
            if (proxy.config.selectorGroupId < 0) return false
            val ent = SagerDatabase.proxyDao.getById(DataStore.selectedProxy) ?: return false
            val tmpBox = ProxyInstance(ent)
            tmpBox.buildConfigTmp()
            if (tmpBox.lastSelectorGroupId == data.proxy?.lastSelectorGroupId) {
                return true
            }
            return false
        }

        suspend fun startProcesses() {
            data.proxy!!.launch()
        }

        fun startRunner() {
            if (data.maintenanceRequested) return
            this as Context
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, javaClass))
            else startService(Intent(this, javaClass))
        }

        suspend fun killProcesses(): Unit = closeResources(
            { data.proxy?.closeAndWait() },
            { try { wakeLock?.release() } finally { wakeLock = null } },
            { DefaultNetworkListener.stop(this@Interface) },
        )

        fun stopRunner(restart: Boolean = false, msg: String? = null) {
            DataStore.baseService = null
            DataStore.vpnService = null

            if (!restart) data.pendingRestart = false
            if (data.state == State.Stopping || (data.state == State.Stopped && !ShutdownRecovery.hasPending())) return
            data.pendingRestart = restart && !data.maintenanceRequested
            this as Service

            data.lastStopError = null
            data.changeState(State.Stopping)

            runOnMainDispatcher {
                data.connectingJob?.cancelAndJoin() // ensure stop connecting first
                // we use a coroutineScope here to allow clean-up in parallel
                var stopError: Throwable? = null
                coroutineScope {
                    suspend fun finishOwnStop() {
                        killProcesses()
                        data.runningLease?.close()
                        data.runningLease = null
                        data.proxy = null
                        data.lastStopError = null
                    }
                    val retryingOwnStop = ShutdownRecovery.contains(data)
                    try {
                        closeResources(
                            { ShutdownRecovery.retryAll() },
                            {
                                if (!retryingOwnStop) try { finishOwnStop() } catch (error: Throwable) {
                                    ShutdownRecovery.retain(data, error) { finishOwnStop() }
                                    throw error
                                }
                            },
                        )
                    } catch (error: Exception) {
                        stopError = error
                        Logs.w(error)
                        data.pendingRestart = false
                    } finally {
                        data.notification?.destroy()
                        data.notification = null
                    }
                    val data = data
                    if (data.closeReceiverRegistered) {
                        unregisterReceiver(data.receiver)
                        data.closeReceiverRegistered = false
                    }
                }

                data.lastStopError = stopError?.readableMessage
                // Failed cleanup and its lease remain process-owned until a maintenance retry succeeds.
                data.changeState(State.Stopped, msg ?: data.lastStopError)
                // stop the service if nothing has bound to it
                val shouldRestart = data.pendingRestart && !data.maintenanceRequested
                data.pendingRestart = false
                if (shouldRestart) startRunner() else {
                    stopSelf()
                }
            }
        }

        suspend fun persistStats() {
            data.proxy?.looper?.checkpoint()
        }

        // networks
        var upstreamInterfaceName: String?

        // Native close unregisters this listener synchronously. Never wait for the native
        // lifecycle lock on the network actor or an Android broadcast thread.
        fun requestAutomaticConnectionReset() {
            val owner = data.proxy?.takeIf { it.isInitialized() } ?: return
            val core = owner.box
            runOnDefaultDispatcher {
                if (data.state == State.Connected && data.proxy === owner) {
                    runCatching { core.resetConnections() }.onFailure { Logs.w(it) }
                }
            }
        }

        suspend fun preInit() {
            DefaultNetworkListener.start(this) {
                SagerNet.underlyingNetwork = it
                DataStore.vpnService?.updateUnderlyingNetwork()
                if (it == null) upstreamInterfaceName = null
                it?.let { network -> SagerNet.connectivity.getLinkProperties(network) }?.also { link ->
                    //
                    val oldName = upstreamInterfaceName
                    if (oldName != link.interfaceName) {
                        upstreamInterfaceName = link.interfaceName
                    }
                    if (oldName != null && upstreamInterfaceName != null && oldName != upstreamInterfaceName) {
                        Logs.d("Network changed: $oldName -> $upstreamInterfaceName")
                        if (DataStore.networkChangeResetConnections) {
                            requestAutomaticConnectionReset()
                        }
                    }
                }
            }
        }

        var wakeLock: PowerManager.WakeLock?
        fun acquireWakeLock()

        suspend fun lateInit() {
            wakeLock?.apply {
                release()
                wakeLock = null
            }

            if (DataStore.acquireWakeLock) {
                acquireWakeLock()
                data.notification?.postNotificationWakeLockStatus(true)
            } else {
                data.notification?.postNotificationWakeLockStatus(false)
            }
        }

        private fun rejectStart(startId: Int): Int {
            if (data.state == State.Stopped) {
                data.notification?.destroy()
                data.notification = null
            }
            (this as Service).stopSelf(startId)
            return Service.START_NOT_STICKY
        }

        fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            this as Service
            // Satisfy the system handshake before database access or any rejected-start return.
            if (data.notification == null) data.notification = createNotification(getString(R.string.service_preparing))
            if (data.maintenanceRequested) return rejectStart(startId)
            val data = data
            if (data.state != State.Stopped) return Service.START_NOT_STICKY
            this as Context
            if (data.lastStopError != null || ShutdownRecovery.hasPending()) return rejectStart(startId)
            val lockFile = java.io.File(createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
            data.runningLease = ServiceMaintenanceLock.tryAcquire(lockFile, shared = true)
            if (data.runningLease == null) return rejectStart(startId)
            DataStore.baseService = this
            data.changeState(State.Connecting)
            val profile = SagerDatabase.proxyDao.getById(DataStore.selectedProxy)
            if (profile == null) {
                stopRunner(false, getString(R.string.profile_empty))
                return Service.START_NOT_STICKY
            }

            val proxy = ProxyInstance(profile, this)
            data.proxy = proxy
            BootReceiver.enabled = DataStore.persistAcrossReboot
            if (!data.closeReceiverRegistered) {
                val filter = IntentFilter().apply {
                    addAction(Action.RELOAD)
                    addAction(Intent.ACTION_SHUTDOWN)
                    addAction(Action.CLOSE)
                    // addAction(Action.SWITCH_WAKE_LOCK)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
                    }
                    addAction(Action.RESET_UPSTREAM_CONNECTIONS)
                }
                // Exported for the UI process and system idle broadcasts; senders
                // need the signature-level "$packageName.SERVICE" permission.
                ContextCompat.registerReceiver(
                    this,
                    data.receiver,
                    filter,
                    "$packageName.SERVICE",
                    null,
                    ContextCompat.RECEIVER_EXPORTED
                )
                data.closeReceiverRegistered = true
            }

            data.changeState(State.Connecting)
            data.connectingJob = appScope.launch(Dispatchers.Main, start = CoroutineStart.LAZY) {
                try {
                    data.notification?.postNotificationTitle(ServiceNotification.genTitle(profile))

                    Executable.killAll()    // clean up old processes
                    preInit()
                    proxy.init()
                    DataStore.currentProfile = profile.id

                    proxy.processes = GuardedProcessPool {
                        Logs.w(it)
                        stopRunner(false, it.readableMessage)
                    }

                    startProcesses()
                    data.changeState(State.Connected)
                    if (proxy.startupWarnings.isNotEmpty()) {
                        Toast.makeText(this@Interface, proxy.startupWarnings.take(3).joinToString("\n"),
                            Toast.LENGTH_LONG).show()
                    }

                    lateInit()
                } catch (_: CancellationException) { // if the job was cancelled, it is canceller's responsibility to call stopRunner
                } catch (_: UnknownHostException) {
                    stopRunner(false, getString(R.string.invalid_server))
                } catch (e: PluginManager.PluginNotFoundException) {
                    Toast.makeText(this@Interface, e.readableMessage, Toast.LENGTH_SHORT).show()
                    Logs.w(e)
                    data.binder.missingPlugin(e.plugin)
                    stopRunner(false, null)
                } catch (exc: Throwable) {
                    if (exc.javaClass.name.endsWith("proxyerror")) {
                        // error from golang
                        Logs.w(exc.readableMessage)
                    } else {
                        Logs.w(exc)
                    }
                    stopRunner(
                        false, "${getString(R.string.service_failed)}: ${exc.readableMessage}"
                    )
                } finally {
                    data.connectingJob = null
                }
            }
            data.connectingJob?.start()
            return Service.START_NOT_STICKY
        }
    }

}