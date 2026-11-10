package io.nekohasekai.sagernet.group

import android.content.ComponentName
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, io.nekohasekai.sagernet.bg.QuietNativeLog::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionLifecycleTest {
    private val listeners = mutableListOf<GroupManager.Listener>()
    private fun listen(listener: GroupManager.Listener) { listeners += listener; GroupManager.addListener(listener) }
    private open class Listener : GroupManager.Listener {
        override suspend fun groupAdd(group: ProxyGroup) = Unit
        override suspend fun groupUpdated(group: ProxyGroup) = Unit
        override suspend fun groupUpdated(groupId: Long) = Unit
        override suspend fun groupRemoved(groupId: Long) = Unit
    }
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        SagerDatabase.instance.clearAllTables()
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "before", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().applyDefaultValues().apply { link = "https://old.example/sub" })))
        GroupUpdater.updating.clear(); GroupUpdater.progress.clear()
    }
    @After fun cleanup() {
        listeners.forEach(GroupManager::removeListener)
        GroupManager.userInterface = null
        GroupUpdater.updating.clear(); GroupUpdater.progress.clear()
    }
    @Test fun cancellationBeforeDownloadAlwaysClearsProgressAndPublishesFreshGroup() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        var published: ProxyGroup? = null
        listen(object : Listener() {
            override suspend fun groupUpdated(groupId: Long) { entered.complete(Unit); awaitCancellation() }
            override suspend fun groupUpdated(group: ProxyGroup) { published = group }
        })
        val original = SagerDatabase.groupDao.getById(1)!!
        val task = launch { GroupUpdater.executeUpdate(original, false) }
        entered.await()
        GroupUpdater.progress[1] = GroupUpdater.Progress(1)
        SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(1)!!.apply {
            name = "after"; subscription!!.link = "https://new.example/sub"
        })
        task.cancelAndJoin()
        assertFalse(GroupUpdater.updating.contains(1))
        assertFalse(GroupUpdater.progress.containsKey(1))
        assertEquals("after", published!!.name)
        assertEquals("https://new.example/sub", published!!.subscription!!.link)
    }
    @Test fun duplicateRequestDoesNotClearAnotherOwnersProgress() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        listen(object : Listener() {
            override suspend fun groupUpdated(groupId: Long) { entered.complete(Unit); awaitCancellation() }
        })
        val group = SagerDatabase.groupDao.getById(1)!!
        val owner = launch { GroupUpdater.executeUpdate(group, false) }
        entered.await()
        val progress = GroupUpdater.Progress(2)
        GroupUpdater.progress[1] = progress
        assertFalse(GroupUpdater.executeUpdate(group, false))
        assertTrue(GroupUpdater.updating.contains(1))
        assertSame(progress, GroupUpdater.progress[1])
        owner.cancelAndJoin()
        assertFalse(GroupUpdater.updating.contains(1))
    }
    @Test fun backgroundFailurePersistsSafeDetailsAndSuccessClearsThem() = runBlocking {
        val group = SagerDatabase.groupDao.getById(1)!!
        val outcome = GroupUpdater.executeUpdateResult(group, false) { _, _, _, _ ->
            throw RawUpdater.DownloadFailure(java.io.IOException("Get https://sub.example/private?token=secret: timeout"))
        }
        assertEquals(GroupUpdater.Outcome.RETRYABLE_FAILURE, outcome)
        val failed = SagerDatabase.groupDao.getById(1)!!
        assertTrue(failed.subscription!!.lastAttempted > 0)
        assertTrue(failed.subscription!!.lastError.contains("timeout"))
        assertFalse(failed.subscription!!.lastError.contains("secret"))
        assertFalse(failed.subscription!!.lastError.contains("private"))
        assertEquals(0, failed.subscription!!.lastUpdated)
        val contentFailure = GroupUpdater.executeUpdateResult(group, false) { _, _, _, _ ->
            throw IllegalArgumentException("Invalid subscription content")
        }
        assertEquals(GroupUpdater.Outcome.CONTENT_FAILURE, contentFailure)
        val success = GroupUpdater.executeUpdateResult(group, false) { snapshot, _, _, _ ->
            SubscriptionUpdate.commit(SagerDatabase.instance, snapshot,
                listOf(io.nekohasekai.sagernet.fmt.socks.SOCKSBean().applyDefaultValues()))
        }
        assertEquals(GroupUpdater.Outcome.UPDATED, success)
        assertEquals("", SagerDatabase.groupDao.getById(1)!!.subscription!!.lastError)
        assertTrue(SagerDatabase.groupDao.getById(1)!!.subscription!!.lastUpdated > 0)
    }

    @Test fun cancellationWhileSynchronousDownloadFinishesCannotCommit() = runBlocking {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val original = SagerDatabase.groupDao.getById(1)!!.apply {
            subscription!!.lastUpdated = 10; subscription!!.subscriptionUserinfo = "old"
        }
        SagerDatabase.groupDao.updateGroup(original)
        SagerDatabase.proxyDao.insert(listOf(ProxyEntity(id = 1, groupId = 1).apply {
            putBean(io.nekohasekai.sagernet.fmt.socks.SOCKSBean().applyDefaultValues().apply { name = "saved" })
        }))
        val task = launch(Dispatchers.IO) {
            RawUpdater.update(original, original.subscription!!, null, false) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                RawUpdater.Download("""{"outbounds":[{"type":"socks","tag":"new","server":"new.example","server_port":1080}]}""", "new")
            }
        }
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            task.cancel()
        } finally { release.countDown(); task.join() }
        assertTrue(task.isCancelled)
        assertEquals("saved", SagerDatabase.proxyDao.getAll().single().requireBean().name)
        assertEquals(10, SagerDatabase.groupDao.getById(1)!!.subscription!!.lastUpdated)
        assertEquals("old", SagerDatabase.groupDao.getById(1)!!.subscription!!.subscriptionUserinfo)
    }

    @Test fun completedUpdateDoesNotRebroadcastADeletedGroup() = runBlocking {
        val original = SagerDatabase.groupDao.getById(1)!!
        var published = false
        listen(object : Listener() { override suspend fun groupUpdated(group: ProxyGroup) { published = true } })
        SagerDatabase.groupDao.deleteById(1)
        GroupUpdater.finishUpdate(original)
        assertFalse(published)
    }
    @Test fun permanentHttpFailuresDoNotRequestBackgroundRetries() = runBlocking {
        val group = SagerDatabase.groupDao.getById(1)!!
        for (status in listOf(401, 403, 404, 410, 408, 429, 503)) {
            val outcome = GroupUpdater.executeUpdateResult(group, false) { _, _, _, _ ->
                throw RawUpdater.DownloadFailure(java.io.IOException("HTTP $status response"))
            }
            assertEquals(if (status in listOf(408, 429, 503)) GroupUpdater.Outcome.RETRYABLE_FAILURE
                else GroupUpdater.Outcome.CONTENT_FAILURE, outcome)
        }
    }

}
