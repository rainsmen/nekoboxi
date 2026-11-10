package io.nekohasekai.sagernet.ui.profile

import io.nekohasekai.sagernet.database.ProfileMutations
import io.nekohasekai.sagernet.ui.showDataError
import android.annotation.SuppressLint
import android.content.DialogInterface
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import androidx.activity.result.component1
import androidx.activity.result.component2
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.LayoutRes
import androidx.appcompat.app.AlertDialog
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import androidx.preference.Preference
import androidx.preference.PreferenceDataStore
import androidx.preference.PreferenceFragmentCompat
import com.github.shadowsocks.plugin.Empty
import com.github.shadowsocks.plugin.fragment.AlertDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutGroupItemBinding
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.ui.ConfigurationEditorActivity
import io.nekohasekai.sagernet.widget.ListListener
import kotlinx.parcelize.Parcelize
import kotlin.properties.Delegates

@Suppress("UNCHECKED_CAST")
abstract class ProfileSettingsActivity<T : AbstractBean>(
    @LayoutRes resId: Int = R.layout.layout_config_settings,
) : ConfigurationEditorActivity(resId), OnPreferenceDataStoreChangeListener {

    class UnsavedChangesDialogFragment : AlertDialogFragment<Empty, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.unsaved_changes_prompt)
            setPositiveButton(R.string.save_and_exit) { _, _ ->
                runOnDefaultDispatcher {
                    (requireActivity() as ProfileSettingsActivity<*>).saveAndExit()
                }
            }
            setNegativeButton(R.string.discard_changes) { _, _ ->
                requireActivity().finish()
            }
            setNeutralButton(R.string.continue_editing, null)
        }
    }

    @Parcelize
    data class ProfileIdArg(val profileId: Long, val groupId: Long, val generation: String) : Parcelable
    class DeleteConfirmationDialogFragment : AlertDialogFragment<ProfileIdArg, Empty>() {
        override fun AlertDialog.Builder.prepare(listener: DialogInterface.OnClickListener) {
            setTitle(R.string.delete_confirm_prompt)
            setPositiveButton(R.string.yes) { _, _ ->
                val activity = requireActivity()
                runOnDefaultDispatcher {
                    try {
                        ProfileManager.deleteProfile(arg.groupId, arg.profileId, arg.generation)
                        onMainDispatcher { activity.finish() }
                    } catch (error: Exception) {
                        onMainDispatcher { activity.showDataError(error) }
                    }
                }
            }
            setNegativeButton(R.string.no, null)
        }
    }

    companion object {
        const val EXTRA_PROFILE_ID = "id"
        const val EXTRA_IS_SUBSCRIPTION = "sub"
    }

    abstract fun createEntity(): T
    abstract fun T.init()
    abstract fun T.serialize()

    val proxyEntity by lazy { SagerDatabase.proxyDao.getById(editingId) }
    private var expectedConfiguration: String? = null
    protected var isSubscription by Delegates.notNull<Boolean>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        expectedConfiguration = savedInstanceState?.getString("expectedConfiguration")
        isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.profile_config)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        if (savedInstanceState == null) {
            val editingId = intent.getLongExtra(EXTRA_PROFILE_ID, 0L)
            isSubscription = intent.getBooleanExtra(EXTRA_IS_SUBSCRIPTION, false)
            DataStore.editingId = editingId
            runOnDefaultDispatcher {
                val entity = if (editingId == 0L) null else proxyEntity
                val groupId = entity?.groupId ?: DataStore.selectedGroupForImport()
                onMainDispatcher {
                    initializeDraft {
                        if (editingId != 0L && entity == null) { finish(); return@initializeDraft }
                        DataStore.editingId = editingId
                        DataStore.editingGroup = groupId
                        if (entity == null) createEntity().applyDefaultValues().init()
                        else {
                            expectedConfiguration = ProfileMutations.configurationFingerprint(entity.requireBean())
                            (entity.requireBean() as T).init()
                        }
                        DataStore.dirty = false
                        supportFragmentManager.beginTransaction()
                            .replace(R.id.settings, MyPreferenceFragmentCompat()).commit()
                    }
                }
            }


        }

    }

    data class ValidationError(val preferenceKey: String?, val message: String)
    protected open fun validateSettings(): ValidationError? = null

    open suspend fun saveAndExit() {
        try {
            val candidate = onMainDispatcher {
                requireEditorSession()
                validateSettings()?.let { error ->
                    error.preferenceKey?.let { key ->
                        supportFragmentManager.fragments.filterIsInstance<PreferenceFragmentCompat>()
                            .firstOrNull()?.let { fragment ->
                                if (fragment.findPreference<Preference>(key) != null) fragment.scrollToPreference(key)
                            }
                    }
                    alert(error.message).show()
                    return@onMainDispatcher null
                }
                if (editingId == 0L) {
                    ProxyEntity(groupId = DataStore.editingGroup).putBean(createEntity().apply { serialize() })
                } else {
                    val original = proxyEntity ?: throw io.nekohasekai.sagernet.database.DataValidationException(
                        io.nekohasekai.sagernet.database.DataValidationException.Reason.CONFIG_CHANGED)
                    original.copy().putBean((original.requireBean().clone() as T).apply { serialize() })
                }
            } ?: return
            if (editingId == 0L) {
                ProfileManager.createProfile(candidate.groupId, candidate.requireBean(), editingGeneration)
            } else {
                if (candidate.id == DataStore.selectedProxy) SagerNet.stopService()
                ProfileManager.updateProfile(candidate, expectedConfiguration
                    ?: throw io.nekohasekai.sagernet.database.DataValidationException(
                        io.nekohasekai.sagernet.database.DataValidationException.Reason.CONFIG_CHANGED), editingGeneration)
            }
            onMainDispatcher { finish() }
        } catch (error: Exception) {
            onMainDispatcher { showDataError(error) }
        }
    }

    val child by lazy { supportFragmentManager.findFragmentById(R.id.settings) as MyPreferenceFragmentCompat }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.profile_config_menu, menu)
        menu.findItem(R.id.action_move)?.apply {
            if (editingId != 0L // not new profile
                && SagerDatabase.groupDao.getById(DataStore.editingGroup)?.type == GroupType.BASIC // not in subscription group
                && SagerDatabase.groupDao.allGroups()
                    .filter { it.type == GroupType.BASIC }.size > 1 // have other basic group
            ) isVisible = true
        }
        menu.findItem(R.id.action_create_shortcut)?.apply {
            if (Build.VERSION.SDK_INT >= 26 && editingId != 0L) {
                isVisible = true // not new profile
            }
        }
        // shared menu item
        menu.findItem(R.id.action_custom_outbound_json)?.isVisible = true
        menu.findItem(R.id.action_custom_config_json)?.isVisible = true
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem) = child.onOptionsItemSelected(item)

    override fun onBackPressed() {
        if (DataStore.dirty) UnsavedChangesDialogFragment().apply { key() }
            .show(supportFragmentManager, null) else super.onBackPressed()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressed()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("expectedConfiguration", expectedConfiguration)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        DataStore.profileCacheStore.unregisterChangeListener(this)
        super.onDestroy()
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        if (key != Key.PROFILE_DIRTY) {
            DataStore.dirty = true
        }
    }

    abstract fun PreferenceFragmentCompat.createPreferences(
        savedInstanceState: Bundle?,
        rootKey: String?,
    )

    open fun PreferenceFragmentCompat.viewCreated(view: View, savedInstanceState: Bundle?) {
    }

    open fun PreferenceFragmentCompat.displayPreferenceDialog(preference: Preference): Boolean {
        return false
    }

    class MyPreferenceFragmentCompat : PreferenceFragmentCompat() {

        var activity: ProfileSettingsActivity<*>? = null

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.preferenceDataStore = DataStore.profileCacheStore
            try {
                activity = (requireActivity() as ProfileSettingsActivity<*>).apply {
                    createPreferences(savedInstanceState, rootKey)
                }
            } catch (e: Exception) {
                Toast.makeText(
                    SagerNet.application,
                    "Error on createPreferences, please try again.",
                    Toast.LENGTH_SHORT
                ).show()
                Logs.e(e)
            }
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)

            ViewCompat.setOnApplyWindowInsetsListener(listView, ListListener)

            activity?.apply {
                viewCreated(view, savedInstanceState)
                DataStore.profileCacheStore.registerChangeListener(this)
            }
        }

        var callbackCustom: ((String) -> Unit)? = null
        var callbackCustomOutbound: ((String) -> Unit)? = null

        val resultCallbackCustom = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustom?.let { it(DataStore.serverCustom) }
        }

        val resultCallbackCustomOutbound = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { (_, _) ->
            callbackCustomOutbound?.let { it(DataStore.serverCustomOutbound) }
        }

        @SuppressLint("CheckResult")
        override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
            R.id.action_delete -> {
                val editor = requireActivity() as ProfileSettingsActivity<*>
                if (editor.editingId == 0L) {
                    requireActivity().finish()
                } else {
                    DeleteConfirmationDialogFragment().apply {
                        arg(
                            ProfileIdArg(
                                editor.editingId, editor.proxyEntity!!.groupId, editor.editingGeneration
                            )
                        )
                        key()
                    }.show(parentFragmentManager, null)
                }
                true
            }

            R.id.action_apply -> {
                runOnDefaultDispatcher {
                    activity?.saveAndExit()
                }
                true
            }

            R.id.action_custom_outbound_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustomOutbound = bean.customOutboundJson
                    callbackCustomOutbound = { bean.customOutboundJson = it }
                    resultCallbackCustomOutbound.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM_OUTBOUND)
                        })
                }
                true
            }

            R.id.action_custom_config_json -> {
                activity?.proxyEntity?.apply {
                    val bean = requireBean()
                    DataStore.serverCustom = bean.customConfigJson
                    callbackCustom = { bean.customConfigJson = it }
                    resultCallbackCustom.launch(
                        Intent(
                            requireContext(),
                            ConfigEditActivity::class.java
                        ).apply {
                            putExtra("key", Key.SERVER_CUSTOM)
                        })
                }
                true
            }

            R.id.action_create_shortcut -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val ent = activity.proxyEntity!!
                val shortcut = ShortcutInfoCompat.Builder(activity, "shortcut-profile-${ent.id}")
                    .setShortLabel(ent.displayName())
                    .setLongLabel(ent.displayName())
                    .setIcon(
                        IconCompat.createWithResource(
                            activity, R.drawable.ic_qu_shadowsocks_launcher
                        )
                    ).setIntent(Intent(
                        context, QuickToggleShortcut::class.java
                    ).apply {
                        action = Intent.ACTION_MAIN
                        putExtra("profile", ent.id)
                    }).build()
                ShortcutManagerCompat.requestPinShortcut(activity, shortcut, null)
            }

            R.id.action_move -> {
                val activity = requireActivity() as ProfileSettingsActivity<*>
                val view = LinearLayout(context).apply {
                    val ent = activity.proxyEntity!!
                    orientation = LinearLayout.VERTICAL

                    SagerDatabase.groupDao.allGroups()
                        .filter { it.type == GroupType.BASIC && it.id != ent.groupId }
                        .forEach { group ->
                            LayoutGroupItemBinding.inflate(layoutInflater, this, true).apply {
                                edit.isVisible = false
                                options.isVisible = false
                                groupName.text = group.displayName()
                                groupUpdate.text = getString(R.string.move)
                                groupUpdate.setOnClickListener {
                                    runOnDefaultDispatcher {
                                        try {
                                            activity.requireEditorSession()
                                            val oldGroupId = ent.groupId
                                            val newGroupId = group.id
                                            ProfileManager.moveProfile(ent.id, newGroupId, activity.editingGeneration)
                                            GroupManager.postUpdate(oldGroupId)
                                            GroupManager.postUpdate(newGroupId)
                                            onMainDispatcher { activity.finish() }
                                        } catch (error: Exception) {
                                            onMainDispatcher { activity.showDataError(error) }
                                        }
                                    }
                                }
                            }
                        }
                }
                val scrollView = ScrollView(context).apply {
                    addView(view)
                }
                MaterialAlertDialogBuilder(activity).setView(scrollView).show()
                true
            }

            else -> false
        }

        override fun onDisplayPreferenceDialog(preference: Preference) {
            activity?.apply {
                if (displayPreferenceDialog(preference)) return
            }
            super.onDisplayPreferenceDialog(preference)
        }

    }

    object PasswordSummaryProvider : Preference.SummaryProvider<EditTextPreference> {

        override fun provideSummary(preference: EditTextPreference): CharSequence {
            val text = preference.text
            return if (text.isNullOrBlank()) {
                preference.context.getString(androidx.preference.R.string.not_set)
            } else {
                "\u2022".repeat(text.length)
            }
        }

    }

}