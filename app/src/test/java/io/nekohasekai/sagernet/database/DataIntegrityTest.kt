package io.nekohasekai.sagernet.database

import android.app.Application
import android.os.Parcel
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.database.backup.BackupDocument
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.backup.BackupPlan
import io.nekohasekai.sagernet.database.backup.BackupRestore
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.SubscriptionUpdate
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class DataIntegrityTest {
    private lateinit var db: SagerDatabase
    private lateinit var settings: PublicDatabase
    private fun bean(name: String) = SOCKSBean().apply {
        initializeDefaultValues(); this.name = name; serverAddress = "example.com"
    }
    private fun profile(id: Long, name: String) = ProxyEntity(id = id, groupId = 1).apply { putBean(bean(name)) }

    @Before fun setup() {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("restore-profiles")
        context.deleteDatabase("restore-settings")
        db = Room.databaseBuilder(context, SagerDatabase::class.java, "restore-profiles")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        settings = Room.databaseBuilder(context, PublicDatabase::class.java, "restore-settings")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        db.groupDao().insert(listOf(ProxyGroup(id = 1, name = "original")))
        db.proxyDao().insert(listOf(profile(1, "original")))
        settings.keyValuePairDao().put(KeyValuePair("theme").put("old"))
    }
    @After fun close() { db.close(); settings.close() }

    @Test fun incompleteBackupCannotDeleteProfiles() {
        assertThrows(Exception::class.java) { BackupPlan.decode(JSONObject("""{"version":1,"profiles":[]}"""), true, false, false) }
        assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
    }
    @Test fun malformedLateSectionDoesNotWriteAnything() {
        assertThrows(Throwable::class.java) {
            BackupPlan.decode(JSONObject("""{"version":1,"profiles":[],"groups":[],"settings":["invalid"]}"""), true, false, true)
        }
        assertEquals(1, db.proxyDao().getAll().size)
        assertEquals("old", settings.keyValuePairDao()["theme"]!!.string)
    }
    private fun profilePayload(node: ProxyEntity, beanBytes: ByteArray): ByteArray {
        val output = com.esotericsoftware.kryo.io.ByteBufferOutput(256, -1)
        output.writeInt(0)
        output.writeLong(node.id); output.writeLong(node.groupId); output.writeInt(node.type)
        output.writeLong(node.userOrder); output.writeLong(node.tx); output.writeLong(node.rx)
        output.writeInt(node.status); output.writeInt(node.ping)
        output.writeString(node.uuid); output.writeString(node.error)
        output.writeVarInt(beanBytes.size, true); output.writeBytes(beanBytes); output.writeBoolean(node.dirty)
        return output.toBytes().also { output.close() }
    }

    @Test fun corruptNestedBeanAndTrailingBytesCannotReplaceExistingData() {
        val node = profile(2, "replacement")
        val valid = KryoConverters.serialize(node.requireBean())
        val payloads = listOf(byteArrayOf(0), valid.copyOf(valid.size - 1), valid + byteArrayOf(42))
            .map { profilePayload(node, it) } + listOf(KryoConverters.serialize(node) + byteArrayOf(42))
        for (payload in payloads) {
            val document = BackupDocument.encode(listOf(node), db.groupDao().allGroups(), emptyList(),
                emptyList(), true, false, false)
            val parcel = Parcel.obtain()
            try {
                parcel.writeByteArray(payload)
                document.getJSONArray("profiles").put(0, moe.matsuri.nb4a.utils.Util.b64EncodeUrlSafe(parcel.marshall()))
            } finally { parcel.recycle() }
            assertThrows(com.esotericsoftware.kryo.KryoException::class.java) {
                BackupRestore.restore(BackupPlan.decode(document, true, false, false), db, settings)
            }
            assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
            assertEquals("original", db.groupDao().allGroups().single().name)
            assertEquals("old", settings.keyValuePairDao()["theme"]!!.string)
        }
        // A failed strict decode must not poison later reads on the same thread.
        assertEquals("replacement", KryoConverters.deserialize(ProxyEntity(), KryoConverters.serialize(node)).requireBean().name)
    }

    @Test fun strictBackupStillAcceptsOlderSocksBeanSchemas() {
        for (version in 0..1) {
            val output = com.esotericsoftware.kryo.io.ByteBufferOutput(128, -1)
            output.writeInt(version); output.writeString("legacy.example"); output.writeInt(1080)
            if (version >= 1) output.writeInt(SOCKSBean.PROTOCOL_SOCKS5)
            output.writeString("user"); output.writeString("password")
            output.writeInt(1); output.writeString("legacy"); output.writeString(""); output.writeString("")
            val restored = KryoConverters.withStrictDecoding {
                KryoConverters.deserialize(ProxyEntity(), profilePayload(profile(2, "legacy"), output.toBytes()))
            }
            output.close()
            val bean = restored.requireBean() as SOCKSBean
            assertEquals("legacy.example", bean.serverAddress)
            assertEquals(SOCKSBean.PROTOCOL_SOCKS5, bean.protocol)
            assertFalse(bean.sUoT)
        }
    }

    @Test fun emptySubscriptionKeepsNodesAndMetadata() {
        assertThrows(IllegalArgumentException::class.java) { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, emptyList()) }
        assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
    }
    @Test fun statisticsNeverOverwriteEditedConfiguration() {
        val edited = db.proxyDao().getAll().single().apply { putBean(bean("edited")) }
        db.proxyDao().updateProxy(edited)
        db.proxyDao().updateTraffic(1, 123, 456)
        val result = db.proxyDao().getAll().single()
        assertEquals("edited", result.requireBean().name)
        assertEquals(123L, result.rx)
        assertEquals(456L, result.tx)
    }
    @Test fun settingsFailureRollsBackBothDatabases() {
        // Real SQLite failure after profile replacement, not a fake repository assertion.
        settings.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_setting BEFORE INSERT ON KeyValuePair WHEN NEW.`key` = 'reject' BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        val plan = BackupPlan(listOf(profile(2, "new")), listOf(ProxyGroup(id = 1, name = "new")), null,
            listOf(KeyValuePair("reject").put("value")))
        assertThrows(Exception::class.java) { BackupRestore.restore(plan, db, settings) }
        assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
        assertEquals("original", db.groupDao().allGroups().single().name)
        assertEquals("old", settings.keyValuePairDao()["theme"]!!.string)
    }
    @Test fun successfulRestoreCommitsBothDatabases() {
        BackupRestore.restore(BackupPlan(listOf(profile(2, "new")), listOf(ProxyGroup(id = 1, name = "new")), emptyList(),
            listOf(KeyValuePair("theme").put("new"))), db, settings)
        assertEquals("new", db.proxyDao().getAll().single().requireBean().name)
        assertEquals("new", settings.keyValuePairDao()["theme"]!!.string)
    }
    @Test fun subscriptionFailureRollsBackInsertedNodes() {
        db.openHelper.writableDatabase.execSQL("CREATE TRIGGER reject_group BEFORE UPDATE ON proxy_groups BEGIN SELECT RAISE(ABORT, 'test failure'); END")
        assertThrows(Exception::class.java) { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("replacement"))) }
        assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
    }
    @Test fun groupBackupPreservesSelectorAndChainSettings() {
        val original = ProxyGroup(id = 1, isSelector = true, frontProxy = 2, landingProxy = 3)
        val restored = KryoConverters.deserialize(ProxyGroup(), KryoConverters.serialize(original))
        assertTrue(restored.isSelector)
        assertEquals(2L, restored.frontProxy)
        assertEquals(3L, restored.landingProxy)
    }
    @Test fun legacySchemasMigrateWithoutLosingRows() {
        val context = RuntimeEnvironment.getApplication()
        for (version in listOf(1, 2)) {
            val name = "legacy-$version"
            context.deleteDatabase(name)
            val schema = JSONObject(javaClass.classLoader!!.getResourceAsStream(
                "io.nekohasekai.sagernet.database.SagerDatabase/$version.json"
            )!!.bufferedReader().use { it.readText() }).getJSONObject("database")
            android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(name), null).use { sqlite ->
                val entities = schema.getJSONArray("entities")
                for (i in 0 until entities.length()) {
                    val entity = entities.getJSONObject(i)
                    val table = entity.getString("tableName")
                    sqlite.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                    val indices = entity.getJSONArray("indices")
                    for (j in 0 until indices.length()) sqlite.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
                val queries = schema.getJSONArray("setupQueries")
                for (i in 0 until queries.length()) sqlite.execSQL(queries.getString(i))
                if (version == 1) sqlite.execSQL("INSERT INTO proxy_groups VALUES (1, 1, 0, 'kept', 0, NULL, 0)")
                else sqlite.execSQL("INSERT INTO proxy_groups VALUES (1, 1, 0, 'kept', 0, NULL, 0, 0, -1, -1)")
                sqlite.version = version
            }
            val migrated = Room.databaseBuilder(context, SagerDatabase::class.java, name)
                .addMigrations(LegacyMigrations.FROM_1, LegacyMigrations.FROM_2)
                .allowMainThreadQueries().build()
            try {
                val group = migrated.groupDao().allGroups().single()
                assertEquals("kept", group.name)
                assertFalse(group.isSelector)
                assertEquals(-1L, group.frontProxy)
                assertEquals(7, migrated.openHelper.writableDatabase.version)
            } finally { migrated.close() }
        }
    }

    @Test fun renamedSubscriptionNodeKeepsReferencesAndCustomConfig() {
        val entity = db.proxyDao().getById(1)!!
        entity.requireBean().customOutboundJson = "{\"test\":true}"
        db.proxyDao().updateProxy(entity)
        db.rulesDao().insert(listOf(RuleEntity(id = 1, outbound = 1, domains = "example.com")))
        SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(bean("renamed")))
        val restored = db.proxyDao().getAll().single()
        assertEquals(1L, restored.id)
        assertEquals("renamed", restored.requireBean().name)
        assertEquals("{\"test\":true}", restored.requireBean().customOutboundJson)
        assertEquals(1L, db.rulesDao().allRules().single().outbound)
    }
    @Test fun subscriptionCannotDeleteReferencedNode() {
        db.rulesDao().insert(listOf(RuleEntity(id = 1, outbound = 1, domains = "example.com")))
        val replacement = bean("different").apply { serverAddress = "different.example" }
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, listOf(replacement))
        }
        assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
    }
    @Test fun restoreInvalidatesInflightSubscription() {
        val generation = BackupRestore.generation(settings)
        val group = db.groupDao().getById(1)!!
        BackupRestore.restore(BackupPlan(null, null, null, emptyList()), db, settings)
        assertThrows(IllegalArgumentException::class.java) {
            SubscriptionUpdate.commit(db, group, listOf(bean("late")), generation, settings)
        }
        assertEquals("original", db.proxyDao().getAll().single().requireBean().name)
    }
    @Test fun partialRestoreRejectsIdCollision() {
        db.rulesDao().insert(listOf(RuleEntity(id = 1, outbound = 1, domains = "example.com")))
        val other = profile(1, "other").apply { requireBean().serverAddress = "other.example" }
        assertThrows(IllegalArgumentException::class.java) {
            BackupRestore.restore(BackupPlan(listOf(other), db.groupDao().allGroups(), null, null), db, settings)
        }
        assertEquals("example.com", db.proxyDao().getById(1)!!.requireBean().serverAddress)
    }
    @Test fun rulesOnlyRestoreRequiresMatchingSourceIdentity() {
        val other = profile(1, "other").apply { requireBean().serverAddress = "other.example" }
        val plan = BackupPlan(null, null, listOf(RuleEntity(id = 1, outbound = 1)), null,
            listOf(other), db.groupDao().allGroups())
        assertThrows(IllegalArgumentException::class.java) { BackupRestore.restore(plan, db, settings) }
        assertTrue(db.rulesDao().allRules().isEmpty())
    }
    @Test fun clearingTrafficPreservesEditedConfiguration() {
        val edited = db.proxyDao().getById(1)!!.apply { putBean(bean("edited")); rx = 123; tx = 456 }
        db.proxyDao().updateProxy(edited)
        db.proxyDao().clearTraffic(1)
        val result = db.proxyDao().getById(1)!!
        assertEquals("edited", result.requireBean().name)
        assertEquals(0L, result.rx)
        assertEquals(0L, result.tx)
    }

    @Test fun rulesOnlyExportRoundTripsWithoutCredentials() {
        val rules = listOf(RuleEntity(id = 1, outbound = 1, domains = "example.org"))
        val document = BackupDocument.encode(db.proxyDao().getAll(), db.groupDao().allGroups(), rules,
            emptyList(), false, true, false)
        assertFalse(document.has("profiles"))
        assertFalse(document.toString().contains("example.com"))
        BackupRestore.restore(BackupPlan.decode(document, false, true, false), db, settings)
        assertEquals(1L, db.rulesDao().allRules().single().outbound)
        val changed = db.proxyDao().getById(1)!!.apply { requireBean().serverAddress = "other.example" }
        db.proxyDao().updateProxy(changed)
        assertThrows(IllegalArgumentException::class.java) {
            BackupRestore.restore(BackupPlan.decode(document, false, true, false), db, settings)
        }
    }

    @Test fun partialRestoreClearsSelectionEvenWhenIdsCollide() {
        settings.keyValuePairDao().put(KeyValuePair(Key.PROFILE_ID).put(1L))
        val other = profile(1, "other").apply { requireBean().serverAddress = "other.example" }
        BackupRestore.restore(BackupPlan(listOf(other), db.groupDao().allGroups(), null, null), db, settings)
        assertNull(settings.keyValuePairDao()[Key.PROFILE_ID])
        BackupRestore.restore(BackupPlan(null, null, null, listOf(KeyValuePair(Key.PROFILE_ID).put(1L))), db, settings)
        assertNull(settings.keyValuePairDao()[Key.PROFILE_ID])
    }

    @Suppress("DEPRECATION")
    @Test fun malformedTypedSettingsCannotCommit() {
        for ((type, bytes) in listOf(3 to byteArrayOf(), 999 to byteArrayOf(),
            1 to byteArrayOf(2), 4 to ByteArray(9), 7 to byteArrayOf(0, 0, 0, 8))) {
            val invalid = KeyValuePair("appTheme").apply { valueType = type; value = bytes }
            assertThrows(Exception::class.java) {
                BackupRestore.restore(BackupPlan(null, null, null, listOf(invalid)), db, settings)
            }
            assertEquals("old", settings.keyValuePairDao()["theme"]!!.string)
        }
        val validLegacy = KeyValuePair("appTheme").put(3)
        BackupRestore.restore(BackupPlan(null, null, null, listOf(validLegacy)), db, settings)
        assertEquals(3L, settings.keyValuePairDao()["appTheme"]!!.long)
    }

    @Test fun unicodeStringSetsAndLegacyEncodingSurviveBackup() {
        val values = linkedSetOf("ASCII", "中文", "😀")
        val legacyBytes = java.io.ByteArrayOutputStream().also { stream ->
            java.io.DataOutputStream(stream).use { out ->
                values.forEach { out.writeInt(it.length); out.write(it.toByteArray(Charsets.UTF_8)) }
            }
        }.toByteArray()
        for (value in listOf(KeyValuePair("set").put(values), KeyValuePair("set").apply {
            valueType = KeyValuePair.TYPE_STRING_SET; this.value = legacyBytes
        })) {
            val json = BackupDocument.encode(emptyList(), emptyList(), emptyList(), listOf(value), false, false, true)
            BackupRestore.restore(BackupPlan.decode(json, false, false, true), db, settings)
            assertEquals(values, settings.keyValuePairDao()["set"]!!.stringSet)
        }
    }

    @Test fun partialRestoreRejectsChangedSelectorGroupIdentity() {
        db.rulesDao().insert(listOf(RuleEntity(id = 1, outbound = 1, domains = "example.org")))
        val changed = db.groupDao().allGroups().map { it.copy(isSelector = true) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupRestore.restore(BackupPlan(db.proxyDao().getAll(), changed, null, null), db, settings)
        }
        assertFalse(db.groupDao().allGroups().single().isSelector)
    }

}
