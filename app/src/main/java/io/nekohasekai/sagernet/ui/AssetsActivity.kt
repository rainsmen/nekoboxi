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
import java.util.concurrent.atomic.AtomicInteger

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
        if (uri != null) runOnDefaultDispatcher {
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
                val metadataError = AssetImporter.installVersioned(target, versionFileFor(target), "Custom", { temporary ->
                    AssetImporter.copy({ requireNotNull(contentResolver.openInputStream(uri)) {
                        getString(R.string.asset_cannot_read)
                    } }, temporary)
                }, { temporary -> Libcore.validateAsset(temporary.absolutePath, safeName) })
                adapter.reloadAssets()
                onMainDispatcher {
                    val message = if (metadataError == null) getString(R.string.asset_imported, safeName)
                        else getString(R.string.asset_imported_metadata_failed, safeName, metadataError.readableMessage)
                    snackbar(message).show()
                }
            }.onFailure { error ->
                val detail = when (error) {
                    is io.nekohasekai.sagernet.utils.AssetImporter.Empty -> getString(R.string.asset_empty)
                    is io.nekohasekai.sagernet.utils.AssetImporter.TooLarge -> getString(R.string.asset_too_large)
                    else -> error.readableMessage
                }
                onMainDispatcher { alert(getString(R.string.asset_import_failed, displayName, detail)).tryToShow() }
            }
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
                        failures.joinToString("\n") { (file, error) -> "${file.name}: ${error.readableMessage}" })).tryToShow()
                }
            }
        }

    }

    val updating = AtomicInteger()

    inner class AssetHolder(val binding: LayoutAssetItemBinding) :
        RecyclerView.ViewHolder(binding.root) {
        lateinit var file: File

        fun bind(file: File) {
            this.file = file

            binding.assetName.text = file.name
            val versionFile = versionFileFor(file)

            val localVersion = if (file.isFile) {
                if (versionFile.isFile) {
                    try {
                        versionFile.readText().trim()
                    } catch (e: Throwable) {
                        snackbar(e.readableMessage).show()
                        getString(R.string.asset_version_unknown)
                    }
                } else {
                    getString(R.string.asset_version_unknown) + " · " + DateFormat.getDateFormat(app).format(Date(file.lastModified()))
                }
            } else {
                getString(R.string.asset_not_downloaded)
            }

            binding.assetStatus.text = getString(R.string.route_asset_status, localVersion)
            binding.assetDetails.text = getString(R.string.asset_details, file.extension.uppercase(), file.absolutePath,
                getString(if (file.name in assetNames) R.string.asset_database_help
                    else if (file.extension.equals("db", true)) R.string.asset_custom_database_help else R.string.asset_ruleset_help))
            binding.assetDelete.isInvisible = file.name in assetNames
            binding.assetDelete.setOnClickListener {
                val index = bindingAdapterPosition
                if (index != RecyclerView.NO_POSITION && file.name !in assetNames) {
                    adapter.remove(index)
                    undoManager.remove(index to file)
                }
            }

            binding.rulesUpdate.isInvisible = file.name !in assetNames
            binding.rulesUpdate.setOnClickListener {
                updating.incrementAndGet()
                layout.refreshLayout.isEnabled = false
                binding.subscriptionUpdateProgress.isInvisible = false
                binding.rulesUpdate.isInvisible = true
                runOnDefaultDispatcher {
                    runCatching {
                        updateAsset(file, versionFile, localVersion)
                    }.onFailure {
                        onMainDispatcher {
                            alert(getString(R.string.asset_update_failed, file.name, it.readableMessage)).tryToShow()
                        }
                    }

                    onMainDispatcher {
                        if (this@AssetHolder.file == file) {
                            binding.rulesUpdate.isInvisible = false
                            binding.subscriptionUpdateProgress.isInvisible = true
                        }
                        if (updating.decrementAndGet() == 0) {
                            layout.refreshLayout.isEnabled = true
                        }
                    }
                }
            }

        }

    }

    private fun versionFileFor(file: File) = File(file.parentFile,
        if (file.name in assetNames) "${file.nameWithoutExtension}.version.txt" else "${file.name}.version.txt")

    private val rulesProviders = listOf(
        RuleAssetsProvider(
            "SagerNet/sing-geoip",
            "SagerNet/sing-geosite",
        ),
        RuleAssetsProvider(
            "soffchen/sing-geoip",
            "soffchen/sing-geosite",
        ),
        RuleAssetsProvider(
            "Chocolate4U/Iran-sing-box-rules"
        ),
        RuleAssetsProvider(
            "L11R/antizapret-sing-box-geo"
        ),
    )

    suspend fun updateAsset(file: File, versionFile: File, localVersion: String) {
        val fileName = file.name

        val ruleProvider = rulesProviders[DataStore.rulesProvider]
        val repo = ruleProvider.repoByFileName[fileName]

        val client = Libcore.newHttpClient().apply {
            modernTLS()
            keepAlive()
            trySocks5(DataStore.mixedPort)
        }

        try {
            var response = client.newRequest().apply {
                setURL("https://api.github.com/repos/$repo/releases/latest")
            }.execute()

            val release = JSONObject(Util.getStringBox(response.contentString))
            val tagName = release.getString("tag_name").also { require(it.isNotBlank()) }

            if (tagName == localVersion && file.isFile && runCatching {
                Libcore.validateAsset(file.absolutePath, fileName)
            }.isSuccess) {
                onMainDispatcher {
                    snackbar(R.string.route_asset_no_update).show()
                }
                return
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            val metadataError = AssetImporter.installVersioned(file, versionFile, tagName,
                { temporary -> response.writeTo(temporary.canonicalPath) },
                { temporary -> Libcore.validateAsset(temporary.absolutePath, fileName) })
            adapter.reloadAssets()
            onMainDispatcher {
                if (metadataError == null) snackbar(R.string.route_asset_updated).show()
                else snackbar(getString(R.string.asset_imported_metadata_failed, fileName, metadataError.readableMessage)).show()
            }
        } finally {
            client.close()
        }
    }

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

    private data class RuleAssetsProvider(
        val repoByFileName: Map<String, String>
    ) {
        constructor(
            geoipRepo: String,
            geositeRepo: String = geoipRepo,
        ) : this(
            mapOf(
                "geoip.db" to geoipRepo,
                "geosite.db" to geositeRepo,
            )
        )
    }
}