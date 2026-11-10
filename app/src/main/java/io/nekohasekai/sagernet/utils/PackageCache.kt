package io.nekohasekai.sagernet.utils

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.listenForPackageChanges
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import moe.matsuri.nb4a.plugin.Plugins
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

internal class PackageSnapshotStore(private val load: () -> PackageSnapshot) {
    @Volatile private var current: PackageSnapshot? = null
    fun get(): PackageSnapshot = current ?: synchronized(this) { current ?: refresh() }
    @Synchronized fun refresh(): PackageSnapshot = load().also { current = it }
}

class PackageSnapshot(packages: List<PackageInfo>, applications: List<ApplicationInfo>) {
    private fun <K, V> freeze(values: Map<K, V>): Map<K, V> = Collections.unmodifiableMap(values)
    val installedPackages = freeze(packages.filter {
        it.packageName == "android" || it.requestedPermissions?.contains(Manifest.permission.INTERNET) == true
    }.associateBy { it.packageName })
    val installedPluginPackages = freeze(packages.filter(Plugins::isExe).associateBy { it.packageName })
    val installedApps = freeze(applications.associateBy { it.packageName })
    val packageMap = freeze(applications.associate { it.packageName to it.uid })
    val uidMap: Map<Int, Set<String>> = freeze(applications.groupBy { it.uid }.mapValues { (_, apps) ->
        Collections.unmodifiableSet(apps.map { it.packageName }.toSet())
    })
    internal val labels = ConcurrentHashMap<String, String>()
}

object PackageCache {
    private val store = PackageSnapshotStore(::loadSnapshot)
    private var registered = false
    val snapshot get(): PackageSnapshot {
        register()
        return store.get()
    }
    val installedPackages get() = snapshot.installedPackages
    val installedPluginPackages get() = snapshot.installedPluginPackages
    val installedApps get() = snapshot.installedApps
    val packageMap get() = snapshot.packageMap
    val uidMap get() = snapshot.uidMap

    @Synchronized fun register() {
        if (registered) return
        // Register before loading, so an initial PackageManager failure cannot lose future updates.
        app.listenForPackageChanges(false) {
            runOnDefaultDispatcher { runCatching { reload() }.onFailure(Logs::w) }
        }
        registered = true
    }

    @SuppressLint("InlinedApi")
    private fun loadSnapshot(): PackageSnapshot {
        val packages = app.packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.GET_PERMISSIONS or
                PackageManager.GET_PROVIDERS or PackageManager.GET_META_DATA)
        val applications = app.packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
        return PackageSnapshot(packages, applications)
    }

    fun reload() { store.refresh() }
    operator fun get(uid: Int) = snapshot.uidMap[uid]
    operator fun get(packageName: String) = snapshot.packageMap[packageName]
    fun awaitLoadSync() { snapshot }
    fun loadLabel(packageName: String): String {
        val current = snapshot
        return current.labels.getOrPut(packageName) {
            current.installedApps[packageName]?.loadLabel(app.packageManager)?.toString() ?: packageName
        }
    }
}
