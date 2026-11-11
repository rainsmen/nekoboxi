package io.nekohasekai.sagernet.group

import android.annotation.SuppressLint
import io.nekohasekai.sagernet.database.backup.BackupRestore
import io.nekohasekai.sagernet.database.preference.PublicDatabase
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.http.HttpBean
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.parseHysteria1Json
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.parseShadowsocks
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.trojan.TrojanBean
import io.nekohasekai.sagernet.fmt.trojan_go.parseTrojanGo
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.VMessBean
import io.nekohasekai.sagernet.fmt.v2ray.isTLS
import io.nekohasekai.sagernet.fmt.v2ray.setTLS
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.ktx.*
import libcore.Libcore
import moe.matsuri.nb4a.Protocols
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.proxy.config.OutboundDependencies
import io.nekohasekai.sagernet.database.DataValidationException
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.utils.Util
import org.ini4j.Ini
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.yaml.snakeyaml.TypeDescription
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import java.io.StringReader
import androidx.core.net.toUri
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

@Suppress("EXPERIMENTAL_API_USAGE")
object RawUpdater : GroupUpdater() {

    @SuppressLint("Recycle")
    override suspend fun doUpdate(
        proxyGroup: ProxyGroup,
        subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?,
        byUser: Boolean
    ) {

        update(proxyGroup, subscription, userInterface, byUser)
    }

    internal class DownloadFailure(cause: Exception) : java.io.IOException(cause.message, cause) {
        // libcore reports status failures as "HTTP <code> <reason>". Auth/deleted
        // subscriptions require a user/provider correction, not background retries.
        val retryable: Boolean = Regex("^HTTP ([0-9]{3})\\b").find(cause.message.orEmpty())
            ?.groupValues?.get(1)?.toInt()?.let { it in setOf(408, 425, 429) || it >= 500 } ?: true
    }

    internal data class Download(val text: String, val userinfo: String? = null, val remoteName: String? = null)

    private fun download(subscription: SubscriptionBean): Download {
        if (subscription.link.startsWith("content://")) {
            val text = app.contentResolver.openInputStream(subscription.link.toUri())
                ?.bufferedReader()?.use { it.readText() }
                ?: error(app.getString(R.string.no_proxies_found_in_subscription))
            return Download(text)
        }
        val client = Libcore.newHttpClient()
        try {
            client.trySocks5(DataStore.mixedPort)
            client.tryH3Direct()
            if (DataStore.appTLSVersion == "1.3") client.restrictedTLS()
            val response = client.newRequest().apply {
                if (DataStore.allowInsecureOnRequest) allowInsecure()
                setURL(subscription.link)
                setUserAgent(subscription.customUserAgent.takeIf { it.isNotBlank() } ?: USER_AGENT)
            }.execute()
            return Download(Util.getStringBox(response.contentString),
                Util.getStringBox(response.getHeader("Subscription-Userinfo")),
                Util.getStringBox(response.getHeader("content-disposition")))
        } catch (error: Exception) {
            throw DownloadFailure(error)
        } finally { client.close() }
    }

    internal suspend fun update(proxyGroup: ProxyGroup, subscription: SubscriptionBean,
        userInterface: GroupManager.Interface?, byUser: Boolean,
        expectedGeneration: String = BackupRestore.generation(PublicDatabase.instance),
        download: (SubscriptionBean) -> Download = ::download) {
        val restoreGeneration = expectedGeneration
        currentCoroutineContext().ensureActive()
        val response = download(subscription)
        currentCoroutineContext().ensureActive()
        var proxies = parseRaw(response.text) ?: error(app.getString(R.string.no_proxies_found))
        response.userinfo?.let { subscription.subscriptionUserinfo = it }
        if (proxyGroup.name?.startsWith("Subscription #") == true && !response.remoteName.isNullOrBlank()) {
            Util.decodeFilename(response.remoteName).takeIf { it.isNotBlank() }?.let { proxyGroup.name = it }
        }

        require(proxies.isNotEmpty()) { app.getString(R.string.subscription_empty_kept) }
        if (subscription.forceResolve) forceResolve(proxies, proxyGroup.id)

        val duplicate = ArrayList<String>()
        if (subscription.deduplication) {
            Logs.d("Before deduplication: ${proxies.size}")
            val uniqueProxies = LinkedHashSet<Protocols.Deduplication>()
            val uniqueNames = HashMap<Protocols.Deduplication, String>()
            for (_proxy in proxies) {
                val proxy = Protocols.Deduplication(_proxy, _proxy.javaClass.toString())
                if (!uniqueProxies.add(proxy)) {
                    val index = uniqueProxies.indexOf(proxy)
                    if (uniqueNames.containsKey(proxy)) {
                        val name = uniqueNames[proxy]!!.replace(" ($index)", "")
                        if (name.isNotBlank()) {
                            duplicate.add("$name ($index)")
                            uniqueNames[proxy] = ""
                        }
                    }
                    duplicate.add(_proxy.displayName() + " ($index)")
                } else {
                    uniqueNames[proxy] = _proxy.displayName()
                }
            }
            uniqueProxies.retainAll(uniqueNames.keys)
            proxies = uniqueProxies.toList().map { it.bean }
        }

        val context = currentCoroutineContext()
        context.ensureActive()
        val result = SubscriptionUpdate.commit(SagerDatabase.instance, proxyGroup, proxies, restoreGeneration,
            PublicDatabase.instance) { context.ensureActive() }
        val updatedGroup = SagerDatabase.groupDao.getById(proxyGroup.id) ?: return
        try {
            userInterface?.onUpdateSuccess(
                updatedGroup, result.changed, result.added, result.updated, result.deleted, duplicate, byUser)
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) { Logs.w(error) } // Data already committed; a UI failure is not an update failure.
    }

    suspend fun parseRaw(text: String, fileName: String = ""): List<AbstractBean>? =
        parseRawUnchecked(text, fileName)?.also { beans ->
            beans.forEachIndexed { index, bean ->
                if (bean is io.nekohasekai.sagernet.fmt.naive.NaiveBean)
                    io.nekohasekai.sagernet.fmt.naive.readNaiveHeaders(bean.extraHeaders, allowLegacyLines = true)
                if (bean is ConfigBean && bean.type == 1) {
                    try { Libcore.validateImportedConfig(OutboundDependencies.validationConfig(bean)) }
                    catch (error: kotlinx.coroutines.CancellationException) { throw error }
                    catch (error: Exception) {
                        val field = Regex("import-field:(server_port|server|type|local_file|size)")
                            .find(error.message.orEmpty())?.groupValues?.get(1)
                        if (field == "local_file") throw DataValidationException(
                            DataValidationException.Reason.IMPORTED_LOCAL_FILE, index + 1)
                        throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_FIELD,
                            "sing-box JSON", index + 1, field ?: "protocol options")
                    }
                }
            }
        }

    @Suppress("UNCHECKED_CAST")
    private suspend fun parseRawUnchecked(text: String, fileName: String): List<AbstractBean>? {

        val proxies = mutableListOf<AbstractBean>()
        val wireGuardDocument = Regex("""(?m)^[ \t]*\[Interface][ \t]*(?:[#;].*)?$""").containsMatchIn(text)
        if (text.trimStart().let { it.startsWith("{") || (it.startsWith("[") && !wireGuardDocument) }) {
            try {
                val tokens = JSONTokener(text)
                val json = tokens.nextValue()
                if (tokens.nextClean() != '\u0000')
                    throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "JSON trailing content")
                return parseJSON(json)
            } catch (error: DataValidationException) {
                throw error
            } catch (error: Exception) {
                throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "JSON")
            }
        }

        if (Regex("""(?m)^[ \t]*proxies[ \t]*:""").containsMatchIn(text)) {

            // clash & meta
            var entryIndex = 0
            try {

                val yaml = Yaml().apply {
                    addTypeDescription(TypeDescription(String::class.java, "str"))
                }.loadAs(text, Map::class.java)

                val globalClientFingerprint = yaml["global-client-fingerprint"]?.toString() ?: ""

                val entries = yaml["proxies"] as? List<Map<String, Any?>> ?: error(app.getString(R.string.no_proxies_found_in_file))
                for ((index, proxy) in entries.withIndex()) {
                    entryIndex = index + 1
                    fun invalidField(field: String): Nothing = throw DataValidationException(
                        DataValidationException.Reason.INVALID_SUBSCRIPTION_FIELD, "Clash", entryIndex, field)
                    // Missing imported fields must not acquire manual-editor defaults.
                    val server = proxy["server"] as? String
                    val type = proxy["type"] as? String
                    if (type.isNullOrBlank()) invalidField("type")
                    val realmOptions = proxy["realm-opts"] ?: proxy["realm_opts"] ?: proxy["realm"]
                    val realm = realmOptions as? Map<*, *>
                    if (type == "hysteria2" && realmOptions != null && realm == null) invalidField("realm-opts")
                    val realmEnabled = type == "hysteria2" && realm != null &&
                        (if (proxy.containsKey("realm")) realm["enable"]?.toString() != "false"
                         else realm["enable"]?.toString() == "true")
                    if (server.isNullOrBlank() && !realmEnabled) invalidField("server")
                    if (realmEnabled) {
                        if ((realm!!["server-url"] ?: realm["server_url"])?.toString().isNullOrBlank()) invalidField("realm-opts.server-url")
                        if ((realm["realm-id"] ?: realm["realm_id"])?.toString().isNullOrBlank()) invalidField("realm-opts.realm-id")
                    }
                    if (realmEnabled && !proxy.containsKey("port") && !proxy.containsKey("ports")) {
                        // Rendezvous supplies the target address and port.
                    } else if (type == "hysteria" || type == "hysteria2") {
                        val ports = proxy["ports"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: proxy["port"]?.toString().orEmpty()
                        try { io.nekohasekai.sagernet.fmt.hysteria.hopPortsToSingboxList(ports) }
                        catch (_: IllegalArgumentException) { invalidField("port/ports") }
                    } else {
                        val port = proxy["port"]?.toString()?.toIntOrNull()
                        if (port == null || port !in 1..65535)
                            invalidField("port")
                    }

                    when (proxy["type"] as String) {
                        "socks5" -> {
                            proxies.add(SOCKSBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                name = proxy["name"]?.toString()
                            })
                        }

                        "http" -> {
                            proxies.add(HttpBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                username = proxy["username"]?.toString()
                                password = proxy["password"]?.toString()
                                setTLS(proxy["tls"]?.toString() == "true")
                                sni = proxy["sni"]?.toString()
                                name = proxy["name"]?.toString()
                                allowInsecure = proxy["skip-cert-verify"]?.toString() == "true"
                            })
                        }

                        "ss" -> {
                            val ssPlugin = mutableListOf<String>()
                            if (proxy.contains("plugin")) {
                                val opts = proxy["plugin-opts"] as Map<String, Any?>
                                when (proxy["plugin"]) {
                                    "obfs" -> {
                                        ssPlugin.apply {
                                            add("obfs-local")
                                            add("obfs=" + (opts["mode"]?.toString() ?: ""))
                                            add("obfs-host=" + (opts["host"]?.toString() ?: ""))
                                        }
                                    }

                                    "v2ray-plugin" -> {
                                        ssPlugin.apply {
                                            add("v2ray-plugin")
                                            add("mode=" + (opts["mode"]?.toString() ?: ""))
                                            if (opts["tls"]?.toString() == "true") add("tls")
                                            add("host=" + (opts["host"]?.toString() ?: ""))
                                            add("path=" + (opts["path"]?.toString() ?: ""))
                                            if (opts["mux"]?.toString() == "true") add("mux=8")
                                        }
                                    }
                                }
                            }
                            proxies.add(ShadowsocksBean().apply {
                                serverAddress = proxy["server"] as String
                                serverPort = proxy["port"].toString().toInt()
                                password = proxy["password"]?.toString()
                                method = clashCipher(proxy["cipher"] as String)
                                plugin = ssPlugin.joinToString(";")
                                name = proxy["name"]?.toString()
                            })
                        }

                        "vmess", "vless", "trojan" -> {
                            val bean = when (proxy["type"] as String) {
                                "vmess" -> VMessBean()
                                "vless" -> VMessBean().apply {
                                    alterId = -1 // make it VLESS
                                    packetEncoding = 2 // clash meta default XUDP
                                }

                                "trojan" -> TrojanBean().apply {
                                    security = "tls"
                                }

                                else -> error("impossible")
                            }

                            bean.serverAddress = proxy["server"]?.toString()?.takeIf { it.isNotBlank() }
                                ?: throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "${index + 1} (Clash)")
                            bean.serverPort = proxy["port"]?.toString()?.toIntOrNull()?.takeIf { it in 1..65535 }
                                ?: throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "${index + 1} (Clash)")

                            for (opt in proxy) {
                                when (opt.key) {
                                    "name" -> bean.name = opt.value?.toString()
                                    "password" -> if (bean is TrojanBean) bean.password =
                                        opt.value?.toString()

                                    "uuid" -> if (bean is VMessBean) bean.uuid =
                                        opt.value?.toString()

                                    "alterId" -> if (bean is VMessBean && !bean.isVLESS) bean.alterId =
                                        opt.value?.toString()?.toIntOrNull()

                                    "cipher" -> if (bean is VMessBean && !bean.isVLESS) bean.encryption =
                                        (opt.value as? String)

                                    "flow" -> if (bean is VMessBean && bean.isVLESS) {
                                        (opt.value as? String)?.let {
                                            if (it.contains("xtls-rprx-vision")) {
                                                bean.encryption = "xtls-rprx-vision"
                                            }
                                        }
                                    }

                                    "packet-encoding" -> if (bean is VMessBean) {
                                        bean.packetEncoding = when ((opt.value as? String)) {
                                            "packetaddr" -> 1
                                            "xudp" -> 2
                                            else -> 0
                                        }
                                    }

                                    "tls" -> if (bean is VMessBean) {
                                        bean.security =
                                            if (opt.value as? Boolean == true) "tls" else ""
                                    }

                                    "servername", "sni" -> bean.sni = opt.value?.toString()

                                    "alpn" -> bean.alpn =
                                        (opt.value as? List<Any>)?.joinToString("\n")

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value as? Boolean == true

                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "reality-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (realityOpt in it) {
                                            bean.security = "tls"

                                            when (realityOpt.key) {
                                                "public-key" -> bean.realityPubKey =
                                                    realityOpt.value?.toString()

                                                "short-id" -> bean.realityShortId =
                                                    realityOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "network" -> {
                                        when (opt.value) {
                                            "h2", "http" -> bean.type = "http"
                                            "ws", "grpc" -> bean.type = opt.value as String
                                        }
                                    }

                                    "ws-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (wsOpt in it) {
                                            when (wsOpt.key) {
                                                "headers" -> (wsOpt.value as? Map<Any, Any?>)?.forEach { (key, value) ->
                                                    when (key.toString().lowercase()) {
                                                        "host" -> {
                                                            bean.host = value?.toString()
                                                        }
                                                    }
                                                }

                                                "path" -> {
                                                    bean.path = wsOpt.value?.toString()
                                                }

                                                "max-early-data" -> {
                                                    bean.wsMaxEarlyData =
                                                        wsOpt.value?.toString()?.toIntOrNull()
                                                }

                                                "early-data-header-name" -> {
                                                    bean.earlyDataHeaderName =
                                                        wsOpt.value?.toString()
                                                }

                                                "v2ray-http-upgrade" -> {
                                                    if (wsOpt.value as? Boolean == true) {
                                                        bean.type = "httpupgrade"
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "h2-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (h2Opt in it) {
                                            when (h2Opt.key) {
                                                "host" -> bean.host =
                                                    (h2Opt.value as? List<Any>)?.joinToString("\n")

                                                "path" -> bean.path = h2Opt.value?.toString()
                                            }
                                        }
                                    }

                                    "http-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (httpOpt in it) {
                                            when (httpOpt.key) {
                                                "path" -> bean.path =
                                                    (httpOpt.value as? List<Any>)?.joinToString("\n")

                                                "headers" -> {
                                                    (httpOpt.value as? Map<Any, List<Any>>)?.forEach { (key, value) ->
                                                        when (key.toString().lowercase()) {
                                                            "host" -> {
                                                                bean.host = value.joinToString("\n")
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    "grpc-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (grpcOpt in it) {
                                            when (grpcOpt.key) {
                                                "grpc-service-name" -> bean.path =
                                                    grpcOpt.value?.toString()
                                            }
                                        }
                                    }

                                    "smux" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (smuxOpt in it) {
                                            when (smuxOpt.key) {
                                                "enabled" -> bean.enableMux =
                                                    smuxOpt.value.toString() == "true"

                                                "max-streams" -> bean.muxConcurrency =
                                                    smuxOpt.value.toString().toInt()

                                                "padding" -> bean.muxPadding =
                                                    smuxOpt.value.toString() == "true"
                                            }
                                        }
                                    }

                                    "ech-opts" -> (opt.value as? Map<String, Any?>)?.also {
                                        for (echOpt in it) {
                                            when (echOpt.key) {
                                                "enable" -> bean.enableECH =
                                                    echOpt.value.toString() == "true"
                                            }
                                        }
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "anytls" -> {
                            val bean = AnyTLSBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPort = opt.value.toString().toInt()
                                    "password" -> bean.password = opt.value.toString()
                                    "client-fingerprint" -> bean.utlsFingerprint =
                                        opt.value as String

                                    "sni" -> bean.sni = opt.value.toString()
                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }
                                }
                            }
                            proxies.add(bean)
                        }

                        "snell" -> {
                            // mihomo: version 1-5; sing-box supports 4 and 6.
                            val bean = SnellBean()
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPort = opt.value.toString().toInt()
                                    "userkey", "user-key" -> bean.userKey = opt.value.toString()
                                    "mode" -> bean.mode = opt.value.toString()
                                    "psk" -> bean.psk = opt.value.toString()
                                    "version" -> bean.version = opt.value.toString().toIntOrNull()
                                    "reuse" -> bean.reuse = opt.value.toString() == "true"
                                    "obfs-opts" -> (opt.value as? Map<*, *>)?.let {
                                        bean.obfsMode = it["mode"]?.toString() ?: ""
                                        bean.obfsHost = it["host"]?.toString() ?: ""
                                    }
                                }
                            }
                            if (bean.version != 4 && bean.version != 6)
                                throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "${index + 1} (Snell)")
                            proxies.add(bean)
                        }

                        "hysteria" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 1
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs" -> bean.obfuscation = opt.value.toString()

                                    "auth-str" -> {
                                        bean.authPayloadType = HysteriaBean.TYPE_STRING
                                        bean.authPayload = opt.value.toString()
                                    }

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull()
                                            ?: 100

                                    "recv-window-conn" -> bean.connectionReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "recv-window" -> bean.streamReceiveWindow =
                                        opt.value.toString().toIntOrNull() ?: 0

                                    "disable-mtu-discovery" -> bean.disableMtuDiscovery =
                                        opt.value.toString() == "true" || opt.value.toString() == "1"

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n") ?: "h3"
                                    }
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "hysteria2" -> {
                            val bean = HysteriaBean()
                            bean.protocolVersion = 2
                            var hopPorts = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value as String
                                    "port" -> bean.serverPorts = opt.value.toString()
                                    "ports" -> hopPorts = opt.value.toString()

                                    "obfs-password" -> bean.obfuscation = opt.value.toString()
                                    "obfs" -> bean.obfsType =
                                        if (opt.value.toString() == "gecko") "gecko" else ""

                                    "password" -> bean.authPayload = opt.value.toString()
                                    "bbr-profile" -> bean.bbrProfile = opt.value.toString()
                                    "hop-interval" -> bean.hopInterval = opt.value.toString().removeSuffix("s").toInt()
                                    "hop-interval-max" -> bean.hopIntervalMax = opt.value.toString().removeSuffix("s").toInt()
                                    "disable-chrome-parrot" -> bean.disableChromeParrot = opt.value.toString().toBoolean()
                                    "realm", "realm-opts" -> {
                                        if (!realmEnabled) continue
                                        val realm = opt.value as? Map<*, *> ?: error("Invalid realm object")
                                        fun field(key: String) = realm[key] ?: realm[key.replace('-', '_')]
                                        bean.realmServerUrl = field("server-url")?.toString().orEmpty()
                                        bean.realmId = field("realm-id")?.toString().orEmpty()
                                        bean.realmToken = field("token")?.toString().orEmpty()
                                        bean.realmStunServers = when (val stun = field("stun-servers")) {
                                            is List<*> -> stun.joinToString("\n")
                                            else -> stun?.toString().orEmpty()
                                        }
                                    }

                                    "sni" -> bean.sni = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "up" -> bean.uploadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0

                                    "down" -> bean.downloadMbps =
                                        opt.value.toString().substringBefore(" ").toIntOrNull() ?: 0
                                }
                            }
                            if (hopPorts.isNotBlank()) {
                                bean.serverPorts = hopPorts
                            }
                            proxies.add(bean)
                        }

                        "tuic" -> {
                            val bean = TuicBean()
                            var ip = ""
                            for (opt in proxy) {
                                if (opt.value == null) continue
                                when (opt.key.replace("_", "-")) {
                                    "name" -> bean.name = opt.value.toString()
                                    "server" -> bean.serverAddress = opt.value.toString()
                                    "ip" -> ip = opt.value.toString()
                                    "port" -> bean.serverPort = opt.value.toString().toInt()

                                    "token" -> {
                                        bean.protocolVersion = 4
                                        bean.token = opt.value.toString()
                                    }

                                    "uuid" -> bean.uuid = opt.value.toString()

                                    "password" -> bean.token = opt.value.toString()

                                    "skip-cert-verify" -> bean.allowInsecure =
                                        opt.value.toString() == "true"

                                    "disable-sni" -> bean.disableSNI =
                                        opt.value.toString() == "true"

                                    "reduce-rtt" -> bean.reduceRTT =
                                        opt.value.toString() == "true"

                                    "sni" -> bean.sni = opt.value.toString()

                                    "alpn" -> {
                                        val alpn = (opt.value as? (List<String>))
                                        bean.alpn = alpn?.joinToString("\n")
                                    }

                                    "congestion-controller" -> bean.congestionController =
                                        opt.value.toString()

                                    "udp-relay-mode" -> bean.udpRelayMode = opt.value.toString()

                                }
                            }
                            if (ip.isNotBlank()) {
                                if (bean.sni.isNullOrBlank() && !bean.serverAddress.isNullOrBlank() && !bean.serverAddress.isIpAddress()) {
                                    bean.sni = bean.serverAddress
                                }
                                bean.serverAddress = ip
                            }
                            proxies.add(bean)
                        }
                        else -> throw DataValidationException(
                            DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "${index + 1} (Clash)")
                    }
                }

                // Fix ent
                proxies.forEach {
                    it.initializeDefaultValues()
                    if (it is StandardV2RayBean) {
                        // 1. SNI
                        if (it.isTLS() && it.sni.isNullOrBlank() && !it.host.isNullOrBlank() && !it.host.isIpAddress()) {
                            it.sni = it.host
                        }
                        // 2. globalClientFingerprint
                        if (!it.realityPubKey.isNullOrBlank() && it.utlsFingerprint.isNullOrBlank()) {
                            it.utlsFingerprint = globalClientFingerprint
                            if (it.utlsFingerprint.isNullOrBlank()) it.utlsFingerprint = "chrome"
                        }
                    }
                }
                return ClashDependencies.expand(entries, proxies)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DataValidationException) {
                throw e
            } catch (e: Exception) {
                throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, if (entryIndex > 0) "$entryIndex (Clash YAML)" else "Clash YAML")
            }
        } else if (wireGuardDocument) {
            // wireguard
            try {
                proxies.addAll(parseWireGuard(text).map {
                    if (fileName.isNotBlank()) it.name = fileName.removeSuffix(".conf")
                    it
                })
                return proxies
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: DataValidationException) {
                throw e
            } catch (e: Exception) {
                throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "WireGuard")
            }
        }


        try {
            return parseProxies(text.decodeBase64UrlSafe()).takeIf { it.isNotEmpty() }
                ?: error("Not found")
        } catch (error: DataValidationException) {
            throw error
        } catch (error: SubscriptionFoundException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (e: Exception) {
            Logs.w(e)
        }

        try {
            return parseProxies(text).takeIf { it.isNotEmpty() } ?: error("Not found")
        } catch (e: SubscriptionFoundException) {
            throw e
        } catch (error: DataValidationException) {
            throw error
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (ignored: Exception) {
        }

        return null
    }

    fun clashCipher(cipher: String): String {
        return when (cipher) {
            "dummy" -> "none"
            else -> cipher
        }
    }

    fun parseWireGuard(conf: String): List<WireGuardBean> {
        fun invalidField(index: Int, field: String): Nothing = throw DataValidationException(
            DataValidationException.Reason.INVALID_SUBSCRIPTION_FIELD, "WireGuard", index, field)
        val ini = Ini(StringReader(conf))
        val iface = ini["Interface"] ?: invalidField(1, "Interface")
        val bean = WireGuardBean().applyDefaultValues()
        val localAddresses = iface.getAll("Address")
        if (localAddresses.isNullOrEmpty()) invalidField(1, "Interface.Address")
        bean.localAddress = localAddresses.flatMap { it.split(",") }.joinToString("\n")
        bean.privateKey = iface["PrivateKey"]?.takeIf { it.isNotBlank() } ?: invalidField(1, "Interface.PrivateKey")
        iface["MTU"]?.let { value ->
            bean.mtu = value.toIntOrNull()?.takeIf { it > 0 } ?: invalidField(1, "Interface.MTU")
        }
        val peers = ini.getAll("Peer")
        if (peers.isNullOrEmpty()) invalidField(1, "Peer")
        val beans = mutableListOf<WireGuardBean>()
        for ((index, peer) in peers.withIndex()) {
            val endpoint = peer["Endpoint"]
            if (endpoint.isNullOrBlank() || !endpoint.contains(":")) invalidField(index + 1, "Peer.Endpoint")

            val peerBean = bean.clone()
            peerBean.serverAddress = endpoint.substringBeforeLast(":")
            peerBean.serverPort = endpoint.substringAfterLast(":").toIntOrNull()?.takeIf { it in 1..65535 }
                ?: invalidField(index + 1, "Peer.Endpoint.port")
            peerBean.peerPublicKey = peer["PublicKey"]?.takeIf { it.isNotBlank() }
                ?: invalidField(index + 1, "Peer.PublicKey")
            peerBean.peerPreSharedKey = peer["PresharedKey"]
            beans.add(peerBean.applyDefaultValues())
        }
        if (beans.isEmpty()) error("Empty available peer list")
        return beans
    }

    fun parseJSON(json: Any): List<AbstractBean> {
        val proxies = ArrayList<AbstractBean>()

        if (json is JSONObject) {
            if ((json.has("type") && json.getStr("type") == null) ||
                (json.has("endpoints") && json.optJSONArray("endpoints") == null))
                throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "sing-box JSON")
            when {
                json.has("type") && (json.has("server") || json.has("realm") || OutboundDependencies.isEndpoint(json.optString("type"))) -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                        OutboundDependencies.collect(json, emptyList(), validateImportedDns = true)
                        name = json.optString("tag", "")
                    })
                }
                json.has("server") && (json.has("up") || json.has("up_mbps")) -> {
                    return listOf(json.parseHysteria1Json())
                }

                json.has("method") -> {
                    return listOf(json.parseShadowsocks())
                }

                json.has("remote_addr") -> {
                    return listOf(json.parseTrojanGo())
                }

                json.has("outbounds") || json.has("endpoints") -> {
                    val rawOutbounds = if (json.has("outbounds")) json.getJSONArray("outbounds") else JSONArray()
                    val outbounds = rawOutbounds.filterIsInstance<JSONObject>()
                    val rawEndpoints = json.optJSONArray("endpoints") ?: JSONArray()
                    val endpoints = rawEndpoints.filterIsInstance<JSONObject>()
                    if (outbounds.size != rawOutbounds.length() || endpoints.size != rawEndpoints.length() ||
                        (outbounds + endpoints).any { it.getStr("type") == null })
                        throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "sing-box JSON")
                    endpoints.firstOrNull { !OutboundDependencies.isEndpoint(it.optString("type")) }?.let {
                        throw DataValidationException(DataValidationException.Reason.UNSUPPORTED_ENDPOINT, it.optString("tag"))
                    }
                    val candidates = outbounds + endpoints
                    return candidates.mapNotNull {
                            val ty = it.getStr("type")
                            if (ty == null || ty == "" ||
                                ty == "dns" || ty == "block" || ty == "direct" || ty == "selector" || ty == "urltest"
                            ) {
                                null
                            } else {
                                it
                            }
                        }.map {
                            ConfigBean().apply {
                                applyDefaultValues()
                                type = 1
                                config = it.toStringPretty()
                                outboundDependencies = OutboundDependencies.collect(it, candidates, validateImportedDns = true).takeIf { it.isNotEmpty() }?.let { JSONArray(it).toString() }.orEmpty()
                                name = it.getStr("tag")
                            }
                        }
                }

                json.has("server") && json.has("server_port") -> {
                    return listOf(ConfigBean().applyDefaultValues().apply {
                        type = 1
                        config = json.toStringPretty()
                    })
                }
            }
        } else {
            json as JSONArray
            val candidates = json.filterIsInstance<JSONObject>()
            if (candidates.size != json.length())
                throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "JSON array")
            if (candidates.any { it.has("type") }) {
                return parseJSON(JSONObject().put("outbounds", json))
            }
            json.forEach { _, it ->
                if (!isJsonObjectValid(it))
                    throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "JSON array")
                val parsed = parseJSON(it)
                if (parsed.isEmpty())
                    throw DataValidationException(DataValidationException.Reason.INVALID_SUBSCRIPTION_ENTRIES, 1, "JSON array")
                proxies.addAll(parsed)
            }
        }

        proxies.forEach { it.initializeDefaultValues() }
        return proxies
    }

}
