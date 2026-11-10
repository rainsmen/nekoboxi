package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficData
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.TAG_BYPASS
import io.nekohasekai.sagernet.fmt.TAG_PROXY
import kotlinx.coroutines.*

/** Sampling, selection, clearing and persistence share one lock; IPC uses immutable snapshots. */
class TrafficLooper(val data: BaseService.Data, private val sc: CoroutineScope,
                    private val database: SagerDatabase = SagerDatabase.instance) {
    private var job: Job? = null
    private var stopped = false
    private var updater: TrafficUpdater? = null
    private var ledger: TrafficLedger? = null
    private val bypass = TrafficUpdater.TrafficLooperData(tag = TAG_BYPASS)

    @Synchronized
    fun prepare(proxy: ProxyInstance) {
        val routes = proxy.config.trafficMap.mapValues { (_, entities) ->
            entities.map { TrafficLedger.Node(it.id, it.rx, it.tx) }
        }
        prepare(routes, proxy.box::queryStats)
        proxy.box.setV2rayStats((routes.keys + TAG_PROXY + TAG_BYPASS).joinToString("\n"))
    }

    @Synchronized
    internal fun prepare(routes: Map<String, List<TrafficLedger.Node>>, queryStats: (String, String) -> Long) {
        check(updater == null)
        val ledger = TrafficLedger(routes)
        this.ledger = ledger
        updater = TrafficUpdater(queryStats, ledger.items + bypass)
    }

    fun start() { job = sc.launch { loop() } }

    suspend fun stop() {
        withContext(Dispatchers.IO) { synchronized(this@TrafficLooper) { stopped = true } }
        job?.cancelAndJoin()
        val traffic = withContext(Dispatchers.IO) {
            synchronized(this@TrafficLooper) {
                updater?.updateAll()
                if (!DataStore.profileTrafficStatistics) emptyList() else snapshotTraffic().also(::persist)
            }
        }
        data.binder.broadcast { callback -> traffic.forEach(callback::cbTrafficUpdate) }
    }

    private fun persist(values: List<TrafficData>) = database.runInTransaction {
        values.forEach { database.proxyDao().updateTraffic(it.id, it.rx, it.tx) }
    }

    suspend fun clearTraffic(groupId: Long): Boolean = withContext(Dispatchers.IO) {
        synchronized(this@TrafficLooper) {
            if (stopped) return@synchronized false
            updater?.updateAll()
            val ids = database.runInTransaction(java.util.concurrent.Callable {
                database.proxyDao().getByGroup(groupId).map { it.id }.toSet().also {
                    database.proxyDao().clearTraffic(groupId)
                }
            })
            ledger?.clear(ids)
            data.proxy?.config?.trafficMap?.values?.flatten()?.filter { it.id in ids }?.forEach {
                it.rx = 0; it.tx = 0
            }
            true
        }
    }

    suspend fun selectMain(id: Long) = withContext(Dispatchers.IO) {
        synchronized(this@TrafficLooper) {
            if (stopped) return@synchronized
            val proxy = data.proxy ?: return@synchronized
            if (!proxy.isInitialized() || proxy.config.selectorGroupId < 0) return@synchronized
            if (id !in proxy.config.profileTagMap) return@synchronized
            updater?.updateAll()
            proxy.config.mainEntId = id
            if (DataStore.profileTrafficStatistics) persist(snapshotTraffic())
        }
    }

    private data class Snapshot(val speed: SpeedDisplayData, val traffic: List<TrafficData>)
    private fun snapshotTraffic() = ledger?.totals().orEmpty().map {
        TrafficData(id = it.id, rx = it.rx, tx = it.tx)
    }

    @Synchronized
    private fun sample(): Snapshot? {
        if (stopped) return null
        val proxy = data.proxy ?: return null
        if (!proxy.isInitialized() || updater == null) return null
        updater!!.updateAll()
        val values = ledger!!.representatives
        return Snapshot(SpeedDisplayData(
            values.filter { !it.ignore }.sumOf { it.txRate },
            values.filter { !it.ignore }.sumOf { it.rxRate },
            if (DataStore.showDirectSpeed) bypass.txRate else 0L,
            if (DataStore.showDirectSpeed) bypass.rxRate else 0L,
            values.sumOf { it.tx - it.txBase }, values.sumOf { it.rx - it.rxBase },
        ), snapshotTraffic())
    }

    private suspend fun loop() {
        val interval = DataStore.speedInterval.toLong()
        if (interval <= 0) return
        while (currentCoroutineContext().isActive) {
            sample()?.let { snapshot ->
                if (data.state == BaseService.State.Connected) {
                    data.binder.broadcast { callback ->
                        if (data.binder.callbackIdMap[callback] == SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND) {
                            callback.cbSpeedUpdate(snapshot.speed)
                            if (DataStore.profileTrafficStatistics) snapshot.traffic.forEach(callback::cbTrafficUpdate)
                        }
                    }
                }
                data.notification?.let { if (it.listenPostSpeed) it.postNotificationSpeedUpdate(snapshot.speed) }
            }
            delay(interval)
        }
    }
}
