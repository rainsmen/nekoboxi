package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Parcel
import android.os.Parcelable
import android.provider.OpenableColumns
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jakewharton.processphoenix.ProcessPhoenix
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.Executable
import io.nekohasekai.sagernet.bg.ServiceStopper
import io.nekohasekai.sagernet.database.backup.BackupExportSnapshot
import io.nekohasekai.sagernet.database.backup.BackupPlan
import io.nekohasekai.sagernet.database.backup.BackupRestore
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.database.preference.KeyValuePair
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.databinding.LayoutBackupBinding
import io.nekohasekai.sagernet.databinding.LayoutImportBinding
import io.nekohasekai.sagernet.databinding.LayoutProgressBinding
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.database.backup.BackupShareFile
import moe.matsuri.nb4a.utils.Util
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.*

class BackupFragment : NamedFragment(R.layout.layout_backup) {

    override fun name0() = app.getString(R.string.backup)

    internal var pendingExport: String? = null
    internal var writeShare: (String) -> File = { BackupShareFile(app.cacheDir).create(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pendingExport")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExport", pendingExport)
        super.onSaveInstanceState(outState)
    }

    private val exportSettings =
        registerForActivityResult(ActivityResultContracts.CreateDocument()) { data ->
            val context = requireContext().applicationContext
            val token = pendingExport
            runOnDefaultDispatcher {
                val snapshot = BackupExportSnapshot(context.cacheDir)
                try {
                    if (data != null) {
                        checkNotNull(token) { context.getString(R.string.backup_export_expired) }
                        snapshot.write(token) {
                            checkNotNull(context.contentResolver.openOutputStream(data))
                        }
                        onMainDispatcher {
                            if (isAdded) snackbar(getString(R.string.action_export_msg)).show()
                        }
                    }
                    token?.let(snapshot::discard)
                    onMainDispatcher { pendingExport = null }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (isAdded) snackbar(getString(R.string.backup_export_failed, e.readableMessage)).show()
                    }
                }
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val binding = LayoutBackupBinding.bind(view)

        binding.resetSettings.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.confirm)
                .setMessage(R.string.reset_settings_message)
                .setNegativeButton(R.string.no, null)
                .setPositiveButton(R.string.yes) { _, _ ->
                    val context = requireContext().applicationContext
                    runOnDefaultDispatcher {
                        runCatching {
                            ServiceStopper.withStopped(context) { service ->
                                DataStore.configurationStore.reset()
                                restartStoppedApp(context, service)
                            }
                        }.onFailure { error -> onMainDispatcher {
                            if (isAdded) alert(getString(R.string.restart_failed, error.readableMessage)).show()
                        } }
                    }
                }
                .show()
        }

        binding.actionExport.setOnClickListener {
            val profile = binding.backupConfigurations.isChecked
            val rules = binding.backupRules.isChecked
            val settings = binding.backupSettings.isChecked
            binding.actionExport.isEnabled = false
            runOnDefaultDispatcher {
                try {
                    val snapshot = BackupExportSnapshot(app.cacheDir)
                    val token = snapshot.stage(doBackup(profile, rules, settings))
                    onMainDispatcher {
                        pendingExport?.let(snapshot::discard)
                        pendingExport = token
                        if (isAdded) startFilesForResult(exportSettings, "thbox_backup_${Date().toLocaleString()}.json")
                    }
                } catch (e: Exception) {
                    Logs.w(e)
                    onMainDispatcher {
                        if (isAdded) snackbar(getString(R.string.backup_export_failed, e.readableMessage)).show()
                    }
                } finally {
                    onMainDispatcher { binding.actionExport.isEnabled = true }
                }
            }
        }

        binding.actionShare.setOnClickListener {
            val profiles = binding.backupConfigurations.isChecked
            val rules = binding.backupRules.isChecked
            val settings = binding.backupSettings.isChecked
            binding.actionShare.isEnabled = false
            viewLifecycleOwner.lifecycleScope.launch {
                var cacheFile: File? = null
                var shared = false
                try {
                    withContext(Dispatchers.IO) { cacheFile = writeShare(doBackup(profiles, rules, settings)) }
                    val uri = FileProvider.getUriForFile(app, BuildConfig.APPLICATION_ID + ".cache", requireNotNull(cacheFile))
                    startActivity(Intent.createChooser(
                        Intent(Intent.ACTION_SEND).setType("application/json")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_STREAM, uri),
                        getString(androidx.appcompat.R.string.abc_shareactionprovider_share_with)))
                    shared = true
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (this@BackupFragment.view === binding.root) Snackbar.make(binding.root,
                        getString(R.string.backup_share_failed, error.readableMessage), Snackbar.LENGTH_LONG).show()
                } finally {
                    if (!shared) cacheFile?.delete()
                    if (this@BackupFragment.view === binding.root) binding.actionShare.isEnabled = true
                }
            }
        }

        binding.actionImportFile.setOnClickListener {
            startFilesForResult(importFile, "*/*")
        }
    }

    fun doBackup(profile: Boolean, rule: Boolean, setting: Boolean): String {
        val out = SagerDatabase.instance.runInTransaction(java.util.concurrent.Callable {
            io.nekohasekai.sagernet.database.backup.BackupDocument.encode(
                SagerDatabase.proxyDao.getAll(), SagerDatabase.groupDao.allGroups(),
                if (rule) SagerDatabase.rulesDao.allRules() else emptyList(),
                if (setting) PublicDatabase.kvPairDao.all() else emptyList(),
                profile, rule, setting,
            )
        })
        return out.toStringPretty()
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { file ->
        if (file != null) {
            runOnDefaultDispatcher {
                startImport(file)
            }
        }
    }

    suspend fun startImport(file: Uri) {
        suspend fun readFailed(error: Exception) = onMainDispatcher {
            snackbar(getString(R.string.backup_read_failed, error.readableMessage)).show()
        }
        val resolver = requireContext().contentResolver
        val fileName = try {
            resolver.query(file, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
            }?.takeIf { it.isNotBlank() } ?: file.lastPathSegment.orEmpty().substringAfterLast('/').substringAfter(':')
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { readFailed(error); return }

        if (!fileName.endsWith(".json", ignoreCase = true)) {
            onMainDispatcher { snackbar(getString(R.string.backup_not_file, fileName)).show() }
            return
        }
        suspend fun invalid() = onMainDispatcher { snackbar(getString(R.string.invalid_backup_file)).show() }
        val text = try {
            val input = resolver.openInputStream(file) ?: throw java.io.IOException(getString(R.string.asset_cannot_read))
            input.use { it.bufferedReader().readText() }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { Logs.w(error); readFailed(error); return }
        val content = try {
            val tokens = org.json.JSONTokener(text)
            val value = tokens.nextValue()
            require(value is JSONObject && tokens.nextClean() == '\u0000')
            value
        } catch (error: Exception) { Logs.w(error); invalid(); return }
        val version = content.optInt("version", 0)
        if (version !in 1..2) {
            invalid()
            return
        }

        val currentCounts = listOf(SagerDatabase.groupDao.allGroups().size, SagerDatabase.proxyDao.getAll().size,
            SagerDatabase.rulesDao.allRules().size, PublicDatabase.kvPairDao.all().size)
        onMainDispatcher {
            var confirmation: AlertDialog? = null
            val import = LayoutImportBinding.inflate(layoutInflater)
            if (!content.has("profiles")) {
                import.backupConfigurations.isVisible = false
            }
            if (!content.has("rules")) {
                import.backupRules.isVisible = false
            }
            if (!content.has("settings")) {
                import.backupSettings.isVisible = false
            }
            fun updateRestoreSummary() {
                val sections = mutableListOf<String>()
                if (content.has("profiles") && import.backupConfigurations.isChecked) {
                    sections += getString(R.string.restore_count_groups, content.optJSONArray("groups")?.length() ?: 0)
                    sections += getString(R.string.restore_count_profiles, content.optJSONArray("profiles")?.length() ?: 0)
                }
                if (content.has("rules") && import.backupRules.isChecked)
                    sections += getString(R.string.restore_count_rules, content.optJSONArray("rules")?.length() ?: 0)
                if (content.has("settings") && import.backupSettings.isChecked)
                    sections += getString(R.string.restore_count_settings, content.optJSONArray("settings")?.length() ?: 0)
                import.restoreSummary.text = getString(R.string.restore_selection_summary, sections.joinToString("\n")) +
                    "\n\n" + getString(R.string.restore_replace_counts, currentCounts[0], currentCounts[1], currentCounts[2], currentCounts[3])
                confirmation?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = sections.isNotEmpty()
            }
            listOf(import.backupConfigurations, import.backupRules, import.backupSettings).forEach {
                it.setOnCheckedChangeListener { _, _ -> updateRestoreSummary() }
            }
            updateRestoreSummary()
            confirmation = MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.backup_import)
                .setView(import.root)
                .setPositiveButton(R.string.backup_import) { _, _ ->
                    val restoreContext = requireContext().applicationContext
                    val binding = LayoutProgressBinding.inflate(layoutInflater)
                    binding.content.text = getString(R.string.backup_importing)
                    val dialog = AlertDialog.Builder(requireContext())
                        .setView(binding.root)
                        .setCancelable(false)
                        .show()
                    runOnDefaultDispatcher {
                        var committed = false
                        runCatching {
                            val plan = BackupPlan.decode(
                                content,
                                content.has("profiles") && import.backupConfigurations.isChecked,
                                content.has("rules") && import.backupRules.isChecked,
                                content.has("settings") && import.backupSettings.isChecked,
                            )
                            plan.validateReferences(SagerDatabase.proxyDao.getAll(), SagerDatabase.groupDao.allGroups(), SagerDatabase.rulesDao.allRules())
                            ServiceStopper.withStopped(restoreContext) { service ->
                                BackupRestore.restore(plan)
                                committed = true
                                restartStoppedApp(restoreContext, service)
                            }
                        }.onFailure {
                            Logs.w(it)
                            onMainDispatcher {
                                if (isAdded) requireContext().showDataError(it, getString(
                                    if (committed) R.string.restore_restart_failed else R.string.restore_data_kept,
                                    it.readableMessage))
                            }
                        }

                        onMainDispatcher {
                            dialog.dismiss()
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            updateRestoreSummary()
        }
    }

}
