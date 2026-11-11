package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.*
import kotlinx.coroutines.ensureActive
import libcore.Libcore
import moe.matsuri.nb4a.utils.Util
import org.json.JSONObject
import java.io.File

internal object AssetDownloader {
    data class Outcome(val upToDate: Boolean = false, val metadataError: Throwable? = null)
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

    suspend fun update(file: File, versionFile: File, localVersion: String): Outcome {
        Libcore.waitForAssets()
        val expected = AssetImporter.snapshot(file)
        AssetImporter.retryVersionMetadata(file, versionFile)
        val storedVersion = AssetImporter.readVersion(file, versionFile) ?: localVersion
        val fileName = file.name

        val ruleProvider = rulesProviders[DataStore.rulesProvider]
        val repo = ruleProvider.repoByFileName[fileName]

        val client = Libcore.newAssetHttpClient().apply {
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

            if (tagName == storedVersion && file.isFile && runCatching {
                Libcore.validateAsset(file.absolutePath, fileName)
            }.isSuccess) {
                return Outcome(upToDate = true)
            }

            val releaseAssets = release.getJSONArray("assets").filterIsInstance<JSONObject>()
            val assetToDownload = releaseAssets.find { it.getStr("name") == fileName }
                ?: error("File $fileName not found in release ${release["url"]}")
            val browserDownloadUrl = assetToDownload.getStr("browser_download_url")

            response = client.newRequest().apply {
                setURL(browserDownloadUrl)
            }.execute()

            try {
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                val metadataError = AssetImporter.installVersioned(file, versionFile, tagName,
                    { temporary -> response.writeTo(temporary.canonicalPath) },
                    { temporary -> Libcore.validateAsset(temporary.absolutePath, fileName) }, expected)
                return Outcome(metadataError = metadataError)
            } finally {
                response.close()
            }
        } finally {
            client.close()
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
