package io.nekohasekai.sagernet.database

import androidx.sqlite.db.SupportSQLiteDatabase
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.ktx.Logs
import java.util.concurrent.Callable

/** Commit profile mutations and their selection cleanup in one rollback-journal transaction. */
object AttachedSettingsTransaction {
    const val TABLE = "mutation_settings.KeyValuePair"

    fun read(db: SupportSQLiteDatabase, key: String): io.nekohasekai.sagernet.database.preference.KeyValuePair? =
        db.query("SELECT valueType, value FROM $TABLE WHERE `key` = ?", arrayOf(key)).use { cursor ->
            if (!cursor.moveToFirst()) null else io.nekohasekai.sagernet.database.preference.KeyValuePair(key).apply {
                valueType = cursor.getInt(0)
                value = cursor.getBlob(1)
            }
        }

    fun <T> run(profiles: SagerDatabase, settings: PublicDatabase?,
                action: (SagerDatabase, SupportSQLiteDatabase?) -> T): T {
        if (settings == null) return profiles.runInTransaction(Callable { action(profiles, null) })
        return withConnection(profiles, settings) { isolated, sqlite ->
            isolated.runInTransaction(Callable { action(isolated, sqlite) })
        }
    }

    internal fun <T> withConnection(profiles: SagerDatabase, settings: PublicDatabase,
                                   action: (SagerDatabase, SupportSQLiteDatabase) -> T): T {
        val settingsDb = settings.openHelper.writableDatabase
        require(!profiles.openHelper.writableDatabase.isWriteAheadLoggingEnabled && !settingsDb.isWriteAheadLoggingEnabled)
        val isolated = profiles.isolatedConnection()
        try {
            val sqlite = isolated.openHelper.writableDatabase
            sqlite.execSQL("ATTACH DATABASE ? AS mutation_settings", arrayOf(settingsDb.path))
            return action(isolated, sqlite)
        } finally {
            // Closing the private connection removes attachments even after rollback or cleanup failure.
            runCatching { isolated.close() }.onFailure(Logs::w)
            runCatching { profiles.invalidationTracker.notifyObserversByTableNames("proxy_entities", "proxy_groups", "rules") }.onFailure(Logs::w)
            runCatching { settings.invalidationTracker.notifyObserversByTableNames("KeyValuePair") }.onFailure(Logs::w)
        }
    }
}
