package io.nekohasekai.sagernet.database

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.SubscriptionUpdate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionConsistencyTest {
    private lateinit var db: SagerDatabase
    private lateinit var settings: PublicDatabase
    private lateinit var store: RoomPreferenceDataStore
    private fun bean(name: String, host: String = "example.org") = SOCKSBean().apply {
        initializeDefaultValues(); this.name = name; serverAddress = host
    }
    private fun entity(id: Long, name: String, host: String = "example.org") =
        ProxyEntity(id = id, groupId = 1).apply { putBean(bean(name, host)) }
    private fun openProfiles() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java, "consistency-profiles")
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("consistency-profiles"); context.deleteDatabase("consistency-settings")
        db = openProfiles()
        settings = Room.databaseBuilder(context, PublicDatabase::class.java, "consistency-settings")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        store = RoomPreferenceDataStore(settings.keyValuePairDao())
        db.groupDao().insert(listOf(ProxyGroup(id = 1, name = "subscription")))
        db.proxyDao().insert(listOf(entity(1, "node")))
    }
    @After fun close() { db.close(); settings.close() }

    @Test fun attachedConnectionCannotLeakIntoSelectionOrConcurrentRestore() {
        val attached = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val pending = executor.submit<String> {
            AttachedSettingsTransaction.withConnection(db, settings) { isolated, sqlite ->
                attached.countDown()
                check(proceed.await(10, TimeUnit.SECONDS))
                isolated.runInTransaction(java.util.concurrent.Callable {
                    AttachedSettingsTransaction.read(sqlite, "concurrent-restore")!!.string!!
                })
            }
        }
        try {
            assertTrue(attached.await(5, TimeUnit.SECONDS))
            assertTrue(ProfileSelection.select(1, db, store))
            io.nekohasekai.sagernet.database.backup.BackupRestore.restore(
                io.nekohasekai.sagernet.database.backup.BackupPlan(null, null, null,
                    listOf(io.nekohasekai.sagernet.database.preference.KeyValuePair("concurrent-restore").put("committed"))),
                db, settings)
            val aliases = db.openHelper.writableDatabase.query("PRAGMA database_list").use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(1)) }
            }
            assertFalse(aliases.contains("mutation_settings"))
            proceed.countDown()
            assertEquals("committed", pending.get(5, TimeUnit.SECONDS))
        } finally { proceed.countDown(); executor.shutdownNow() }
    }

    @Test fun delayedSelectionCannotResurrectAnIdDeletedByAnotherConnection() {
        val other = openProfiles()
        other.proxyDao().getAll() // open before taking the write lock
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            lateinit var result: java.util.concurrent.Future<Boolean>
            db.runInTransaction {
                db.proxyDao().deleteById(1)
                result = executor.submit<Boolean> {
                    started.countDown()
                    ProfileSelection.select(1, other, store)
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFalse(result.isDone)
            }
            assertFalse(result.get(5, TimeUnit.SECONDS))
            assertEquals(0L, store.getLong(Key.PROFILE_ID, 0L))
        } finally { executor.shutdownNow(); other.close() }
    }
    @Test fun subscriptionCannotDeleteASelectionCommittedFirst() {
        assertTrue(ProfileSelection.select(1, db, store))
        assertThrows(DataValidationException::class.java) {
            SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("replacement", "new.example")), settings = settings)
        }
        assertNotNull(db.proxyDao().getById(1))
        assertEquals(1L, store.getLong(Key.PROFILE_ID, 0L))
    }
    @Test fun unrelatedExistingInvalidRuleDoesNotBlockSubscription() {
        db.rulesDao().insert(listOf(RuleEntity(id = 1, name = "old broken rule", outbound = 999)))
        SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("node", "updated.example")))
        assertEquals("updated.example", db.proxyDao().getById(1)!!.requireBean().serverAddress)
        assertEquals(999L, db.rulesDao().allRules().single().outbound)
    }
    @Test fun duplicateOldNamesAreMatchedByUniqueContentAndExtrasRemoved() {
        db.proxyDao().insert(listOf(entity(2, "node", "obsolete.example")))
        SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("node")))
        assertEquals(listOf(1L), db.proxyDao().getAll().map { it.id })
    }
    @Test fun ambiguousDuplicateNamesKeepBothOldNodes() {
        db.proxyDao().insert(listOf(entity(2, "node", "other.example")))
        assertThrows(DataValidationException::class.java) {
            SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("node", "new.example")))
        }
        assertEquals(2, db.proxyDao().getAll().size)
    }
    @Test fun referencedDuplicateCannotBeSilentlyRemoved() {
        db.proxyDao().insert(listOf(entity(2, "node", "other.example")))
        db.rulesDao().insert(listOf(RuleEntity(id = 1, outbound = 2)))
        assertThrows(DataValidationException::class.java) {
            SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("node")))
        }
        assertEquals(2, db.proxyDao().getAll().size)
    }
    @Test fun bulkDeletionIsAllOrNothingAndClearsOnlyAffectedSelection() {
        db.proxyDao().insert(listOf(entity(2, "other")))
        ProfileSelection.select(2, db, store)
        db.rulesDao().insert(listOf(RuleEntity(id = 1, outbound = 1)))
        assertThrows(DataValidationException::class.java) {
            ProfileMutations.delete(setOf(1, 2), database = db, settings = store)
        }
        assertEquals(2, db.proxyDao().getAll().size)
        assertEquals(2L, store.getLong(Key.PROFILE_ID, 0L))
        ProfileMutations.delete(setOf(2), database = db, settings = store)
        assertEquals(0L, store.getLong(Key.PROFILE_ID, 0L))
        assertNotNull(db.proxyDao().getById(1))
    }
    @Test fun delayedTestNeverOverwritesNewConfigurationOrTraffic() {
        val tested = db.proxyDao().getById(1)!!.apply { status = 1; ping = 42 }
        db.proxyDao().getById(1)!!.also {
            it.requireBean().serverAddress = "new.example"; it.rx = 900; it.userOrder = 8
            db.proxyDao().updateProxy(it)
        }
        assertNull(ProfileMutations.saveTestResult(tested, db))
        val latest = db.proxyDao().getById(1)!!
        assertEquals("new.example", latest.requireBean().serverAddress)
        assertEquals(900L, latest.rx)
        assertEquals(8L, latest.userOrder)
        latest.status = 1; latest.ping = 50
        db.proxyDao().updateTraffic(1, 1200, 300)
        assertNotNull(ProfileMutations.saveTestResult(latest, db))
        assertEquals(1200L, db.proxyDao().getById(1)!!.rx)
        assertEquals(50, db.proxyDao().getById(1)!!.ping)
    }

    @Test fun configurationEditPreservesLiveColumnsAndRejectsStaleConfig() {
        val edited = db.proxyDao().getById(1)!!
        val original = ProfileMutations.configurationFingerprint(edited.requireBean())
        edited.requireBean().name = "renamed"
        db.proxyDao().updateTraffic(1, 1234, 5678)
        db.proxyDao().updateOrder(1, 1, 9)
        ProfileMutations.updateConfiguration(edited, db, original)
        val saved = db.proxyDao().getById(1)!!
        assertEquals(1234L, saved.rx)
        assertEquals(9L, saved.userOrder)
        assertThrows(DataValidationException::class.java) {
            ProfileMutations.updateConfiguration(edited, db, original)
        }
    }

    @Test fun editorCannotSaveReferenceToNodeDeletedDuringEditing() {
        db.proxyDao().deleteById(1)
        assertThrows(DataValidationException::class.java) {
            ProfileMutations.edit(db) { db.rulesDao().createRule(RuleEntity(outbound = 1)) }
        }
        assertTrue(db.rulesDao().allRules().isEmpty())
        assertThrows(DataValidationException::class.java) {
            ProfileMutations.edit(db) {
                db.groupDao().updateGroup(db.groupDao().getById(1)!!.apply { frontProxy = 1 })
            }
        }
        assertEquals(-1L, db.groupDao().getById(1)!!.frontProxy)
    }

    @Test fun duplicateIncomingNamesKeepIdentityWhenResponseOrderChanges() {
        val group = db.groupDao().getById(1)!!
        SubscriptionUpdate.commit(db, group, listOf(bean("X", "a.example"), bean("X", "b.example")))
        val a = db.proxyDao().getAll().single { it.requireBean().serverAddress == "a.example" }
        val b = db.proxyDao().getAll().single { it.requireBean().serverAddress == "b.example" }
        a.requireBean().customConfigJson = "{\"log\":{\"level\":\"debug\"}}"
        db.proxyDao().updateProxy(a)
        db.rulesDao().insert(listOf(RuleEntity(outbound = a.id)))
        SubscriptionUpdate.commit(db, group, listOf(bean("X", "b.example"), bean("X", "a.example")))
        assertEquals("a.example", db.proxyDao().getById(a.id)!!.requireBean().serverAddress)
        assertEquals("b.example", db.proxyDao().getById(b.id)!!.requireBean().serverAddress)
        assertEquals(a.requireBean().customConfigJson, db.proxyDao().getById(a.id)!!.requireBean().customConfigJson)
        assertEquals(2, db.proxyDao().getAll().map { it.displayName() }.distinct().size)
    }

    @Test fun subscriptionFailureRollsBackCurrentSelectionAndProfileChanges() {
        store.putLong(Key.PROFILE_CURRENT, 1)
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_node_delete BEFORE DELETE ON proxy_entities BEGIN SELECT RAISE(ABORT, 'simulated delete failure'); END")
        assertThrows(Exception::class.java) {
            SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!,
                listOf(bean("replacement", "new.example")), settings = settings)
        }
        assertEquals(1L, store.getLong(Key.PROFILE_CURRENT, 0L))
        assertEquals(listOf(1L), db.proxyDao().getAll().map { it.id })
    }

    @Test fun currentSelectionCannotResurrectDeletedProfile() {
        ProfileSelection.select(1, db, store, Key.PROFILE_CURRENT)
        ProfileMutations.delete(setOf(1), database = db, settings = store)
        assertFalse(ProfileSelection.select(1, db, store, Key.PROFILE_CURRENT))
        assertEquals(0L, store.getLong(Key.PROFILE_CURRENT, 0L))
    }

    @Test fun backupReferenceErrorCarriesImportedOriginEvenWhenLocalIdsCollide() {
        val plan = io.nekohasekai.sagernet.database.backup.BackupPlan(
            emptyList(), listOf(ProxyGroup(id = 1, name = "imported", frontProxy = 999)), emptyList(), null)
        val error = assertThrows(DataValidationException::class.java) {
            plan.validateReferences(db.proxyDao().getAll(), db.groupDao().allGroups(), db.rulesDao().allRules())
        }
        assertTrue(error.fromBackup)
        assertEquals("imported", error.details[0])
        assertEquals("subscription", db.groupDao().getById(1)!!.name)
    }

    @Test fun literalNumericSuffixNamesRemainIndependentDuringUpdates() {
        val group = db.groupDao().getById(1)!!
        SubscriptionUpdate.commit(db, group, listOf(bean("X", "a.example"), bean("X (1)", "a.example")))
        val original = db.proxyDao().getAll().associate { it.displayName() to it.id }
        SubscriptionUpdate.commit(db, group, listOf(bean("X", "a.example"), bean("X (1)", "a.example")))
        SubscriptionUpdate.commit(db, group, listOf(bean("X", "c.example"), bean("X (1)", "a.example")))
        assertEquals("c.example", db.proxyDao().getById(original.getValue("X"))!!.requireBean().serverAddress)
        assertEquals("a.example", db.proxyDao().getById(original.getValue("X (1)"))!!.requireBean().serverAddress)
    }

}
