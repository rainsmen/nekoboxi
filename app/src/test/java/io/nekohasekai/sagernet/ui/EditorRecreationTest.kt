package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.backup.BackupExportSnapshot
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.ui.profile.SocksSettingsActivity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import org.robolectric.android.controller.ActivityController
import java.io.ByteArrayOutputStream

class BackupStateActivity : ThemedActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(FrameLayout(this).apply { id = R.id.settings })
        if (state == null) supportFragmentManager.beginTransaction()
            .add(R.id.settings, BackupFragment(), "backup").commitNow()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class EditorRecreationTest {
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        DataStore.profileCacheStore.reset()
        DataStore.rulesFirstCreate = true
        SagerDatabase.instance.clearAllTables()
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 1, name = "group")))
        SagerDatabase.proxyDao.insert(listOf(ProxyEntity(id = 1, groupId = 1).apply {
            putBean(SOCKSBean().apply { initializeDefaultValues(); name = "node"; serverAddress = "example.org" })
        }))
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(id = 1, name = "rule", domains = "example.org")))
    }

    private fun <T : ThemedActivity> open(type: Class<T>): ActivityController<T> =
        Robolectric.buildActivity(type, Intent(RuntimeEnvironment.getApplication(), type).putExtra("id", 1L))
            .setup().visible().also(::ready)

    private fun <T : ThemedActivity> ready(controller: ActivityController<T>) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            controller.get().supportFragmentManager.executePendingTransactions()
            if (controller.get().supportFragmentManager.findFragmentById(R.id.settings)?.view != null) return
            Thread.sleep(10)
        }
        fail("Editor initialization did not finish")
    }

    @Test fun profileRecreationPreservesUnsavedChangesAndExpectedConfiguration() {
        val controller = open(SocksSettingsActivity::class.java)
        try {
            DataStore.profileName = "draft"
            assertTrue(DataStore.dirty)
            val changed = SagerDatabase.proxyDao.getById(1)!!
            changed.requireBean().serverAddress = "new.example"
            SagerDatabase.proxyDao.updateProxy(changed)
            controller.recreate(); ready(controller)
            assertTrue(DataStore.dirty)
            assertEquals("draft", DataStore.profileName)
            runBlocking { controller.get().saveAndExit() }
            assertFalse(controller.get().isFinishing)
            assertEquals("new.example", SagerDatabase.proxyDao.getById(1)!!.requireBean().serverAddress)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun groupRecreationStillTracksAndSavesNewEdits() {
        val controller = open(GroupSettingsActivity::class.java)
        try {
            assertFalse(DataStore.dirty)
            controller.recreate(); ready(controller)
            DataStore.groupName = "after rotation"
            assertTrue(DataStore.dirty)
            runBlocking { controller.get().saveAndExit() }
            assertEquals("after rotation", SagerDatabase.groupDao.getById(1)!!.name)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun routeRecreationStillTracksEdits() {
        val controller = open(RouteSettingsActivity::class.java)
        try {
            controller.recreate(); ready(controller)
            DataStore.routeName = "after rotation"
            assertTrue(DataStore.dirty)
            runBlocking { controller.get().saveAndExit() }
            assertEquals("after rotation", SagerDatabase.rulesDao.getById(1)!!.name)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun embeddedDependenciesCanBeEditedWithoutChangingTheRootOutbound() {
        val bean = moe.matsuri.nb4a.proxy.config.ConfigBean().apply {
            initializeDefaultValues(); type = 1; name = "linked"
            config = """{"type":"socks","tag":"a","server":"a.example","server_port":443,"detour":"b"}"""
            outboundDependencies = """[{"type":"socks","tag":"b","server":"b.example","server_port":443}]"""
        }
        SagerDatabase.proxyDao.updateProxy(ProxyEntity(id = 1, groupId = 1).apply { putBean(bean) })
        val controller = open(moe.matsuri.nb4a.proxy.config.ConfigSettingActivity::class.java)
        try {
            DataStore.profileCacheStore.putString("outboundDependencies", bean.outboundDependencies.replace("443", "8443"))
            runBlocking { controller.get().saveAndExit() }
            val saved = SagerDatabase.proxyDao.getById(1)!!.requireBean() as moe.matsuri.nb4a.proxy.config.ConfigBean
            assertEquals(bean.config, saved.config)
            assertTrue(saved.outboundDependencies.contains("8443"))
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun savingUnchangedRouteClosesWithoutEmptyRuleError() {
        val controller = open(RouteSettingsActivity::class.java)
        try {
            runBlocking { controller.get().saveAndExit() }
            assertTrue(controller.get().isFinishing)
            assertEquals("example.org", SagerDatabase.rulesDao.getById(1)!!.domains)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun backupSnapshotSurvivesFragmentRecreationAndMissingSnapshotCannotTruncateOutput() {
        val controller = Robolectric.buildActivity(BackupStateActivity::class.java).setup().visible()
        try {
            val original = controller.get().supportFragmentManager.findFragmentByTag("backup") as BackupFragment
            val snapshots = BackupExportSnapshot(controller.get().cacheDir)
            val content = original.doBackup(true, true, true)
            val token = snapshots.stage(content)
            original.pendingExport = token
            controller.recreate()
            val restored = controller.get().supportFragmentManager.findFragmentByTag("backup") as BackupFragment
            assertNotSame(original, restored)
            assertEquals(token, restored.pendingExport)
            val output = ByteArrayOutputStream()
            BackupExportSnapshot(controller.get().cacheDir).write(restored.pendingExport!!) { output }
            assertEquals(content, output.toString("UTF-8"))
            snapshots.discard(token)
            var opened = false
            assertThrows(java.io.FileNotFoundException::class.java) {
                snapshots.write(token) { opened = true; ByteArrayOutputStream() }
            }
            assertFalse(opened)
        } finally { controller.pause().stop().destroy() }
    }
}
