package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.RoomPreferenceDataStore

/** One query observes one committed per-app configuration, across UI and VPN processes. */
data class PerAppProxySettings(val enabled: Boolean, val bypass: Boolean, val packages: Set<String>) {
    companion object {
        val keys = listOf(Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL)
        fun read(store: RoomPreferenceDataStore = DataStore.configurationStore): PerAppProxySettings {
            val values = store.getAll(keys).associateBy { it.key }
            return PerAppProxySettings(values[Key.PROXY_APPS]?.boolean ?: false,
                values[Key.BYPASS_MODE]?.boolean ?: true,
                values[Key.INDIVIDUAL]?.string.orEmpty().lineSequence().filter { it.isNotBlank() }.toSet())
        }
        fun setMode(enabled: Boolean, bypass: Boolean,
                    store: RoomPreferenceDataStore = DataStore.configurationStore) {
            store.putAll(listOf(KeyValuePair(Key.PROXY_APPS).put(enabled), KeyValuePair(Key.BYPASS_MODE).put(bypass)))
        }
        fun importSelection(bypass: Boolean, packages: String,
                            store: RoomPreferenceDataStore = DataStore.configurationStore) {
            // Import is an explicit user action; commit mode and list as one policy.
            store.putAll(listOf(KeyValuePair(Key.PROXY_APPS).put(true),
                KeyValuePair(Key.BYPASS_MODE).put(bypass), KeyValuePair(Key.INDIVIDUAL).put(packages)))
        }
    }
}
