package io.nekohasekai.sagernet.ui

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
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

class DraftProbeActivity : ConfigurationEditorActivity(R.layout.layout_config_settings) {
    fun completeInitialization(name: String) = initializeDraft { DataStore.profileName = name }
}

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

    @Test fun newTailscaleEditorCanSaveWithoutAServerPortField() {
        DataStore.selectedGroup = 1
        val type = io.nekohasekai.sagernet.ui.profile.TailscaleSettingsActivity::class.java
        val controller = Robolectric.buildActivity(type, Intent(RuntimeEnvironment.getApplication(), type)).setup()
        try {
            ready(controller)
            DataStore.profileCacheStore.putString("name", "new tailscale")
            runBlocking { controller.get().saveAndExit() }
            val added = SagerDatabase.proxyDao.getAll().single { it.id != 1L }
            assertTrue(added.requireBean() is io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean)
            assertNotNull(added.requireBean().serverPort)
            assertTrue(controller.get().isFinishing)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun subscriptionOnlyRestoreProducesAnActionableImportTargetError() {
        SagerDatabase.groupDao.updateGroup(ProxyGroup(id = 1, name = "subscription",
            type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION))
        DataStore.selectedGroup = 1
        val error = assertThrows(DataValidationException::class.java) { DataStore.selectedGroupForImport() }
        assertEquals(DataValidationException.Reason.IMPORT_GROUP_REQUIRED, error.reason)
        assertEquals(1, SagerDatabase.proxyDao.getAll().size)
        SagerDatabase.groupDao.insert(listOf(ProxyGroup(id = 2, name = "manual")))
        assertEquals(2L, DataStore.selectedGroupForImport())
    }

    @Test fun savesRejectRestoreAfterTheEditorPrecheckEvenWhenIdsAndNodeContentMatch() = runBlocking {
        val generation = ProfileMutations.currentGeneration()
        val profile = SagerDatabase.proxyDao.getById(1)!!
        val fingerprint = ProfileMutations.configurationFingerprint(profile.requireBean())
        val group = SagerDatabase.groupDao.getById(1)!!
        val rule = SagerDatabase.rulesDao.getById(1)!!
        io.nekohasekai.sagernet.database.backup.BackupRestore.restore(
            io.nekohasekai.sagernet.database.backup.BackupPlan(listOf(profile), listOf(group), listOf(rule), null))
        val operations: List<suspend () -> Unit> = listOf(
            { ProfileManager.updateProfile(profile, fingerprint, generation) },
            { ProfileManager.updateRule(rule.copy(name = "old draft"), generation) },
            { GroupManager.updateGroup(group.copy(name = "old draft"), generation) },
            { ProfileManager.createProfile(1, profile.requireBean(), generation) },
            { ProfileManager.createRule(RuleEntity(domains = "old.example"), expectedGeneration = generation) },
            { GroupManager.createGroup(ProxyGroup(name = "old draft"), generation) })
        operations.forEach { operation ->
            val error = try { operation(); null } catch (error: DataValidationException) { error }
            assertEquals(DataValidationException.Reason.CONFIG_CHANGED, error?.reason)
        }
        assertEquals("group", SagerDatabase.groupDao.getById(1)!!.name)
        assertEquals("rule", SagerDatabase.rulesDao.getById(1)!!.name)
        assertEquals(1, SagerDatabase.proxyDao.getAll().size)
        assertEquals(1, SagerDatabase.groupDao.allGroups().size)
        assertEquals(1, SagerDatabase.rulesDao.allRules().size)
    }

    @Test fun defaultRulesRollbackAndRemainRetryableWhenTheSecondInsertFails() {
        SagerDatabase.rulesDao.reset()
        DataStore.rulesFirstCreate = false
        val database = SagerDatabase.instance.openHelper.writableDatabase
        database.execSQL("CREATE TRIGGER reject_default_rule BEFORE INSERT ON rules WHEN NEW.domains = 'geosite:category-ads-all' BEGIN SELECT RAISE(ABORT, 'fixture write failure'); END")
        try {
            assertThrows(Exception::class.java) { ProfileManager.ensureDefaultRules() }
            assertTrue(SagerDatabase.rulesDao.allRules().isEmpty())
            assertFalse(DataStore.rulesFirstCreate)
        } finally { database.execSQL("DROP TRIGGER reject_default_rule") }
        val rules = ProfileManager.ensureDefaultRules()
        assertTrue(rules.any { it.domains == "geosite:category-ads-all" })
        assertTrue(rules.any { it.ip == "geoip:cn" && it.enabled })
        assertTrue(DataStore.rulesFirstCreate)
        assertEquals(rules, ProfileManager.ensureDefaultRules())
    }

    @Test fun routeResetAndSwitchRejectACompletedRestore() = runBlocking {
        val old = ProfileMutations.currentGeneration()
        io.nekohasekai.sagernet.database.backup.BackupRestore.restore(
            io.nekohasekai.sagernet.database.backup.BackupPlan(
                SagerDatabase.proxyDao.getAll(), SagerDatabase.groupDao.allGroups(),
                listOf(RuleEntity(id = 1, name = "restored", domains = "restored.example", enabled = false)), null))
        assertThrows(DataValidationException::class.java) { ProfileManager.resetRules(old) }
        assertThrows(DataValidationException::class.java) { ProfileManager.setRuleEnabled(1, true, old) }
        assertThrows(DataValidationException::class.java) { ProfileManager.orderRules(mapOf(1L to 9L), old) }
        assertEquals("restored", SagerDatabase.rulesDao.getById(1)!!.name)
        assertFalse(SagerDatabase.rulesDao.getById(1)!!.enabled)
    }

    @Test fun resetRollsBackTheDeletionWhenDefaultInsertionFails() {
        val database = SagerDatabase.instance.openHelper.writableDatabase
        database.execSQL("CREATE TRIGGER reject_reset_rule BEFORE INSERT ON rules WHEN NEW.domains = 'geosite:category-ads-all' BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try {
            assertThrows(Exception::class.java) { ProfileManager.resetRules(ProfileMutations.currentGeneration()) }
            assertEquals("rule", SagerDatabase.rulesDao.allRules().single().name)
            assertTrue(DataStore.rulesFirstCreate)
        } finally { database.execSQL("DROP TRIGGER reject_reset_rule") }
        ProfileManager.resetRules(ProfileMutations.currentGeneration())
        assertFalse(SagerDatabase.rulesDao.allRules().any { it.name == "rule" })
        assertTrue(SagerDatabase.rulesDao.allRules().any { it.domains == "geosite:category-ads-all" })
    }

    @Test fun importedBatchIsAtomicAndCannotAdoptANewRestoreGeneration() = runBlocking {
        val beans = listOf("one", "two").map { name -> SOCKSBean().apply {
            initializeDefaultValues(); this.name = name; serverAddress = "example.org"
        } }
        val generation = ProfileMutations.currentGeneration()
        val database = SagerDatabase.instance.openHelper.writableDatabase
        database.execSQL("CREATE TRIGGER reject_second_import BEFORE INSERT ON proxy_entities WHEN (SELECT count(*) FROM proxy_entities) >= 2 BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        try {
            try { ProfileManager.createProfiles(1, beans, generation); fail("partial import accepted") }
            catch (_: android.database.sqlite.SQLiteException) { }
            assertEquals(1, SagerDatabase.proxyDao.getAll().size)
        } finally { database.execSQL("DROP TRIGGER reject_second_import") }
        io.nekohasekai.sagernet.database.backup.BackupRestore.restore(
            io.nekohasekai.sagernet.database.backup.BackupPlan(
                SagerDatabase.proxyDao.getAll(), SagerDatabase.groupDao.allGroups(), null, null))
        val error = try { ProfileManager.createProfiles(1, beans, generation); null }
            catch (error: DataValidationException) { error }
        assertEquals(DataValidationException.Reason.CONFIG_CHANGED, error?.reason)
        assertEquals(1, SagerDatabase.proxyDao.getAll().size)
        ProfileManager.createProfiles(1, beans, ProfileMutations.currentGeneration())
        assertEquals(3, SagerDatabase.proxyDao.getAll().size)
    }

    @Test fun emptyChainsCannotBeCommittedOrBreakAnOtherwiseValidSelector() = runBlocking {
        val empty = io.nekohasekai.sagernet.fmt.internal.ChainBean().apply { initializeDefaultValues(); name = "empty" }
        val error = try { ProfileManager.createProfile(1, empty); null }
            catch (error: DataValidationException) { error }
        assertEquals(DataValidationException.Reason.EMPTY_CHAIN, error?.reason)
        assertEquals(1, SagerDatabase.proxyDao.getAll().size)
        // A legacy bad row is also rejected by the builder, even when another node is selected.
        SagerDatabase.proxyDao.insert(listOf(ProxyEntity(id = 2, groupId = 1).putBean(empty)))
        SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(1)!!.copy(isSelector = true))
        val failure = assertThrows(DataValidationException::class.java) {
            io.nekohasekai.sagernet.fmt.buildConfig(SagerDatabase.proxyDao.getById(1)!!)
        }
        assertEquals(DataValidationException.Reason.EMPTY_CHAIN, failure.reason)
    }

    @Test fun groupSavePreservesCurrentSubscriptionMetadataAndResetsOnlyWhenSourceChanges() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        runCatching { androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build()) }
        val metadata = SubscriptionBean().apply {
            initializeDefaultValues(); type = 2; link = "https://old.example"; lastUpdated = 42
            lastAttempted = 99L; lastError = "previous failure"; subscriptionUserinfo = "upload=5"
        }
        SagerDatabase.groupDao.updateGroup(ProxyGroup(id = 1, name = "subscription", type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION,
            ungrouped = true, subscription = metadata))
        val generation = ProfileMutations.currentGeneration()
        fun candidate(link: String) = ProxyGroup(id = 1, name = "edited", type = io.nekohasekai.sagernet.GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().apply {
                initializeDefaultValues(); this.link = link; deduplication = true; customUserAgent = "edited-agent"
            })
        GroupManager.updateGroup(candidate(metadata.link), generation)
        val saved = SagerDatabase.groupDao.getById(1)!!
        assertTrue(saved.ungrouped)
        with(saved.subscription!!) {
            assertEquals(2, type.toInt()); assertEquals(42, lastUpdated.toInt()); assertEquals(99L, lastAttempted.toLong())
            assertEquals("previous failure", lastError); assertEquals("upload=5", subscriptionUserinfo)
            assertTrue(deduplication); assertEquals("edited-agent", customUserAgent)
        }
        GroupManager.updateGroup(candidate("https://new.example"), generation)
        with(SagerDatabase.groupDao.getById(1)!!.subscription!!) {
            assertEquals(2, type.toInt()); assertEquals(0, lastUpdated.toInt()); assertEquals(0L, lastAttempted.toLong())
            assertEquals("", lastError); assertEquals("", subscriptionUserinfo)
        }
    }

    @Test fun shareFailureAllowsRetryAndDetachedViewDoesNotReceiveAnError() {
        val controller = Robolectric.buildActivity(BackupStateActivity::class.java).setup().visible()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        fun await(condition: () -> Boolean) {
            val deadline = System.nanoTime() + 10_000_000_000L
            while (System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return
                Thread.sleep(10)
            }
            assertTrue(condition())
        }
        try {
            val fragment = controller.get().supportFragmentManager.findFragmentByTag("backup") as BackupFragment
            val binding = io.nekohasekai.sagernet.databinding.LayoutBackupBinding.bind(fragment.requireView())
            fragment.writeShare = { throw java.io.IOException("fixture write failure") }
            binding.actionShare.performClick()
            await { binding.actionShare.isEnabled }
            fragment.writeShare = {
                entered.countDown()
                check(release.await(10, java.util.concurrent.TimeUnit.SECONDS))
                throw java.io.IOException("failure after detach")
            }
            val job = fragment.viewLifecycleOwner.lifecycleScope
            binding.actionShare.performClick()
            await { entered.count == 0L }
            controller.get().supportFragmentManager.beginTransaction().remove(fragment).commitNow()
            release.countDown()
            await { job.coroutineContext[kotlinx.coroutines.Job]!!.children.none() }
            assertNull(fragment.view)
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
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

    @Test fun lateInitializationCannotOverwriteTheNextEditorsDraft() {
        val first = Robolectric.buildActivity(DraftProbeActivity::class.java).setup()
        val second = Robolectric.buildActivity(DraftProbeActivity::class.java).setup()
        try {
            second.get().completeInitialization("new draft")
            first.get().completeInitialization("obsolete draft")
            assertTrue(first.get().isFinishing)
            assertEquals("new draft", DataStore.profileName)
            second.get().requireEditorSession()
        } finally { second.pause().stop().destroy(); first.pause().stop().destroy() }
    }

    @Test fun deleteAlwaysUsesTheIdentityOfTheDisplayedEditor() {
        SagerDatabase.proxyDao.insert(listOf(ProxyEntity(id = 2, groupId = 1).apply {
            putBean(SOCKSBean().apply { initializeDefaultValues(); name = "other"; serverAddress = "other.example" })
        }))
        val controller = open(SocksSettingsActivity::class.java)
        try {
            DataStore.editingId = 2
            val activity = controller.get()
            val menu = android.widget.PopupMenu(activity, android.view.View(activity)).menu
            activity.onOptionsItemSelected(menu.add(0, R.id.action_delete, 0, "delete"))
            activity.supportFragmentManager.executePendingTransactions()
            val fragment = activity.supportFragmentManager.fragments.filterIsInstance<
                io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity.DeleteConfirmationDialogFragment>().single()
            (fragment.dialog as androidx.appcompat.app.AlertDialog)
                .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!activity.isFinishing && System.nanoTime() < deadline) {
                Shadows.shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(10)
            }
            assertTrue(activity.isFinishing)
            assertNull(SagerDatabase.proxyDao.getById(1))
            assertEquals("other", SagerDatabase.proxyDao.getById(2)!!.displayName())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun anotherFullEditorCannotCauseThePreviousDraftToBeSaved() {
        val first = open(SocksSettingsActivity::class.java)
        val second = open(GroupSettingsActivity::class.java)
        try {
            DataStore.profileName = "foreign draft"
            runBlocking { first.get().saveAndExit() }
            assertFalse(first.get().isFinishing)
            assertEquals("node", SagerDatabase.proxyDao.getById(1)!!.displayName())
        } finally { second.pause().stop().destroy(); first.pause().stop().destroy() }
    }

    @Test fun followingAReferenceRequiresDiscardingADirtyDraftAndClosesTheSource() {
        val controller = open(SocksSettingsActivity::class.java)
        try {
            val activity = controller.get()
            DataStore.profileName = "unsaved"
            activity.reviewReference(Intent(activity, GroupSettingsActivity::class.java).putExtra("id", 1L))
            assertFalse(activity.isFinishing)
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
            dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(activity.isFinishing)
            assertEquals(GroupSettingsActivity::class.java.name, Shadows.shadowOf(activity).nextStartedActivity.component!!.className)
            assertEquals("node", SagerDatabase.proxyDao.getById(1)!!.displayName())
        } finally { controller.pause().stop().destroy() }
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
            initializeDefaultValues(); type = 1; name = "linked"; inheritGlobalTls = true
            config = """{"type":"socks","tag":"a","server":"a.example","server_port":443,"detour":"b"}"""
            outboundDependencies = """[{"type":"socks","tag":"b","server":"b.example","server_port":443}]"""
        }
        SagerDatabase.proxyDao.updateProxy(ProxyEntity(id = 1, groupId = 1).apply { putBean(bean) })
        val controller = open(moe.matsuri.nb4a.proxy.config.ConfigSettingActivity::class.java)
        try {
            controller.recreate(); ready(controller)
            DataStore.profileCacheStore.putString("outboundDependencies", bean.outboundDependencies.replace("443", "8443"))
            runBlocking { controller.get().saveAndExit() }
            val saved = SagerDatabase.proxyDao.getById(1)!!.requireBean() as moe.matsuri.nb4a.proxy.config.ConfigBean
            assertEquals(bean.config, saved.config)
            assertTrue(saved.inheritGlobalTls)
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
