package io.nekohasekai.sagernet.bg

import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.proto.TrafficLedger
import io.nekohasekai.sagernet.bg.proto.TrafficLooper
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ktx.appScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

@Implements(libcore.Libcore::class, isInAndroidSdk = false)
class QuietNativeLog {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
        @JvmStatic @Implementation fun nekoLogPrintln(message: String) = Unit
        @JvmStatic @Implementation fun waitForAssets() = Unit
        // Controls the JNI boundary only. Real registry/constructor acceptance is tested in Go.
        @JvmStatic @Implementation fun validateImportedConfig(config: String) {
            val document = org.json.JSONObject(config)
            val nodes = document.getJSONArray("outbounds")
            for (index in 0 until nodes.length()) {
                val node = nodes.getJSONObject(index)
                if (node.optString("type") == "fixture-invalid") throw IllegalArgumentException("fixture native rejection")
            }
        }
    }
}

/** Real service stop state machine and SQLite persistence; only the native counter source is replaced. */
class TrafficStopService : Service(), BaseService.Interface {
    override val data = BaseService.Data(this)
    override val tag = "TrafficStopTest"
    override var wakeLock: PowerManager.WakeLock? = null
    override var upstreamInterfaceName: String? = null
    override fun acquireWakeLock() = Unit
    override fun createNotification(profileName: String) = ServiceNotification(this, profileName, "service-proxy")
    var traffic: TrafficLooper? = null
    override suspend fun killProcesses() { traffic?.stop() }
    override fun onBind(intent: Intent) = super<BaseService.Interface>.onBind(intent)
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) =
        super<BaseService.Interface>.onStartCommand(intent, flags, startId)
    override fun onDestroy() { data.binder.close(); super.onDestroy() }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class TrafficStopRecoveryTest {
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        assertFalse(ShutdownRecovery.hasPending())
        DataStore.profileTrafficStatistics = true
        SagerDatabase.instance.clearAllTables()
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "traffic")))
        SagerDatabase.proxyDao.insert((1L..2L).map { id ->
            ProxyEntity(id = id, groupId = 1, rx = 10, tx = 20).apply {
                putBean(SOCKSBean().apply { initializeDefaultValues(); name = "node$id" })
            }
        })
    }

    private fun awaitStopped(service: TrafficStopService) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (service.data.state == BaseService.State.Stopped) return
            Thread.sleep(10)
        }
        fail("Stop did not finish")
    }

    @Test fun stoppedClearHoldsMaintenanceLeaseUntilDatabaseWriteFinishes() {
        val controller = Robolectric.buildService(TrafficStopService::class.java).create()
        val service = controller.get()
        val file = File(service.createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            lateinit var result: java.util.concurrent.Future<Boolean>
            SagerDatabase.instance.runInTransaction {
                result = executor.submit<Boolean> { service.data.binder.clearTraffic(1) }
                val deadline = System.nanoTime() + 5_000_000_000L
                var held = false
                while (!held && System.nanoTime() < deadline) {
                    Shadows.shadowOf(Looper.getMainLooper()).idle()
                    val probe = ServiceMaintenanceLock.tryAcquire(file, true)
                    held = probe == null
                    probe?.close()
                    if (!held) Thread.sleep(10)
                }
                assertTrue("Reset must prevent a new launch from taking the old traffic snapshot", held)
                assertEquals(Service.START_NOT_STICKY, service.onStartCommand(null, 0, 1))
                assertNull(service.data.runningLease)
                assertFalse(result.isDone)
            }
            assertTrue(result.get(5, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(SagerDatabase.proxyDao.getAll().all { it.rx == 0L && it.tx == 0L })
            ServiceMaintenanceLock.tryAcquire(file, true)!!.close()
        } finally { executor.shutdownNow(); controller.destroy() }
    }

    @Test fun failedFinalWriteRollsBackAndSurvivesServiceRecreationUntilRetry() {
        val old = Robolectric.buildService(TrafficStopService::class.java).create()
        val service = old.get()
        val database = SagerDatabase.instance
        val counters = mutableMapOf("first" to 7L, "second" to 9L)
        var queriedOnMain = false
        val looper = TrafficLooper(service.data, appScope, database)
        looper.prepare(mapOf("first" to listOf(TrafficLedger.Node(1, 10, 20)),
            "second" to listOf(TrafficLedger.Node(2, 10, 20)))) { tag, direction ->
            queriedOnMain = queriedOnMain || Looper.myLooper() == Looper.getMainLooper()
            if (direction == "uplink") counters.remove(tag) ?: 0L else 0L
        }
        service.traffic = looper
        val lock = File(service.createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
        service.data.runningLease = ServiceMaintenanceLock.tryAcquire(lock, true)!!
        service.data.state = BaseService.State.Connected
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_last_traffic BEFORE UPDATE OF tx ON proxy_entities WHEN NEW.id = 2 BEGIN SELECT RAISE(ABORT, 'traffic write failed'); END")
        service.data.binder.stopForMaintenance("test", android.os.Binder())
        awaitStopped(service)
        assertFalse(queriedOnMain)
        assertTrue(ShutdownRecovery.hasPending())
        assertFalse(service.data.binder.getMaintenanceResult("test").isNullOrEmpty())
        assertEquals(listOf(20L, 20L), database.proxyDao().getAll().sortedBy { it.id }.map { it.tx })
        assertTrue(counters.isEmpty()) // Native counts were drained, so retry must use the retained ledger.
        old.destroy()
        val fresh = Robolectric.buildService(TrafficStopService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, fresh.get().onStartCommand(null, 0, 1))
            assertEquals(BaseService.State.Stopped, fresh.get().data.state)
            assertNull(fresh.get().data.runningLease)
            assertNull(ServiceMaintenanceLock.tryAcquire(lock, false))
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_last_traffic")
            fresh.get().data.binder.stopForMaintenance("test", android.os.Binder())
            awaitStopped(fresh.get())
            assertFalse(ShutdownRecovery.hasPending())
            assertEquals("", fresh.get().data.binder.getMaintenanceResult("test"))
            assertEquals(listOf(27L, 29L), database.proxyDao().getAll().sortedBy { it.id }.map { it.tx })
            ServiceMaintenanceLock.tryAcquire(lock, false)!!.close()
            runBlocking { looper.stop() }
            assertEquals(listOf(27L, 29L), database.proxyDao().getAll().sortedBy { it.id }.map { it.tx })
        } finally {
            database.openHelper.writableDatabase.execSQL("DROP TRIGGER IF EXISTS fail_last_traffic")
            runBlocking { ShutdownRecovery.retryAll() }
            fresh.destroy()
        }
    }
    private fun awaitTraffic(id: Long, tx: Long) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (SagerDatabase.proxyDao.getById(id)?.tx == tx) return
            Thread.sleep(10)
        }
        assertEquals(tx, SagerDatabase.proxyDao.getById(id)!!.tx)
    }

    @Test fun periodicCheckpointsRunWithoutSpeedDisplayAndRespectClear() {
        val controller = Robolectric.buildService(TrafficStopService::class.java).create()
        val oldInterval = DataStore.speedInterval
        DataStore.speedInterval = 0
        val counter = java.util.concurrent.atomic.AtomicLong(7)
        val looper = TrafficLooper(controller.get().data, appScope, SagerDatabase.instance, 25)
        looper.prepare(mapOf("node" to listOf(TrafficLedger.Node(1, 10, 20)))) { _, direction ->
            if (direction == "uplink") counter.getAndSet(0) else 0
        }
        try {
            looper.start()
            awaitTraffic(1, 27)
            counter.set(5)
            awaitTraffic(1, 32)
            runBlocking { assertTrue(looper.clearTraffic(1)) }
            assertEquals(0L, SagerDatabase.proxyDao.getById(1)!!.tx)
            counter.set(3)
            awaitTraffic(1, 3)
            runBlocking { looper.stop() }
            assertEquals(3L, SagerDatabase.proxyDao.getById(1)!!.tx)
        } finally {
            runBlocking { looper.stop() }
            DataStore.speedInterval = oldInterval
            controller.destroy()
        }
    }

    @Test fun checkpointRacesWithClearAndStopWithoutLosingOrRevivingTraffic() = runBlocking {
        for (clear in listOf(true, false)) {
            val controller = Robolectric.buildService(TrafficStopService::class.java).create()
            val entered = java.util.concurrent.CountDownLatch(1)
            val release = java.util.concurrent.CountDownLatch(1)
            val first = java.util.concurrent.atomic.AtomicBoolean(true)
            val counter = java.util.concurrent.atomic.AtomicLong(7)
            val looper = TrafficLooper(controller.get().data, appScope)
            looper.prepare(mapOf("node" to listOf(TrafficLedger.Node(1, 10, 20)))) { tag, direction ->
                if (tag != "node" || direction != "uplink") 0 else counter.getAndSet(0).also {
                    if (first.compareAndSet(true, false)) {
                        entered.countDown(); check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    }
                }
            }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO)
            val checkpoint = scope.async { looper.checkpoint() }
            try {
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
                counter.set(5)
                val competing = scope.async { if (clear) looper.clearTraffic(1) else looper.stop() }
                assertFalse(checkpoint.isCompleted)
                release.countDown()
                checkpoint.await(); competing.await()
                assertEquals(if (clear) 0L else 32L, SagerDatabase.proxyDao.getById(1)!!.tx)
                if (clear) { counter.set(3); looper.stop(); assertEquals(3L, SagerDatabase.proxyDao.getById(1)!!.tx) }
            } finally { release.countDown(); checkpoint.await(); looper.stop(); scope.cancel(); controller.destroy() }
        }
    }

    @Test fun shutdownBroadcastSavesAnActiveSessionWithoutStoppingIt() {
        val controller = Robolectric.buildService(TrafficStopService::class.java).create()
        val service = controller.get()
        val counter = java.util.concurrent.atomic.AtomicLong(7)
        var queriedOnMain = false
        val looper = TrafficLooper(service.data, appScope)
        looper.prepare(mapOf("node" to listOf(TrafficLedger.Node(1, 10, 20)))) { _, direction ->
            queriedOnMain = queriedOnMain || Looper.myLooper() == Looper.getMainLooper()
            if (direction == "uplink") counter.getAndSet(0) else 0
        }
        service.data.proxy = io.nekohasekai.sagernet.bg.proto.ProxyInstance(SagerDatabase.proxyDao.getById(1)!!).apply { this.looper = looper }
        service.data.state = BaseService.State.Connected
        try {
            service.registerReceiver(service.data.receiver, android.content.IntentFilter(Intent.ACTION_SHUTDOWN))
            service.sendBroadcast(Intent(Intent.ACTION_SHUTDOWN))
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            val receiver = Shadows.shadowOf(service.data.receiver)
            assertTrue(receiver.wentAsync())
            val pending = requireNotNull(receiver.originalPendingResult)
            org.robolectric.shadow.api.Shadow.extract<org.robolectric.shadows.ShadowBroadcastPendingResult>(pending)
                .future.get(5, java.util.concurrent.TimeUnit.SECONDS)
            awaitTraffic(1, 27)
            assertFalse(queriedOnMain)
            assertEquals(BaseService.State.Connected, service.data.state)
            counter.set(2)
            runBlocking { service.persistStats() }
            assertEquals(29L, SagerDatabase.proxyDao.getById(1)!!.tx)
        } finally {
            service.unregisterReceiver(service.data.receiver)
            runBlocking { looper.stop() }; service.data.proxy = null; controller.destroy()
        }
    }

    @Test fun checkpointWriteFailureRetainsCountersForTheNextSave() {
        val controller = Robolectric.buildService(TrafficStopService::class.java).create()
        val counter = java.util.concurrent.atomic.AtomicLong(7)
        val looper = TrafficLooper(controller.get().data, appScope)
        looper.prepare(mapOf("node" to listOf(TrafficLedger.Node(1, 10, 20)))) { _, direction ->
            if (direction == "uplink") counter.getAndSet(0) else 0
        }
        val database = SagerDatabase.instance.openHelper.writableDatabase
        try {
            database.execSQL("CREATE TRIGGER fail_checkpoint BEFORE UPDATE OF tx ON proxy_entities BEGIN SELECT RAISE(ABORT, 'storage unavailable'); END")
            assertThrows(Exception::class.java) { runBlocking { looper.checkpoint() } }
            assertEquals(0L, counter.get())
            assertEquals(20L, SagerDatabase.proxyDao.getById(1)!!.tx)
            database.execSQL("DROP TRIGGER fail_checkpoint")
            runBlocking { looper.checkpoint(); looper.stop() }
            assertEquals(27L, SagerDatabase.proxyDao.getById(1)!!.tx)
        } finally {
            database.execSQL("DROP TRIGGER IF EXISTS fail_checkpoint")
            runBlocking { looper.stop() }
            controller.destroy()
        }
    }

}
