package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.os.Looper
import androidx.recyclerview.widget.RecyclerView
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

class RouteToggleHostActivity : ThemedActivity()

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class RouteToggleTest {
    private fun await(check: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (check()) return
            Thread.sleep(10)
        }
        assertTrue("rule update did not finish", check())
    }

    @Test fun aQueuedSwitchStillChangesItsOriginalRuleAfterTheHolderIsReused() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        SagerDatabase.instance.clearAllTables()
        DataStore.rulesFirstCreate = true
        DataStore.serviceState = BaseService.State.Stopped
        val first = RuleEntity(id = 1, name = "first", domains = "a.example", enabled = false)
        val second = RuleEntity(id = 2, name = "second", domains = "b.example", enabled = true)
        SagerDatabase.rulesDao.insert(listOf(first, second))
        val controller = Robolectric.buildActivity(RouteToggleHostActivity::class.java).setup()
        try {
            val fragment = RouteFragment()
            fragment.ruleListView = RecyclerView(controller.get()).apply {
                layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
            }
            controller.get().setContentView(fragment.ruleListView)
            controller.visible()
            fragment.ruleAdapter = fragment.RuleAdapter()
            await { fragment.ruleAdapter.ruleList.size == 2 }
            val holder = fragment.ruleAdapter.RuleHolder(
                io.nekohasekai.sagernet.databinding.LayoutRouteItemBinding.inflate(
                    controller.get().layoutInflater, fragment.ruleListView, false))
            holder.bind(first)
            val connection = SagerDatabase.instance.openHelper.writableDatabase
            connection.beginTransaction()
            try {
                holder.enableSwitch.performClick()
                holder.bind(second)
            } finally { connection.endTransaction() }
            await { SagerDatabase.rulesDao.getById(1)!!.enabled }
            assertTrue(SagerDatabase.rulesDao.getById(2)!!.enabled)
            assertTrue(holder.enableSwitch.isChecked)
        } finally { controller.close() }
    }
}
