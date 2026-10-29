package moe.matsuri.nb4a.proxy.snell

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.preference.EditTextPreferenceModifiers
import io.nekohasekai.sagernet.ktx.applyDefaultValues
import io.nekohasekai.sagernet.ui.profile.ProfileSettingsActivity
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class SnellSettingsActivity : ProfileSettingsActivity<SnellBean>() {
    override fun createEntity() = SnellBean().applyDefaultValues()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val serverPort = pbm.add(PreferenceBinding(Type.TextToInt, "serverPort"))
    private val version = pbm.add(PreferenceBinding(Type.TextToInt, "version"))
    private val psk = pbm.add(PreferenceBinding(Type.Text, "psk"))
    private val userKey = pbm.add(PreferenceBinding(Type.Text, "userKey"))
    private val reuse = pbm.add(PreferenceBinding(Type.Bool, "reuse"))
    private val obfsMode = pbm.add(PreferenceBinding(Type.Text, "obfsMode"))
    private val obfsHost = pbm.add(PreferenceBinding(Type.Text, "obfsHost"))
    private val mode = pbm.add(PreferenceBinding(Type.Text, "mode"))

    override fun SnellBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun SnellBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?
    ) {
        addPreferencesFromResource(R.xml.snell_preferences)

        findPreference<EditTextPreference>(Key.SERVER_PORT)!!.apply {
            setOnBindEditTextListener(EditTextPreferenceModifiers.Port)
        }
        for (key in listOf("psk", "userKey")) {
            findPreference<EditTextPreference>(key)!!.summaryProvider = PasswordSummaryProvider
        }

        val v4Only = listOf("obfsMode", "obfsHost").map { findPreference<Preference>(it)!! }
        val v6Only = findPreference<Preference>("mode")!!
        fun updateVersion(v: String?) {
            v4Only.forEach { it.isVisible = v != "6" }
            v6Only.isVisible = v == "6"
        }
        findPreference<Preference>("version")!!.setOnPreferenceChangeListener { _, newValue ->
            updateVersion(newValue as? String)
            true
        }
        updateVersion(version.readStringFromCache())
    }
}
