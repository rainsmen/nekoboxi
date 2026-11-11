package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.provider.OpenableColumns
import android.text.format.DateFormat
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isInvisible
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.databinding.LayoutAssetItemBinding
import io.nekohasekai.sagernet.databinding.LayoutAssetsBinding
import io.nekohasekai.sagernet.ktx.*
import io.nekohasekai.sagernet.widget.UndoSnackbarManager
import io.nekohasekai.sagernet.utils.AssetDeletionQueue
import io.nekohasekai.sagernet.utils.AssetImporter
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File
import java.util.*
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class AssetsActivity : ThemedActivity() {

    lateinit var adapter: AssetAdapter
    lateinit var layout: LayoutAssetsBinding
    lateinit var undoManager: UndoSnackbarManager<File>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = LayoutAssetsBinding.inflate(layoutInflater)
        layout = binding
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.route_assets)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        adapter = AssetAdapter()
        binding.recyclerView.adapter = adapter
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    downloads.running.collect { running ->
                        layout.refreshLayout.isEnabled = running.isEmpty()
                        adapter.notifyDataSetChanged()
                    }
                }
                launch {
                    downloads.completed.collect { result ->
                        adapter.reloadAssets()
                        val error = result.error
                        if (error != null) {
                            alert(if (error is AssetImporter.Changed) getString(R.string.asset_update_conflict, result.file.name)
                                else getString(R.string.asset_update_failed, result.file.name, error.readableMessage)).tryToShow()
                        } else {
                            val outcome = requireNotNull(result.outcome)
                            val message = when {
                                outcome.upToDate -> getString(R.string.route_asset_no_update)
                                outcome.metadataError != null -> getString(R.string.asset_imported_metadata_failed,
                                    result.file.name, outcome.metadataError.readableMessage)
                                else -> getString(R.string.route_asset_updated)
                            }
                            snackbar(message).show()
                        }
                    }
                }
            }
        }

        binding.refreshLayout.setOnRefreshListener {
            adapter.reloadAssets()
            binding.refreshLayout.isRefreshing = false
        }
        binding.refreshLayout.setColorSchemeColors(getColorAttr(R.attr.primaryOrTextPrimary))

        undoManager = UndoSnackbarManager(this, adapter)

        ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            0, ItemTouchHelper.START
        ) {

            override fun getSwipeDirs(
                recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder
            ): Int {
                val index = viewHolder.bindingAdapterPosition
                if (index < 2) return 0
                return super.getSwipeDirs(recyclerView, viewHolder)
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val index = viewHolder.bindingAdapterPosition
                runCatching {
                    adapter.remove(index)
                    undoManager.remove(index to (viewHolder as AssetHolder).file)
                }.onFailure { error ->
                    adapter.reloadAssets()
                    snackbar(error.readableMessage).show()
                }
            }

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ) = false

        }).attachToRecyclerView(binding.recyclerView)
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(layout.coordinator, text, Snackbar.LENGTH_LONG)
    }

    val assetNames = arrayOf("geoip.db", "geosite.db")

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.import_asset_menu, menu)
        return true
    }

    val importFile = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) lifecycleScope.launch(Dispatchers.IO) {
            var displayName = uri.lastPathSegment.orEmpty().substringAfterLast('/')
            runCatching {
                displayName = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && column >= 0) cursor.getString(column) else null
                }?.takeIf { it.isNotBlank() } ?: displayName
                val baseName = displayName.substringAfterLast('/').substringAfterLast('\\')
                val lowerName = baseName.lowercase(Locale.ROOT)
                require(lowerName.endsWith(".db") || lowerName.endsWith(".srs") || lowerName.endsWith(".json")) {
                    getString(R.string.route_not_asset, displayName)
                }
                val safeName = if (lowerName in assetNames) lowerName else baseName
                val directory = getExternalFilesDir(null) ?: filesDir
                val target = File(directory, safeName)
                val expected = AssetImporter.snapshot(target)
                val replacing = expected.identity != null
                if (replacing && !confirmReplacement(safeName)) return@runCatching
                val metadataError = AssetImporter.installVersioned(target, versionFileFor(target), "Custom", { temporary ->
                    AssetImporter.copy({ requireNotNull(contentResolver.openInputStream(uri)) {
                        getString(R.string.asset_cannot_read)
                    } }, temporary)
                }, { temporary -> Libcore.validateAsset(temporary.absolutePath, safeName) }, expected)
                adapter.reloadAssets()
                onMainDispatcher {
                    val message = if (metadataError == null) getString(if (replacing) R.string.asset_replaced else R.string.asset_imported, safeName)
                        else getString(R.string.asset_imported_metadata_failed, safeName, metadataError.readableMessage)
                    snackbar(message).show()
                }
            }.onFailure { error ->
                if (error is CancellationException) throw error
                val detail = when (error) {
                    is io.nekohasekai.sagernet.utils.AssetImporter.Empty -> getString(R.string.asset_empty)
                    is io.nekohasekai.sagernet.utils.AssetImporter.TooLarge -> getString(R.string.asset_too_large)
                    else -> error.readableMessage
                }
                onMainDispatcher { alert(getString(R.string.asset_import_failed, displayName, detail)).tryToShow() }
            }
        }
    }

    private suspend fun confirmReplacement(name: String): Boolean = onMainDispatcher {
        if (isFinishing || isDestroyed) return@onMainDispatcher false
        suspendCancellableCoroutine { continuation ->
            val dialog = MaterialAlertDialogBuilder(this@AssetsActivity)
                .setTitle(R.string.asset_replace_title)
                .setMessage(getString(R.string.asset_replace_notice, name))
                .setPositiveButton(R.string.asset_replace) { _, _ -> if (continuation.isActive) continuation.resume(true) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(false) }
                .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }.show()
            continuation.invokeOnCancellation { runOnUiThread { dialog.dismiss() } }
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_import_file -> {
                startFilesForResult(importFile, "*/*")
                return true
            }
        }
        return false
    }

    inner class AssetAdapter : RecyclerView.Adapter<AssetHolder>(),
        UndoSnackbarManager.Interface<File> {

        val assets = ArrayList<File>()
        val pendingDeletes = AssetDeletionQueue()

        init {
            reloadAssets()
        }

        fun reloadAssets() {
            layout.recyclerView.post {
                val directory = getExternalFilesDir(null) ?: filesDir
                pendingDeletes.restoreMetadataCleanup(directory)
                val files = directory.listFiles().orEmpty()
                    .filter { it.isFile && it.extension.lowercase() in setOf("db", "srs", "json") && it.name !in assetNames }
                    .sortedBy { it.name.lowercase() }
                assets.clear()
                assets.addAll(assetNames.map { File(directory, it) })
                assets.addAll(pendingDeletes.visible(files))
                notifyDataSetChanged()
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AssetHolder {
            return AssetHolder(LayoutAssetItemBinding.inflate(layoutInflater, parent, false))
        }

        override fun onBindViewHolder(holder: AssetHolder, position: Int) {
            holder.bind(assets[position])
        }

        override fun getItemCount(): Int {
            return assets.size
        }

        fun remove(index: Int) {
            pendingDeletes.stage(assets[index])
            assets.removeAt(index)
            notifyItemRemoved(index)
        }

        override fun undo(actions: List<Pair<Int, File>>) {
            pendingDeletes.undo(actions.map { it.second })
            reloadAssets()
        }

        override fun commit(actions: List<Pair<Int, File>>) {
            val files = actions.map { it.second }
            runOnDefaultDispatcher {
                val failures = pendingDeletes.commit(files, ::versionFileFor)
                onMainDispatcher {
                    reloadAssets()
                    if (failures.isNotEmpty()) alert(getString(R.string.asset_delete_failed,
                        failures.joinToString("\n") { (file, error) ->
                            if (error is AssetImporter.MetadataCleanupFailed) getString(R.string.asset_deleted_metadata_failed, file.name, error.cause?.readableMessage.orEmpty())
                            else "${file.name}: ${error.readableMessage}"
                        })).tryToShow()
                }
            }
        }

    }

    internal val downloads: AssetDownloadViewModel by viewModels()

    internal var readAssetVersion: (File, File) -> String? = { file, version ->
        Libcore.waitForAssets()
        if (file.isFile) AssetImporter.readVersion(file, version)
            ?: (getString(R.string.asset_version_unknown) + " · " + DateFormat.getDateFormat(app).format(Date(file.lastModified())))
        else null
    }

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File
        private var bindingGeneration = 0L
        internal var statusJob: kotlinx.coroutines.Job? = null

        fun bind(file: File) {
            this.file = file
            val generation = ++bindingGeneration

            binding.assetName.text = file.name
            val versionFile = versionFileFor(file)

            var localVersion = getString(if (file.isFile) R.string.asset_version_unknown else R.string.asset_not_downloaded)
            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)
            statusJob = runOnDefaultDispatcher {
                val result = runCatching {
                    readAssetVersion(file, versionFile)
                        ?: getString(if (adapter.pendingDeletes.needsMetadataCleanup(file))
                        R.string.asset_deleted_cleanup_pending else R.string.asset_not_downloaded)
                }
                onMainDispatcher {
                    if (bindingGeneration != generation || this@AssetHolder.file != file) return@onMainDispatcher
                    result.onSuccess {
                        localVersion = it
                        binding.assetStatus.text = getString(R.string.route_asset_status, it)
                    }.onFailure { snackbar(it.readableMessage).show() }
                }
            }
            binding.assetDetails.text = getString(R.string.asset_details, file.extension.uppercase(), file.absolutePath,
                getString(if (file.name in assetNames) R.string.asset_database_help
                    else if (file.extension.equals("db", true)) R.string.asset_custom_database_help else R.string.asset_ruleset_help))
            val cleanupPending = adapter.pendingDeletes.needsMetadataCleanup(file)
            binding.assetDelete.setText(if (cleanupPending) R.string.asset_retry_cleanup else R.string.delete)
            binding.assetDelete.contentDescription = getString(if (cleanupPending)
                R.string.asset_retry_cleanup_named else R.string.asset_delete_named, file.name)
            binding.assetDelete.isInvisible = file.name in assetNames
            binding.assetDelete.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION && file.name !in assetNames) {
                    runCatching {
                        adapter.remove(index)
                        undoManager.remove(index to file)
                    }.onFailure { error ->
                        adapter.reloadAssets()
                        snackbar(error.readableMessage).show()
                    }
                }
            }

            val running = downloads.isRunning(file)
            binding.subscriptionUpdateProgress.isInvisible = !running
            binding.rulesUpdate.isInvisible = file.name !in assetNames || running
            binding.rulesUpdate.contentDescription = getString(R.string.asset_update_named, file.name)
            binding.rulesUpdate.setOnClickListener {
                downloads.start(file, versionFile, localVersion)
                bind(file)
            }

        }

    }

    private fun versionFileFor(file: File) = File(file.parentFile,
        if (file.name in assetNames) "${file.nameWithoutExtension}.version.txt" else "${file.name}.version.txt")

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onResume() {
        super.onResume()

        if (::adapter.isInitialized) {
            adapter.reloadAssets()
        }
    }

}
