package io.nekohasekai.sagernet.database

import android.app.Application
import androidx.room.Room
import androidx.room.RoomDatabase
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class PerAppProxySettingsTest {
    @Test fun modeAndImportedSelectionAreCommittedBeforeOtherConnectionsAndListenersReadThem() {
        val app = RuntimeEnvironment.getApplication()
        app.deleteDatabase("perapp-policy-test")
        fun open() = Room.databaseBuilder(app, PublicDatabase::class.java, "perapp-policy-test")
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE).allowMainThreadQueries().build()
        val writer = open(); val reader = open()
        try {
            val store = RoomPreferenceDataStore(writer.keyValuePairDao())
            val other = RoomPreferenceDataStore(reader.keyValuePairDao())
            assertEquals(PerAppProxySettings(false, true, emptySet()), PerAppProxySettings.read(other))
            val observations = mutableListOf<PerAppProxySettings>()
            store.registerChangeListener(object : OnPreferenceDataStoreChangeListener {
                override fun onPreferenceDataStoreChanged(store: androidx.preference.PreferenceDataStore, key: String) {
                    observations.add(PerAppProxySettings.read(other))
                }
            })
            PerAppProxySettings.importSelection(false, "fixture.browser\nfixture.chat", store)
            val imported = PerAppProxySettings(true, false, setOf("fixture.browser", "fixture.chat"))
            assertEquals(listOf(imported, imported, imported), observations)
            observations.clear()
            PerAppProxySettings.setMode(false, false, store)
            val disabled = imported.copy(enabled = false)
            assertEquals(listOf(disabled, disabled), observations)
            assertEquals("fixture.browser\nfixture.chat", store.getString(Key.INDIVIDUAL))
            writer.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_mode BEFORE INSERT ON KeyValuePair WHEN NEW.key = 'bypassMode' BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
            observations.clear()
            assertThrows(Exception::class.java) { PerAppProxySettings.setMode(true, true, store) }
            assertEquals(disabled, PerAppProxySettings.read(other))
            assertTrue(observations.isEmpty())
        } finally { reader.close(); writer.close(); app.deleteDatabase("perapp-policy-test") }
    }
}
