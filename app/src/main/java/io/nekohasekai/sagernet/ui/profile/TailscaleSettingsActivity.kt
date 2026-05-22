package io.nekohasekai.sagernet.ui.profile

import android.os.Bundle
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import moe.matsuri.nb4a.proxy.PreferenceBinding
import moe.matsuri.nb4a.proxy.PreferenceBindingManager
import moe.matsuri.nb4a.proxy.Type

class TailscaleSettingsActivity : ProfileSettingsActivity<TailscaleBean>() {

    override fun createEntity() = TailscaleBean()

    private val pbm = PreferenceBindingManager()
    private val name = pbm.add(PreferenceBinding(Type.Text, "name"))
    private val serverAddress = pbm.add(PreferenceBinding(Type.Text, "serverAddress"))
    private val authKey = pbm.add(PreferenceBinding(Type.Text, "authKey"))
    private val controlUrl = pbm.add(PreferenceBinding(Type.Text, "controlUrl"))
    private val ephemeral = pbm.add(PreferenceBinding(Type.Switch, "ephemeral"))
    private val exitNode = pbm.add(PreferenceBinding(Type.Text, "exitNode"))
    private val exitNodeAllowLanAccess = pbm.add(PreferenceBinding(Type.Switch, "exitNodeAllowLanAccess"))
    private val acceptRoutes = pbm.add(PreferenceBinding(Type.Switch, "acceptRoutes"))

    override fun TailscaleBean.init() {
        pbm.writeToCacheAll(this)
    }

    override fun TailscaleBean.serialize() {
        pbm.fromCacheAll(this)
    }

    override fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    ) {
        addPreferencesFromResource(R.xml.tailscale_preferences)
        pbm.setPreferenceFragment(this)
        
        (authKey.preference as EditTextPreference).summaryProvider = PasswordSummaryProvider
    }
}
