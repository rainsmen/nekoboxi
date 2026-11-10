package io.nekohasekai.sagernet.database

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.group.SubscriptionUpdate
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ktx.parseProxies
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [io.nekohasekai.sagernet.bg.QuietNativeLog::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class SubscriptionFailureSafetyTest {
    private lateinit var db: SagerDatabase
    private lateinit var settings: PublicDatabase
    private lateinit var store: RoomPreferenceDataStore
    private fun openProfiles() = Room.databaseBuilder(RuntimeEnvironment.getApplication(), SagerDatabase::class.java, "failure-profiles")
        .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
    @Before fun setup() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("failure-profiles"); app.deleteDatabase("failure-settings")
        db = openProfiles()
        settings = Room.databaseBuilder(app, PublicDatabase::class.java, "failure-settings")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        store = RoomPreferenceDataStore(settings.keyValuePairDao())
        db.groupDao().insert(listOf(ProxyGroup(id = 1, name = "group", type = GroupType.SUBSCRIPTION,
            subscription = SubscriptionBean().applyDefaultValues().apply {
                link = "https://subscription.example"; lastUpdated = 10; subscriptionUserinfo = "old metadata"
            })))
        db.proxyDao().insert(listOf(ProxyEntity(id = 1, groupId = 1).apply { putBean(SOCKSBean().applyDefaultValues().apply {
            name = "saved"; serverAddress = "old.example"; customConfigJson = "{\"log\":{\"level\":\"debug\"}}"
        }) }))
    }
    @After fun cleanup() { db.close(); settings.close() }
    @Test fun nativeRejectionCannotReplaceASameNameNodeOrAdvanceMetadata() {
        store.putLong(Key.PROFILE_ID, 1)
        val original = db.proxyDao().getById(1)!!.requireBean().clone()
        val input = """{"outbounds":[{"type":"fixture-invalid","tag":"saved","server":"new.example","server_port":1080}]}"""
        val error = assertThrows(DataValidationException::class.java) {
            runBlocking { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, RawUpdater.parseRaw(input)!!) }
        }
        assertEquals(DataValidationException.Reason.INVALID_SUBSCRIPTION_FIELD, error.reason)
        assertEquals(original, db.proxyDao().getById(1)!!.requireBean())
        assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
        assertEquals(1L, store.getLong(Key.PROFILE_ID))
    }

    @Test fun formatKeywordsInNamesDoNotChangeDocumentDetection() = runBlocking {
        for (name in listOf("proxies:", "[Interface]")) {
            val json = org.json.JSONObject().put("type", "socks").put("tag", name)
                .put("server", "example.org").put("server_port", 1080)
            assertEquals(name, RawUpdater.parseRaw(json.toString())!!.single().name)
            assertEquals(name, RawUpdater.parseRaw("socks5://example.org:1080#$name")!!.single().name)
        }
    }

    @Test fun fieldErrorsIdentifyTheEntryWithoutEchoingCredentials() {
        val text = """
            [Interface]
            Address = 10.0.0.1/32
            PrivateKey = secret-private-key
            [Peer]
            PublicKey = synthetic-public-key
            Endpoint = example.org:invalid
        """.trimIndent()
        val error = assertThrows(DataValidationException::class.java) { runBlocking { RawUpdater.parseRaw(text) } }
        assertEquals(DataValidationException.Reason.INVALID_SUBSCRIPTION_FIELD, error.reason)
        assertEquals(listOf("WireGuard", 1, "Peer.Endpoint.port"), error.details.toList())
        assertFalse(error.message!!.contains("secret"))
        val valid = runBlocking { RawUpdater.parseRaw(text.replace(":invalid", ":443")) }!!.single()
            as io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
        assertEquals(1420, valid.mtu.toInt())
        assertEquals(443, valid.serverPort.toInt())
    }

    @Test fun partialParseNeverReplacesThePreviousSubscription() {
        val mixed = "hy2://secret@a.example:443#A\nhy2://secret@b.example:443?upmbps=invalid#B"
        for (text in listOf(mixed, Base64.getEncoder().encodeToString(mixed.toByteArray()), mixed.replace('\n', ' '))) {
            val original = db.proxyDao().getById(1)!!.requireBean().clone()
            val error = assertThrows(DataValidationException::class.java) {
                runBlocking { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, RawUpdater.parseRaw(text)!!) }
            }
            assertEquals(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, error.reason)
            assertFalse(error.message!!.contains("secret"))
            assertEquals(original, db.proxyDao().getById(1)!!.requireBean())
            assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
            assertEquals("old metadata", db.groupDao().getById(1)!!.subscription!!.subscriptionUserinfo)
        }
    }
    @Test fun malformedDelimitersAndJsonTypesCannotBecomePartialUpdates() {
        val valid = "hy2://secret@a.example:443#A"
        val mixed = "$valid\nhy2:/secret@b.example:443#B"
        val json = """{"outbounds":[{"type":"socks","tag":"A","server":"a.example","server_port":1080},{"type":123,"tag":"B","server":"b.example","server_port":1080}]}"""
        for (text in listOf(mixed, mixed.replace('\n', ' '), Base64.getEncoder().encodeToString(mixed.toByteArray()), json,
            """[{"type":"socks","server":"a.example","server_port":1080},42]""")) {
            assertThrows(DataValidationException::class.java) {
                runBlocking { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, RawUpdater.parseRaw(text)!!) }
            }
            assertEquals("saved", db.proxyDao().getAll().single().requireBean().name)
            assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
            assertEquals("old metadata", db.groupDao().getById(1)!!.subscription!!.subscriptionUserinfo)
        }
        runBlocking { assertEquals(1, parseProxies("# explanation\nNotes: example\n$valid").size) }
    }

    @Test fun linkBoundaryKeepsNamesWithSpacesAndMultipleNodes() = runBlocking {
        val nodes = parseProxies("hy2://p@a.example:443#Hong Kong 01 hy2://p@b.example:443#Second Node")
        assertEquals(listOf("Hong Kong 01", "Second Node"), nodes.map { it.name })
    }
    @Test fun unsupportedClashEntryCannotBecomeADeletion() {
        val text = """
            proxies:
              - {name: valid, type: socks5, server: a.example, port: 443}
              - {name: unsupported, type: future-protocol, server: b.example, port: 443}
        """.trimIndent()
        assertThrows(DataValidationException::class.java) { runBlocking { RawUpdater.parseRaw(text) } }
        assertEquals(listOf(1L), db.proxyDao().getAll().map { it.id })
    }
    @Test fun knownClashAndWireGuardMalformedEntriesNeverProduceAPartialUpdate() {
        val clash = """
            proxies:
              - {name: A, type: socks5, server: a.example, port: 1080}
              - {name: B, type: vmess, server: b.example, port: invalid, uuid: placeholder}
        """.trimIndent()
        val snell = clash.replace("type: vmess", "type: snell, version: 3").replace("port: invalid", "port: 443")
        val wg = """
            [Interface]
            Address = 10.0.0.1/32
            PrivateKey = test
            [Peer]
            Endpoint = a.example:443
            PublicKey = first
            [Peer]
            Endpoint = b.example:invalid
            PublicKey = second
        """.trimIndent()
        for (text in listOf(clash, snell, wg)) {
            assertThrows(DataValidationException::class.java) {
                runBlocking { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, RawUpdater.parseRaw(text)!!) }
            }
            assertEquals("saved", db.proxyDao().getAll().single().requireBean().name)
            assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
        }
    }

    @Test fun oldRequestCannotOverwriteFailureStatusAfterRestore() {
        val request = db.groupDao().getById(1)!!
        val generation = io.nekohasekai.sagernet.database.backup.BackupRestore.generation(settings)
        val restored = db.groupDao().getById(1)!!.apply {
            subscription!!.lastAttempted = 123L; subscription!!.lastError = "restored failure"
        }
        io.nekohasekai.sagernet.database.backup.BackupRestore.restore(
            io.nekohasekai.sagernet.database.backup.BackupPlan(db.proxyDao().getAll(), listOf(restored), null, null), db, settings)
        SubscriptionUpdate.recordFailure(db, request, "obsolete request", generation, settings)
        assertEquals(123L, db.groupDao().getById(1)!!.subscription!!.lastAttempted)
        assertEquals("restored failure", db.groupDao().getById(1)!!.subscription!!.lastError)
    }

    @Test fun cancellationAfterDatabaseWritesRollsBackNodesAndSuccessMetadata() = runBlocking {
        val job = kotlinx.coroutines.Job()
        var checks = 0
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!,
                listOf(SOCKSBean().applyDefaultValues().apply { name = "replacement" })) {
                if (++checks == 2) job.cancel()
                if (!job.isActive) throw kotlinx.coroutines.CancellationException("cancelled while committing")
            }
        }
        assertEquals(2, checks)
        assertEquals("saved", db.proxyDao().getAll().single().requireBean().name)
        assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
        assertEquals("old metadata", db.groupDao().getById(1)!!.subscription!!.subscriptionUserinfo)
    }

    @Test fun changesToRequestOrConversionSettingsRejectInflightResults() {
        val changes: List<(SubscriptionBean) -> Unit> = listOf(
            { it.customUserAgent = "new agent" }, { it.forceResolve = true }, { it.deduplication = true },
            { it.updateWhenConnectedOnly = true })
        for (change in changes) {
            val before = db.groupDao().getById(1)!!
            val edited = db.groupDao().getById(1)!!
            change(edited.subscription!!)
            db.groupDao().updateGroup(edited)
            val error = assertThrows(DataValidationException::class.java) {
                SubscriptionUpdate.commit(db, before, listOf(SOCKSBean().applyDefaultValues().apply { name = "replacement"; serverAddress = "new.example" }))
            }
            assertEquals(DataValidationException.Reason.SUBSCRIPTION_CHANGED, error.reason)
            assertEquals(listOf(1L), db.proxyDao().getAll().map { it.id })
            assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
            db.groupDao().updateGroup(before)
        }
    }
    @Test fun clearingAGroupWaitsForMoveAndKeepsItsCurrentMembership() {
        db.groupDao().insert(listOf(ProxyGroup(id = 2, name = "destination")))
        db.proxyDao().insert(listOf(ProxyEntity(id = 2, groupId = 1).apply { putBean(SOCKSBean().applyDefaultValues()) }))
        store.putLong(Key.PROFILE_ID, 1)
        val other = openProfiles()
        other.proxyDao().getAll()
        val executor = Executors.newSingleThreadExecutor()
        val started = CountDownLatch(1)
        try {
            lateinit var clear: java.util.concurrent.Future<*>
            db.runInTransaction {
                val moving = db.proxyDao().getById(1)!!.apply { groupId = 2 }
                db.proxyDao().updateProxy(moving)
                clear = executor.submit { started.countDown(); runBlocking { GroupManager.clearGroup(1, other, settings) } }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFalse(clear.isDone)
            }
            clear.get(5, TimeUnit.SECONDS)
            assertEquals(2L, db.proxyDao().getById(1)!!.groupId)
            assertNull(db.proxyDao().getById(2))
            assertNotNull(db.groupDao().getById(1))
            assertEquals(1L, store.getLong(Key.PROFILE_ID, 0L))
        } finally { executor.shutdownNow(); other.close() }
    }
    @Test fun trailingJsonAndMissingClashFieldsCannotReplaceSavedNodes() {
        val json = """{"type":"socks","tag":"A","server":"a.example","server_port":1080}"""
        for (text in listOf("$json\n$json", "$json garbage", "proxies:\n  - {name: saved, type: anytls, password: p}",
                "proxies:\n  - {name: saved, type: anytls, server: a.example, port: 0, password: p}")) {
            assertThrows(DataValidationException::class.java) {
                runBlocking { SubscriptionUpdate.commit(db, db.groupDao().getById(1)!!, RawUpdater.parseRaw(text)!!) }
            }
            assertEquals("old.example", db.proxyDao().getById(1)!!.requireBean().serverAddress)
            assertEquals(10, db.groupDao().getById(1)!!.subscription!!.lastUpdated)
        }
        runBlocking { assertEquals(1, RawUpdater.parseRaw("$json \n\t")!!.size) }
    }

    @Test fun standaloneEndpointsAreImportedAndDnsDependenciesAreExplicitlyRejected() {
        val endpoint = org.json.JSONObject("""{"type":"wireguard","tag":"wg","address":["10.0.0.1/32"],"private_key":"key","peers":[]}""")
        val outbound = org.json.JSONObject("""{"type":"socks","tag":"proxy","server":"proxy.example","server_port":1080}""")
        val root = org.json.JSONObject().put("endpoints", org.json.JSONArray().put(endpoint))
        assertEquals("wg", RawUpdater.parseJSON(root).single().name)
        assertEquals("wg", RawUpdater.parseJSON(endpoint).single().name)
        root.put("outbounds", org.json.JSONArray().put(outbound))
        assertEquals(setOf("proxy", "wg"), RawUpdater.parseJSON(root).map { it.name }.toSet())
        outbound.put("domain_resolver", org.json.JSONObject().put("server", "bootstrap"))
        root.put("dns", org.json.JSONObject("""{"servers":[{"type":"udp","tag":"bootstrap","server":"1.1.1.1"}]}"""))
        assertEquals(DataValidationException.Reason.OUTBOUND_DEPENDENCY,
            assertThrows(DataValidationException::class.java) { RawUpdater.parseJSON(root) }.reason)
    }

    @Test fun strictClashValidationPreservesHysteriaPortRangesAndPortsOverride() = runBlocking {
        for (type in listOf("hysteria", "hysteria2")) for (field in listOf("port", "ports")) {
            val nodes = RawUpdater.parseRaw("proxies:\n  - {name: hop, type: $type, server: a.example, $field: '443-445,8443', password: p}")!!
            assertEquals("443-445,8443", (nodes.single() as io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean).serverPorts)
        }
    }

}
