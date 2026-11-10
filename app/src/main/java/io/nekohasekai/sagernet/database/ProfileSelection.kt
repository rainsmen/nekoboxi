package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore
import java.util.concurrent.Callable

/** The profile database write lock also serializes selection in other Android processes. */
object ProfileSelection {
    fun select(id: Long, database: SagerDatabase, settings: RoomPreferenceDataStore, key: String = Key.PROFILE_ID): Boolean =
        database.runInTransaction(Callable {
            if (id != 0L && database.proxyDao().getById(id) == null) false else {
                settings.putLong(key, id)
                true
            }
        })
}
