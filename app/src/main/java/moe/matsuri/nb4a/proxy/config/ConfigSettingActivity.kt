package moe.matsuri.nb4a.proxy.config

import android.os.Bundle
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.ui.EditConfigPreference

class ConfigSettingActivity :
    ProfileSettingsActivity<ConfigBean>(),
    OnPreferenceDataStoreChangeListener {

    private val isOutboundOnlyKey = "isOutboundOnly"
    private val dependenciesKey = "outboundDependencies"

    override fun createEntity() = ConfigBean()

    override fun ConfigBean.init() {
        // CustomBean to input
        DataStore.profileCacheStore.putBoolean(isOutboundOnlyKey, type == 1)
        DataStore.profileCacheStore.putBoolean("inheritGlobalTls", inheritGlobalTls)
        DataStore.profileName = name
        DataStore.serverConfig = config
        DataStore.profileCacheStore.putString(dependenciesKey, outboundDependencies)
    }

    override fun ConfigBean.serialize() {
        // CustomBean from input
        type = if (DataStore.profileCacheStore.getBoolean(isOutboundOnlyKey, false)) 1 else 0
        inheritGlobalTls = DataStore.profileCacheStore.getBoolean("inheritGlobalTls", false)
        name = DataStore.profileName
        config = DataStore.serverConfig
        outboundDependencies = DataStore.profileCacheStore.getString(dependenciesKey).orEmpty()
        if (type == 1) OutboundDependencies.build(this, "validation")
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    private lateinit var editConfigPreference: EditConfigPreference
    private lateinit var dependenciesPreference: EditConfigPreference

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.config_preferences)

        editConfigPreference = findPreference(Key.SERVER_CONFIG)!!
        dependenciesPreference = findPreference(dependenciesKey)!!
        dependenciesPreference.configKey = dependenciesKey
        dependenciesPreference.intent = dependenciesPreference.intent!!.putExtra("key", dependenciesKey)
    }

    override fun onResume() {
        super.onResume()

        if (::editConfigPreference.isInitialized) {
            editConfigPreference.notifyChanged()
            dependenciesPreference.notifyChanged()
        }
    }

}