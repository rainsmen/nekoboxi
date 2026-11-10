package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import androidx.annotation.LayoutRes
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.DataValidationException
import io.nekohasekai.sagernet.database.ProfileMutations
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import java.util.UUID

/** Full editors own the shared draft; small selector/JSON child screens edit that same draft. */
abstract class ConfigurationEditorActivity(@LayoutRes layout: Int) : ThemedActivity(layout) {
    val editingId: Long get() = intent.getLongExtra("id", 0L)
    lateinit var editingGeneration: String
        private set
    private lateinit var draftOwner: String

    override fun onCreate(savedInstanceState: Bundle?) {
        editingGeneration = savedInstanceState?.getString("editorGeneration") ?: ProfileMutations.currentGeneration()
        draftOwner = savedInstanceState?.getString("editorDraftOwner") ?: UUID.randomUUID().toString()
        if (savedInstanceState == null) DataStore.profileCacheStore.putString(OWNER_KEY, draftOwner)
        super.onCreate(savedInstanceState)
    }

    fun requireEditorSession() {
        if (DataStore.profileCacheStore.getString(OWNER_KEY) != draftOwner ||
            ProfileMutations.currentGeneration() != editingGeneration) {
            throw DataValidationException(DataValidationException.Reason.CONFIG_CHANGED)
        }
    }

    /** Call on the UI thread, so a new Activity cannot claim the cache halfway through initialization. */
    protected fun initializeDraft(action: () -> Unit) {
        if (isFinishing || isDestroyed) return
        try { requireEditorSession() } catch (_: DataValidationException) { finish(); return }
        action()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("editorGeneration", editingGeneration)
        outState.putString("editorDraftOwner", draftOwner)
        super.onSaveInstanceState(outState)
    }

    internal fun reviewReference(destination: Intent) {
        fun leave() {
            // Unregister now: onDestroy can run after the next editor initializes its draft.
            (this as? OnPreferenceDataStoreChangeListener)?.let {
                DataStore.profileCacheStore.unregisterChangeListener(it)
            }
            startActivity(destination)
            finish()
        }
        if (DataStore.dirty) {
            MaterialAlertDialogBuilder(this).setTitle(R.string.unsaved_changes_prompt)
                .setMessage(R.string.review_reference_discard_notice)
                .setPositiveButton(R.string.review_reference_discard) { _, _ -> leave() }
                .setNegativeButton(R.string.continue_editing, null).show()
        } else leave()
    }

    private companion object { const val OWNER_KEY = "configurationEditorOwner" }
}
