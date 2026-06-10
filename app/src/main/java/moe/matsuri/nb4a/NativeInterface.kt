package moe.matsuri.nb4a

import android.content.Context
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.PackageCache
import libcore.BoxPlatformInterface
import libcore.Libcore
import libcore.NB4AInterface
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.NetworkInterface as JavaNetworkInterface

class NativeInterface : BoxPlatformInterface, NB4AInterface {

    //  libbox interface

    override fun autoDetectInterfaceControl(fd: Int) {
        val vpnService = DataStore.vpnService ?: return
        try {
            if (!vpnService.protect(fd)) {
                error("protect fd failed")
            }
        } catch (e: Throwable) {
            Logs.w("protect fd failed: $fd", e)
            throw e
        }
    }

    override fun openTun(singTunOptionsJson: String, tunPlatformOptionsJson: String): Long {
        if (DataStore.vpnService == null) {
            throw Exception("no VpnService")
        }
        return DataStore.vpnService!!.startVpn(singTunOptionsJson, tunPlatformOptionsJson).toLong()
    }

    override fun useProcFS(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
    }

    override fun networkInterfaces(): String {
        val interfaces = JSONArray()
        val seenNames = HashSet<String>()
        var defaultAdded = false

        for (network in orderedNetworks()) {
            val linkProperties = SagerNet.connectivity.getLinkProperties(network) ?: continue
            val name = linkProperties.interfaceName ?: continue
            if (name.isBlank() || !seenNames.add(name)) continue

            val capabilities = SagerNet.connectivity.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) continue
            val isDefault = !defaultAdded

            val addresses = JSONArray()
            for (linkAddress in linkProperties.linkAddresses) {
                val hostAddress = stripAddressZone(linkAddress.address.hostAddress ?: continue)
                if (hostAddress.isNotBlank()) {
                    addresses.put("$hostAddress/${linkAddress.prefixLength}")
                }
            }
            if (addresses.length() == 0) continue

            val dnsServers = JSONArray()
            for (dnsServer in linkProperties.dnsServers) {
                val hostAddress = stripAddressZone(dnsServer.hostAddress ?: continue)
                if (hostAddress.isNotBlank()) dnsServers.put(hostAddress)
            }
            if (isDefault) defaultAdded = true

            interfaces.put(JSONObject().apply {
                put("index", interfaceIndex(name))
                put("mtu", linkProperties.mtu.takeIf { it > 0 } ?: 1500)
                put("name", name)
                put("addresses", addresses)
                put("flags", NET_FLAG_UP or NET_FLAG_RUNNING or NET_FLAG_MULTICAST)
                put("type", networkType(capabilities))
                put("dns_server", dnsServers)
                put("metered", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true)
                put("default", isDefault)
            })
        }

        return interfaces.toString()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    override fun findConnectionOwner(
        ipProto: Int, srcIp: String, srcPort: Int, destIp: String, destPort: Int
    ): Int {
        return SagerNet.connectivity.getConnectionOwnerUid(
            ipProto, InetSocketAddress(srcIp, srcPort), InetSocketAddress(destIp, destPort)
        )
    }

    override fun packageNameByUid(uid: Int): String {
        PackageCache.awaitLoadSync()

        if (uid <= 1000L) {
            return "android"
        }

        val packageNames = PackageCache.uidMap[uid]
        if (!packageNames.isNullOrEmpty()) for (packageName in packageNames) {
            return packageName
        }

        error("unknown uid $uid")
    }

    override fun uidByPackageName(packageName: String): Int {
        PackageCache.awaitLoadSync()
        return PackageCache[packageName] ?: 0
    }

    // TODO: 'getter for connectionInfo: WifiInfo!' is deprecated
    override fun wifiState(): String {
        val wifiManager =
            app.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo
        return "${connectionInfo.ssid},${connectionInfo.bssid}"
    }

    private fun stripAddressZone(address: String): String {
        val zoneIndex = address.indexOf('%')
        return if (zoneIndex >= 0) address.substring(0, zoneIndex) else address
    }

    private fun orderedNetworks(): List<Network> {
        val networks = ArrayList<Network>()

        fun add(network: Network?) {
            if (network != null && !networks.contains(network)) networks.add(network)
        }

        add(SagerNet.underlyingNetwork)
        add(SagerNet.connectivity.activeNetwork)
        SagerNet.connectivity.allNetworks.forEach(::add)
        return networks
    }

    private fun interfaceIndex(name: String): Int {
        return runCatching { JavaNetworkInterface.getByName(name)?.index }
            .getOrNull()
            ?.takeIf { it > 0 }
            ?: stableInterfaceIndex(name)
    }

    private fun stableInterfaceIndex(name: String): Int {
        val index = name.hashCode() and Int.MAX_VALUE
        return if (index > 0) index else 1
    }

    private fun networkType(capabilities: NetworkCapabilities?): Int {
        return when {
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> INTERFACE_TYPE_WIFI
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> INTERFACE_TYPE_CELLULAR
            capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true -> INTERFACE_TYPE_ETHERNET
            else -> INTERFACE_TYPE_OTHER
        }
    }

    private companion object {
        const val NET_FLAG_UP = 1
        const val NET_FLAG_MULTICAST = 1 shl 4
        const val NET_FLAG_RUNNING = 1 shl 5

        const val INTERFACE_TYPE_WIFI = 0
        const val INTERFACE_TYPE_CELLULAR = 1
        const val INTERFACE_TYPE_ETHERNET = 2
        const val INTERFACE_TYPE_OTHER = 3
    }

    // nb4a interface

    override fun useOfficialAssets(): Boolean {
        return DataStore.rulesProvider == 0
    }

    override fun selector_OnProxySelected(selectorTag: String, tag: String) {
        if (selectorTag != "proxy") {
            Logs.d("other selector: $selectorTag")
            return
        }
        Libcore.resetAllConnections(true)
        DataStore.baseService?.apply {
            runOnDefaultDispatcher {
                val id = data.proxy!!.config.profileTagMap
                    .filterValues { it == tag }.keys.firstOrNull() ?: -1
                val ent = SagerDatabase.proxyDao.getById(id) ?: return@runOnDefaultDispatcher
                // traffic & title
                data.proxy?.apply {
                    looper?.selectMain(id)
                    displayProfileName = ServiceNotification.genTitle(ent)
                    data.notification?.postNotificationTitle(displayProfileName)
                }
                // post binder
                data.binder.broadcast { b ->
                    b.cbSelectorUpdate(id)
                }
            }
        }
    }

}
