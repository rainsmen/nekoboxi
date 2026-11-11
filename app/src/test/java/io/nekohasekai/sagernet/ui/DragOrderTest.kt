package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DragOrderTest {
    private fun await(check: () -> Boolean) {
        val end = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < end) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue("sort operation did not finish", check())
    }

    @Test fun consecutiveDragsKeepTheirSnapshotsAndLatestOrderInBothAdapters() = exerciseDrags(false)

    @Test fun recreatedAdaptersSubmitCompleteOrderWhileOldDragsArePending() = exerciseDrags(true)

    @Test fun failedEarlierBatchDoesNotCorruptLaterCompleteOrder() = exerciseDrags(false, true)

    private fun exerciseDrags(recreate: Boolean, failFirst: Boolean = false) {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        val db = SagerDatabase.instance
        db.clearAllTables()
        db.groupDao().insert((1L..3L).map { ProxyGroup(id = it, name = "group-$it", userOrder = it, ungrouped = it == 1L) })
        db.proxyDao().insert((1L..3L).map { id -> ProxyEntity(id = id, groupId = 1, userOrder = id).apply {
            putBean(SOCKSBean().apply { initializeDefaultValues(); name = "node-$id"; serverAddress = "example.org" })
        } })
        if (failFirst) {
            for (table in listOf("proxy_groups", "proxy_entities")) {
                db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_first_$table BEFORE UPDATE OF userOrder ON $table WHEN NEW.id = 3 AND NEW.userOrder = 1 BEGIN SELECT RAISE(ABORT, 'first drag fails'); END")
            }
        }
        val host = Robolectric.buildActivity(RouteToggleHostActivity::class.java).setup()
        val release = CompletableDeferred<Unit>()
        try {
            val groups = GroupFragment().apply {
                groupListView = RecyclerView(host.get()).apply { layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context) }
            }
            host.get().setContentView(groups.groupListView)
            host.visible()
            var groupAdapter = groups.GroupAdapter()
            await { groupAdapter.groupList.size == 3 }
            val nodes = ConfigurationFragment.GroupFragment().apply {
                proxyGroup = db.groupDao().getById(1)!!
                configurationListView = groups.groupListView
            }
            var nodeAdapter = nodes.ConfigurationAdapter()
            nodeAdapter.reloadProfiles()
            await { nodeAdapter.itemCount == 3 }
            ProfileOrderWrites.shared.submit { release.await() }
            groupAdapter.move(2, 0)
            assertEquals(listOf(3L, 1L, 2L), groupAdapter.groupList.map { it.id })
            groupAdapter.commitMove()
            groupAdapter.move(0, 2); groupAdapter.commitMove()
            groupAdapter.move(0, 1); groupAdapter.commitMove()
            nodeAdapter.move(2, 0)
            assertEquals(listOf(3L, 1L, 2L), nodeAdapter.configurationIdList)
            nodeAdapter.commitMove()
            nodeAdapter.move(0, 2); nodeAdapter.commitMove()
            nodeAdapter.move(0, 1); nodeAdapter.commitMove()
            if (recreate) {
                groupAdapter = groups.GroupAdapter()
                await { groupAdapter.groupList.size == 3 }
                nodeAdapter = nodes.ConfigurationAdapter()
                nodeAdapter.reloadProfiles()
                await { nodeAdapter.itemCount == 3 }
                groupAdapter.move(0, 1); groupAdapter.commitMove()
                nodeAdapter.move(0, 1); nodeAdapter.commitMove()
            }
            val groupsExpected = groupAdapter.groupList.associate { it.id to it.userOrder }
            val nodesExpected = nodeAdapter.configurationList.mapValues { it.value.userOrder }
            // Mutable entities are changed after enqueue; persisted values must stay captured.
            groupAdapter.groupList.forEach { it.userOrder = 99 }
            nodeAdapter.configurationList.values.forEach { it.userOrder = 99 }
            release.complete(Unit)
            await { db.groupDao().allGroups().associate { it.id to it.userOrder } == groupsExpected &&
                db.proxyDao().getAll().associate { it.id to it.userOrder } == nodesExpected }
            runBlocking { ProfileOrderWrites.shared.submit {}.join() }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertEquals(groupsExpected.entries.sortedBy { it.value }.map { it.key }, groupAdapter.groupList.map { it.id })
            assertEquals(nodesExpected.entries.sortedBy { it.value }.map { it.key }, nodeAdapter.configurationIdList)
        } finally {
            release.complete(Unit)
            runBlocking { ProfileOrderWrites.shared.submit {}.join() }
            host.close()
        }
    }
}
