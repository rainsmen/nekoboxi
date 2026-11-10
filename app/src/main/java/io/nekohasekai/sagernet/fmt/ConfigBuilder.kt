package io.nekohasekai.sagernet.fmt

import android.widget.Toast
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.VpnService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity.Companion.TYPE_CONFIG
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.fmt.ConfigBuildResult.IndexEntity
import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import io.nekohasekai.sagernet.fmt.hysteria.buildSingBoxOutboundHysteriaBean
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import io.nekohasekai.sagernet.fmt.naive.NaiveBean
import io.nekohasekai.sagernet.fmt.naive.buildSingBoxOutboundNaiveBean
import io.nekohasekai.sagernet.fmt.shadowsocks.ShadowsocksBean
import io.nekohasekai.sagernet.fmt.shadowsocks.buildSingBoxOutboundShadowsocksBean
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import io.nekohasekai.sagernet.fmt.socks.buildSingBoxOutboundSocksBean
import io.nekohasekai.sagernet.fmt.ssh.SSHBean
import io.nekohasekai.sagernet.fmt.ssh.buildSingBoxOutboundSSHBean
import io.nekohasekai.sagernet.fmt.tuic.TuicBean
import io.nekohasekai.sagernet.fmt.tuic.buildSingBoxOutboundTuicBean
import io.nekohasekai.sagernet.fmt.v2ray.StandardV2RayBean
import io.nekohasekai.sagernet.fmt.v2ray.buildSingBoxOutboundStandardV2RayBean
import io.nekohasekai.sagernet.fmt.wireguard.WireGuardBean
import io.nekohasekai.sagernet.fmt.wireguard.buildSingBoxEndpointWireguardBean
import io.nekohasekai.sagernet.fmt.tailscale.buildSingBoxEndpointTailscaleBean
import io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean
import io.nekohasekai.sagernet.ktx.isIpAddress
import io.nekohasekai.sagernet.ktx.mkPort
import moe.matsuri.nb4a.*
import moe.matsuri.nb4a.SingBoxOptions.*
import moe.matsuri.nb4a.plugin.Plugins
import moe.matsuri.nb4a.proxy.anytls.AnyTLSBean
import moe.matsuri.nb4a.proxy.anytls.buildSingBoxOutboundAnyTLSBean
import moe.matsuri.nb4a.proxy.snell.SnellBean
import moe.matsuri.nb4a.proxy.snell.buildSingBoxOutboundSnellBean
import moe.matsuri.nb4a.proxy.config.ConfigBean
import moe.matsuri.nb4a.proxy.shadowtls.ShadowTLSBean
import moe.matsuri.nb4a.proxy.shadowtls.buildSingBoxOutboundShadowTLSBean
import moe.matsuri.nb4a.utils.JavaUtil.gson
import moe.matsuri.nb4a.utils.Util
import moe.matsuri.nb4a.utils.listByLineOrComma

const val TAG_MIXED = "mixed-in"

const val TAG_PROXY = "proxy"
const val TAG_DIRECT = "direct"
const val TAG_BYPASS = "bypass"
const val TAG_BLOCK = "block"

const val LOCALHOST = "127.0.0.1"

class ConfigBuildResult(
    var config: String,
    var externalIndex: List<IndexEntity>,
    var mainEntId: Long,
    var trafficMap: Map<String, List<ProxyEntity>>,
    var profileTagMap: Map<Long, String>,
    val selectorGroupId: Long,
    val temporaryStateDirectory: java.io.File? = null,
) {
    data class IndexEntity(var chain: LinkedHashMap<Int, ProxyEntity>)
}

fun buildConfig(
    proxy: ProxyEntity, forTest: Boolean = false, forExport: Boolean = false
): ConfigBuildResult {
    val testState = if (forTest) java.io.File(io.nekohasekai.sagernet.ktx.app.noBackupFilesDir,
        "tailscale-tests/${java.util.UUID.randomUUID()}") else null
    fun isolateTestState(value: String): String {
        if (testState == null) return value
        val json = org.json.JSONObject(value)
        json.optJSONArray("endpoints")?.let { endpoints ->
            for (index in 0 until endpoints.length()) {
                val endpoint = endpoints.getJSONObject(index)
                if (endpoint.optString("type") == "tailscale") {
                    endpoint.put("state_directory", java.io.File(testState, "endpoint-$index").absolutePath)
                    if (endpoint.optBoolean("taildrop")) endpoint.put("taildrop_directory",
                        java.io.File(testState, "taildrop-$index").absolutePath)
                }
            }
        }
        return json.toString()
    }

    if (proxy.type == TYPE_CONFIG) {
        val bean = proxy.requireBean() as ConfigBean
        if (bean.type == 0) {
            return ConfigBuildResult(
                isolateTestState(bean.config),
                listOf(),
                proxy.id, //
                mapOf(TAG_PROXY to listOf(proxy)), //
                mapOf(proxy.id to TAG_PROXY), //
                -1L, testState
            )
        }
    }

    val trafficMap = HashMap<String, List<ProxyEntity>>()
    val tagMap = HashMap<Long, String>()
    val globalOutbounds = HashMap<Long, String>()
    val tailscaleDirectories = io.nekohasekai.sagernet.fmt.tailscale.TailscaleStateDirectories(
        io.nekohasekai.sagernet.database.ProfileMutations.currentGeneration())
    val group = SagerDatabase.groupDao.getById(proxy.groupId)

    fun ProxyEntity.resolveChainInternal(path: MutableSet<Long> = linkedSetOf()): MutableList<ProxyEntity> {
        val bean = requireBean()
        if (bean !is ChainBean) return mutableListOf(this)
        require(path.add(id)) { "Circular proxy chain at profile $id" }
        require(path.size <= 128) { "Proxy chain exceeds 128 nesting levels" }
        try {
            val beansMap = SagerDatabase.proxyDao.getEntities(bean.proxies).associateBy { it.id }
            val beanList = ArrayList<ProxyEntity>()
            for (proxyId in bean.proxies) {
                val item = requireNotNull(beansMap[proxyId]) { "Proxy chain refers to missing profile $proxyId" }
                beanList.addAll(item.resolveChainInternal(path))
            }
            if (beanList.isEmpty()) throw io.nekohasekai.sagernet.database.DataValidationException(
                io.nekohasekai.sagernet.database.DataValidationException.Reason.EMPTY_CHAIN, displayName())
            return beanList
        } finally { path.remove(id) }
    }

    fun ProxyEntity.resolveChain(): MutableList<ProxyEntity> {
        val thisGroup = SagerDatabase.groupDao.getById(groupId)
        val frontProxy = thisGroup?.frontProxy?.let { SagerDatabase.proxyDao.getById(it) }
        val landingProxy = thisGroup?.landingProxy?.let { SagerDatabase.proxyDao.getById(it) }
        require(thisGroup == null || thisGroup.frontProxy <= 0 || frontProxy != null) { "Missing front proxy" }
        require(thisGroup == null || thisGroup.landingProxy <= 0 || landingProxy != null) { "Missing landing proxy" }
        val list = resolveChainInternal().asReversed().toMutableList()
        if (frontProxy != null) {
            list.addAll(frontProxy.resolveChainInternal().asReversed())
        }
        if (landingProxy != null) {
            list.addAll(0, landingProxy.resolveChainInternal().asReversed())
        }
        return list
    }

    if (!forTest) ProfileManager.ensureDefaultRules()
    val extraRules = if (forTest) listOf() else SagerDatabase.rulesDao.enabledRules()
    val extraProxies =
        if (forTest) mapOf() else SagerDatabase.proxyDao.getEntities(extraRules.mapNotNull { rule ->
            rule.outbound.takeIf { it > 0 && it != proxy.id }
        }.toHashSet().toList()).associateBy { it.id }
    val buildSelector = !forTest && group?.isSelector == true && !forExport
    val userDNSRuleList = mutableListOf<DNSRule_DefaultOptions>()
    val domainListDNSDirectForce = mutableListOf<String>()
    val bypassDNSBeans = hashSetOf<AbstractBean>()
    val isVPN = DataStore.serviceMode == Key.MODE_VPN
    val bind = if (!forTest && DataStore.allowAccess) "0.0.0.0" else LOCALHOST
    val remoteDns = DataStore.remoteDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val directDNS = DataStore.directDns.split("\n")
        .mapNotNull { dns -> dns.trim().takeIf { it.isNotBlank() && !it.startsWith("#") } }
    val enableDnsRouting = DataStore.enableDnsRouting
    val useFakeDns = DataStore.enableFakeDns && !forTest
    val needSniff = DataStore.trafficSniffing > 0
    val externalIndexMap = ArrayList<IndexEntity>()
    val ipv6Mode = if (forTest) IPv6Mode.ENABLE else DataStore.ipv6Mode

    fun genDomainStrategy(noAsIs: Boolean): String {
        return when {
            !noAsIs -> ""
            ipv6Mode == IPv6Mode.DISABLE -> "ipv4_only"
            ipv6Mode == IPv6Mode.PREFER -> "prefer_ipv6"
            ipv6Mode == IPv6Mode.ONLY -> "ipv6_only"
            else -> "prefer_ipv4"
        }
    }

    return MyOptions().apply {
        experimental = when {
            forTest -> ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "cache-test-${System.nanoTime()}.db"
                }
            }
            else -> ExperimentalOptions().apply {
                cache_file = CacheFile().apply {
                    enabled = true
                    path = "cache.db"
                    store_fakeip = true
                    store_dns = true
                }
                if (DataStore.enableClashAPI) {
                    clash_api = ClashAPIOptions().apply {
                        external_controller = "127.0.0.1:9090"
                        external_ui = "../files/yacd"
                    }
                }
            }
        }

        log = LogOptions().apply {
            level = when (DataStore.logLevel) {
                0 -> "panic"
                1 -> "warn"
                2 -> "info"
                3 -> "debug"
                4 -> "trace"
                else -> "info"
            }
        }

        dns = DNSOptions().apply {
            servers = mutableListOf()
            rules = mutableListOf()
            if (!forTest) {
                // Answer repeated queries from the expired cache while refreshing in background.
                optimistic = true
            }
        }

        fun autoDnsDomainStrategy(s: String): String? {
            if (s.isNotEmpty()) {
                return s
            }
            return when (ipv6Mode) {
                IPv6Mode.DISABLE -> "ipv4_only"
                IPv6Mode.ENABLE -> "prefer_ipv4"
                IPv6Mode.PREFER -> "prefer_ipv6"
                IPv6Mode.ONLY -> "ipv6_only"
                else -> null
            }
        }

        // sing-box 1.14 deprecates the per-server and per-rule DNS `strategy`.
        // ipv4_only / ipv6_only are kept by answering the excluded record type with
        // an empty NOERROR response before the query is routed to the server.
        fun excludedQueryType(server: String): String? {
            val strategy = when (server) {
                "dns-fake" -> "ipv4_only"
                else -> autoDnsDomainStrategy(SingBoxOptionsUtil.domainStrategy(server))
            }
            return when (strategy) {
                "ipv4_only" -> "AAAA"
                "ipv6_only" -> "A"
                else -> null
            }
        }

        fun dnsRouteRules(
            server: String, match: () -> DNSRule_DefaultOptions
        ): List<DNSRule_DefaultOptions> {
            val route = match().apply { this.server = server }
            val excluded = excludedQueryType(server) ?: return listOf(route)
            return listOf(match().apply {
                query_type = listOf(excluded)
                action = "predefined"
                rcode = "NOERROR"
                // route-only options are rejected on predefined rules
                disable_cache = null
            }, route)
        }

        inbounds = mutableListOf()

        if (!forTest) {
            if (isVPN) inbounds.add(Inbound_TunOptions().apply {
                type = "tun"
                tag = "tun-in"
                stack = when (DataStore.tunImplementation) {
                    TunImplementation.GVISOR -> "gvisor"
                    TunImplementation.SYSTEM -> "system"
                    else -> "mixed"
                }
                endpoint_independent_nat = true
                mtu = DataStore.mtu
                // sing-box 1.12 removed inet4_address/inet6_address; use combined `address`.
                address = when (ipv6Mode) {
                    IPv6Mode.DISABLE -> listOf(VpnService.PRIVATE_VLAN4_CLIENT + "/28")
                    IPv6Mode.ONLY -> listOf(VpnService.PRIVATE_VLAN6_CLIENT + "/126")
                    else -> listOf(
                        VpnService.PRIVATE_VLAN4_CLIENT + "/28",
                        VpnService.PRIVATE_VLAN6_CLIENT + "/126",
                    )
                }
            })
            inbounds.add(Inbound_MixedOptions().apply {
                type = "mixed"
                tag = TAG_MIXED
                listen = bind
                listen_port = DataStore.mixedPort
            })
        }

        endpoints = mutableListOf()
        outbounds = mutableListOf()

        // init routing object
        route = RouteOptions().apply {
            auto_detect_interface = true
            // sing-box 1.13 no longer defaults an empty `final` to the first outbound;
            // it falls back to a synthesized DIRECT outbound. NekoBox relied on the old
            // behaviour, so unmatched traffic silently went direct (most sites unreachable
            // behind the GFW). Pin the default to the proxy explicitly.
            final_ = TAG_PROXY
            rules = mutableListOf()
            rule_set = mutableListOf()

            // sing-box 1.13 removed legacy inbound sniff/domain_strategy fields;
            // they are migrated to route rule actions and must precede routing rules.
            if (!forTest) {
                if (needSniff) rules.add(Rule_DefaultOptions().apply {
                    action = "sniff"
                })
                rules.add(Rule_DefaultOptions().apply { port = listOf(53); action = "hijack-dns" })
                rules.add(Rule_DefaultOptions().apply { protocol = listOf("dns"); action = "hijack-dns" })
                genDomainStrategy(DataStore.resolveDestination).takeIf { it.isNotEmpty() }
                    ?.let { st ->
                        rules.add(Rule_DefaultOptions().apply {
                            action = "resolve"
                            strategy = st
                        })
                    }
            }
        }

        // returns outbound tag
        fun buildChain(
            chainId: Long, entity: ProxyEntity
        ): String {
            tagMap[entity.id]?.let { return it }
            val profileList = entity.resolveChain()
            val chainTrafficSet = HashSet<ProxyEntity>().apply {
                plusAssign(profileList)
                add(entity)
            }

            var currentOutbound: SingBoxOption
            lateinit var pastOutbound: SingBoxOption
            lateinit var pastInboundTag: String
            var pastEntity: ProxyEntity? = null
            val externalChainMap = LinkedHashMap<Int, ProxyEntity>()
            externalIndexMap.add(IndexEntity(externalChainMap))
            val chainOutbounds = ArrayList<SingBoxOption>()

            // chainTagOut: v2ray outbound tag for this chain
            var chainTagOut = ""
            val chainTag = "c-$chainId"
            var muxApplied = false

            val defaultServerDomainStrategy = SingBoxOptionsUtil.domainStrategy("server")

            profileList.forEachIndexed { index, proxyEntity ->
                val bean = proxyEntity.requireBean()
                var terminalOutbound: SingBoxOption? = null
                val endpointRole = profileList.drop(index + 1).joinToString(",") { it.id.toString() }

                // tagOut: v2ray outbound tag for a profile
                // profile2 (in) (global)   tag g-(id)
                // profile1                 tag (chainTag)-(id)
                // profile0 (out)           tag (chainTag)-(id) / single: "proxy"
                var tagOut = "$chainTag-$index-${proxyEntity.id}"

                // needGlobal: can only contain one?
                var needGlobal = false

                // first profile set as global
                if (index == profileList.lastIndex) {
                    // Entry tags have their own counters even when they use the same node.
                    needGlobal = index > 0
                    if (needGlobal) tagOut = "g-" + proxyEntity.id
                    bypassDNSBeans += proxyEntity.requireBean()
                }

                // last profile set as "proxy"
                if (chainId == 0L && index == 0) {
                    tagOut = TAG_PROXY
                }

                // Display names are user input, not unique internal outbound identifiers.
                if (buildSelector && index == 0) tagOut = "profile-${entity.id}"


                // Resolve reuse before writing the preceding hop's reference.
                val reusedTag = if (needGlobal) globalOutbounds[proxyEntity.id] else null
                if (reusedTag != null) tagOut = reusedTag
                if (index > 0) {
                    if (pastEntity!!.needExternal()) {
                        route.rules.add(Rule_DefaultOptions().apply {
                            inbound = listOf(pastInboundTag)
                            outbound = tagOut
                        })
                    } else {
                        pastOutbound._hack_config_map["detour"] = tagOut
                    }
                } else {
                    chainTagOut = tagOut
                }
                if (reusedTag != null) return@forEachIndexed
                if (needGlobal) globalOutbounds[proxyEntity.id] = tagOut

                if (proxyEntity.needExternal()) { // externel outbound
                    val localPort = mkPort()
                    externalChainMap[localPort] = proxyEntity
                    currentOutbound = Outbound_SocksOptions().apply {
                        type = "socks"
                        server = LOCALHOST
                        server_port = localPort
                    }
                } else {
                    // internal outbound

                    currentOutbound = when (bean) {
                        is ConfigBean -> {
                            val imported = moe.matsuri.nb4a.proxy.config.OutboundDependencies.build(bean, tagOut)
                            imported.forEachIndexed { dependencyIndex, option ->
                                if (option is Endpoint && option._hack_config_map["type"] == "tailscale" &&
                                    option._hack_config_map["state_directory"].toString().let { it == "null" || it.isBlank() }) {
                                    option._hack_config_map["state_directory"] = tailscaleDirectories.allocate(
                                        proxyEntity.id, option._hack_config_map["auth_key"]?.toString().orEmpty(),
                                        option._hack_config_map["control_url"]?.toString().orEmpty(), "$endpointRole/imported/$dependencyIndex")
                                }
                            }
                            imported.drop(1).forEach { dependency ->
                                if (dependency is Endpoint) endpoints.add(dependency) else outbounds.add(dependency)
                            }
                            chainOutbounds.addAll(imported.drop(1))
                            terminalOutbound = imported.last()
                            imported.first()
                        }

                        is ShadowTLSBean -> // before StandardV2RayBean
                            buildSingBoxOutboundShadowTLSBean(bean)

                        is StandardV2RayBean -> // http/trojan/vmess/vless
                            buildSingBoxOutboundStandardV2RayBean(bean)

                        is HysteriaBean ->
                            buildSingBoxOutboundHysteriaBean(bean)

                        is TuicBean ->
                            buildSingBoxOutboundTuicBean(bean)

                        is SOCKSBean ->
                            buildSingBoxOutboundSocksBean(bean)

                        is ShadowsocksBean ->
                            buildSingBoxOutboundShadowsocksBean(bean)

                        is WireGuardBean ->
                            buildSingBoxEndpointWireguardBean(bean, tagOut)

                        is SSHBean ->
                            buildSingBoxOutboundSSHBean(bean)

                        is AnyTLSBean ->
                            buildSingBoxOutboundAnyTLSBean(bean)

                        is SnellBean ->
                            buildSingBoxOutboundSnellBean(bean)

                        is NaiveBean ->
                            buildSingBoxOutboundNaiveBean(bean)

                        // Endpoints implement Outbound. Routing through the actual endpoint
                        // respects selectors, user rules, exit nodes and outer detour chains.
                        is TailscaleBean -> buildSingBoxEndpointTailscaleBean(bean, tagOut,
                            tailscaleDirectories.allocate(proxyEntity.id, bean.authKey, bean.controlUrl, endpointRole))

                        else -> throw IllegalStateException("can't reach")
                    }

                    // internal mux
                    if (!muxApplied) {
                        val muxObj = proxyEntity.singMux()
                        if (muxObj != null && muxObj.enabled) {
                            muxApplied = true
                            currentOutbound._hack_config_map["multiplex"] = muxObj.asMap()
                        }
                    }
                }

                // internal & external
                currentOutbound.apply {
                    // udp over tcp
                    try {
                        val sUoT = bean.javaClass.getField("sUoT").get(bean)
                        if (sUoT is Boolean && sUoT) {
                            _hack_config_map["udp_over_tcp"] = true
                        }
                    } catch (_: Exception) {
                    }

                    // domain_strategy
                    pastEntity?.requireBean()?.apply {
                        // don't loopback
                        if (defaultServerDomainStrategy != "" && !serverAddress.isIpAddress()) {
                            domainListDNSDirectForce.add("full:$serverAddress")
                        }
                    }
                    _hack_config_map["tag"] = tagOut

                    _hack_custom_config = bean.customOutboundJson
                }

                // External proxy need a dokodemo-door inbound to forward the traffic
                // For external proxy software, their traffic must goes to v2ray-core to use protected fd.
                bean.finalAddress = bean.serverAddress
                bean.finalPort = bean.serverPort
                if (bean.canMapping() && proxyEntity.needExternal()) {
                    // With ss protect, don't use mapping
                    var needExternal = true
                    if (index == profileList.lastIndex) {
                        val pluginId = when (bean) {
                            is HysteriaBean -> if (bean.protocolVersion == 1) "hysteria-plugin" else "hysteria2-plugin"
                            is NaiveBean -> "naive-plugin"
                            else -> ""
                        }
                        // naive (bundled cronet .so) can't protect its own sockets via
                        // protect_path like the matsuri Go plugins do, so it must use the
                        // mapping path (dial 127.0.0.1:mappingPort -> direct inbound ->
                        // box's protected dialer). Otherwise its upstream socket enters the
                        // TUN and loops back through route.final=proxy.
                        if (bean !is NaiveBean && Plugins.isUsingMatsuriExe(pluginId)) {
                            needExternal = false
                        } else if (Plugins.getPluginExternal(pluginId) != null) {
                            throw Exception("You are using an unsupported $pluginId, please download the correct plugin.")
                        }
                    }
                    if (needExternal) {
                        val mappingPort = mkPort()
                        bean.finalAddress = LOCALHOST
                        bean.finalPort = mappingPort

                        inbounds.add(Inbound_DirectOptions().apply {
                            type = "direct"
                            listen = LOCALHOST
                            listen_port = mappingPort
                            tag = "$chainTag-mapping-$index-${proxyEntity.id}"

                            override_address = bean.serverAddress
                            override_port = bean.serverPort

                            pastInboundTag = tag

                            // no chain rule and not outbound, so need to set to direct
                            if (index == profileList.lastIndex) {
                                route.rules.add(Rule_DefaultOptions().apply {
                                    inbound = listOf(tag)
                                    outbound = TAG_DIRECT
                                })
                            }
                        })
                    }
                }

                if (currentOutbound is Endpoint) {
                    endpoints.add(currentOutbound)
                } else {
                    outbounds.add(currentOutbound)
                }
                chainOutbounds.add(currentOutbound)
                pastOutbound = terminalOutbound ?: currentOutbound
                pastEntity = proxyEntity
            }

            trafficMap[chainTagOut] = chainTrafficSet.toList()
            tagMap[entity.id] = chainTagOut
            return chainTagOut
        }

        // build outbounds
        if (buildSelector) {
            val list = group.id.let { SagerDatabase.proxyDao.getByGroup(it) }
            list.forEach {
                tagMap[it.id] = buildChain(it.id, it)
            }
            outbounds.add(0, Outbound_SelectorOptions().apply {
                type = "selector"
                tag = TAG_PROXY
                default_ = tagMap[proxy.id]
                outbounds = tagMap.values.toList()
            })
        } else {
            buildChain(0, proxy)
        }
        // build outbounds from route item
        extraProxies.forEach { (key, p) ->
            tagMap[key] = buildChain(key, p)
        }

        // apply user rules
        for (rule in extraRules) {
            // Match by package name in the core: a rule whose apps are not installed
            // matches nothing, instead of silently dropping the app condition.
            val packageList = rule.packages.toList()
            if (packageList.isNotEmpty() && !isVPN) {
                Toast.makeText(
                    SagerNet.application,
                    SagerNet.application.getString(R.string.route_need_vpn, rule.displayName()),
                    Toast.LENGTH_SHORT
                ).show()
            }
            val ruleSets = mutableListOf<RuleSet>()

            val ruleObj = Rule_DefaultOptions().apply {
                if (packageList.isNotEmpty()) package_name = packageList
                var domainList: List<String>? = null
                if (rule.domains.isNotBlank()) {
                    domainList = rule.domains.listByLineOrComma()
                    makeSingBoxRule(domainList, false)
                }
                if (rule.ip.isNotBlank()) {
                    makeSingBoxRule(rule.ip.listByLineOrComma(), true)
                }

                if (rule_set != null) generateRuleSet(rule_set, ruleSets)

                if (rule.port.isNotBlank()) {
                    port = mutableListOf<Int>()
                    port_range = mutableListOf<String>()
                    rule.port.listByLineOrComma().map {
                        if (it.contains(":")) {
                            port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { port.add(this) }
                        }
                    }
                }
                if (rule.sourcePort.isNotBlank()) {
                    source_port = mutableListOf<Int>()
                    source_port_range = mutableListOf<String>()
                    rule.sourcePort.listByLineOrComma().map {
                        if (it.contains(":")) {
                            source_port_range.add(it)
                        } else {
                            it.toIntOrNull()?.apply { source_port.add(this) }
                        }
                    }
                }
                if (rule.network.isNotBlank()) {
                    network = listOf(rule.network)
                }
                if (rule.source.isNotBlank()) {
                    source_ip_cidr = rule.source.listByLineOrComma()
                }
                if (rule.protocol.isNotBlank()) {
                    protocol = rule.protocol.listByLineOrComma()
                }

                fun makeDnsRuleObj(): DNSRule_DefaultOptions {
                    return DNSRule_DefaultOptions().apply {
                        if (packageList.isNotEmpty()) package_name = packageList
                        domainList?.let { makeSingBoxRule(it) }
                        if (rule.source.isNotBlank()) source_ip_cidr = rule.source.listByLineOrComma()
                        if (rule.sourcePort.isNotBlank()) {
                            val ports = rule.sourcePort.listByLineOrComma()
                            source_port = ports.filterNot { ":" in it }.map(String::toInt)
                            source_port_range = ports.filter { ":" in it }
                        }
                        if (rule.network.isNotBlank()) network = listOf(rule.network)
                    }
                }

                if (rule.hasConditions() && rule.ip.isBlank() && rule.port.isBlank() && rule.config.isBlank() &&
                    (rule.protocol.isBlank() || rule.protocol.trim() == "dns")) when (rule.outbound) {
                    -1L -> {
                        userDNSRuleList += dnsRouteRules("dns-direct", ::makeDnsRuleObj)
                    }

                    0L -> {
                        // fakeip only answers A/AAAA; other types fall through to dns-remote.
                        if (useFakeDns) userDNSRuleList += dnsRouteRules("dns-fake") {
                            makeDnsRuleObj().apply {
                                inbound = listOf("tun-in")
                                query_type = listOf("A", "AAAA")
                            }
                        }
                        userDNSRuleList += dnsRouteRules("dns-remote", ::makeDnsRuleObj)
                    }

                    -2L -> {
                        userDNSRuleList += makeDnsRuleObj().apply {
                            action = "predefined"
                            rcode = "NOERROR"
                        }
                    }
                }

                outbound = when (val outId = rule.outbound) {
                    0L -> TAG_PROXY
                    -1L -> TAG_BYPASS
                    -2L -> TAG_BLOCK
                    else -> if (!buildSelector && outId == proxy.id) TAG_PROXY else tagMap[outId] ?: ""
                }

                _hack_custom_config = rule.config
            }

            if (!ruleObj.checkEmpty()) {
                require(!ruleObj.outbound.isNullOrBlank()) { "${rule.displayName()}: missing outbound" }
                run {
                    // block 改用新的写法
                    if (ruleObj.outbound == TAG_BLOCK) {
                        ruleObj.outbound = null
                        ruleObj.action = "reject"
                    }
                    route.rules.add(ruleObj)
                    route.rule_set.addAll(ruleSets)
                }
            }
        }

        // 对 rule_set tag 去重
        if (route.rule_set != null) {
            route.rule_set = route.rule_set.distinctBy { it.tag }
        }

        for (freedom in arrayOf(TAG_DIRECT, TAG_BYPASS)) outbounds.add(Outbound().apply {
            tag = freedom
            type = "direct"
        })

        // Bypass Lookup for the first profile
        bypassDNSBeans.forEach {
            var serverAddr = it.serverAddress

            if (it is ConfigBean) {
                var config = mutableMapOf<String, Any>()
                config = gson.fromJson(it.config, config.javaClass)
                config["server"]?.apply {
                    serverAddr = toString()
                }
            }

            if (!serverAddr.isIpAddress()) {
                domainListDNSDirectForce.add("full:${serverAddr}")
            }
        }

        remoteDns.forEach { address ->
            DnsServerParser.hostOf(address)?.takeIf { !it.isIpAddress() }?.let {
                domainListDNSDirectForce.add("full:$it")
            }
        }

        // Typed DNS servers (the legacy `address` format was removed in sing-box 1.14).
        // A typed server without `detour` dials directly, so only dns-remote sets one.
        dns.servers.add(DNSServerOptions().apply {
            type = "local"
            tag = "dns-local"
        })

        directDNS.firstOrNull().let {
            dns.servers.add(DnsServerParser.parse(
                it ?: throw Exception("No direct DNS, check your settings!"), "dns-direct"
            ).apply {
                if (DnsServerParser.isRemote(this)) {
                    domain_resolver = DomainResolveOptions().apply { server = "dns-local" }
                }
            })
        }

        remoteDns.firstOrNull().let {
            // Always use direct DNS for urlTest
            if (!forTest) dns.servers.add(DnsServerParser.parse(
                it ?: throw Exception("No remote DNS, check your settings!"), "dns-remote"
            ).apply {
                if (DnsServerParser.isRemote(this)) {
                    detour = TAG_PROXY
                    domain_resolver = DomainResolveOptions().apply { server = "dns-direct" }
                }
            })
        }

        // Server addresses of outbounds resolve through direct DNS (replaces the
        // deprecated `outbound: any` DNS rule and per-outbound `domain_strategy`).
        route.default_domain_resolver = DomainResolveOptions().apply {
            server = "dns-direct"
            strategy = SingBoxOptionsUtil.domainStrategy("server").takeIf { !forTest && it.isNotEmpty() }
        }

        dns.final_ = if (forTest) "dns-direct" else "dns-remote"

        // dns object user rules
        if (enableDnsRouting) {
            // A protocol=dns-only rule intentionally matches every DNS query.
            dns.rules.addAll(userDNSRuleList)
        }

        if (forTest) {
            dns.rules = listOf()
        } else {
            if (DataStore.bypassLanInCore) {
                route.rules.add(Rule_DefaultOptions().apply {
                    outbound = TAG_BYPASS
                    ip_is_private = true
                })
            }
            // block mcast
            route.rules.add(Rule_DefaultOptions().apply {
                ip_cidr = listOf("224.0.0.0/3", "ff00::/8")
                action = "reject"
            })
            // FakeDNS obj
            if (useFakeDns) {
                dns.servers.add(DNSServerOptions().apply {
                    type = "fakeip"
                    tag = "dns-fake"
                    inet4_range = "198.18.0.0/15"
                    inet6_range = "fc00::/18"
                })
                // fakeip only answers A/AAAA; other query types (HTTPS-RR type 65 used
                // by Chrome for HTTP3/ECH) must fall through to the real resolver
                // (dns.final = dns-remote), otherwise the whole response fails.
                dns.rules.addAll(dnsRouteRules("dns-fake") {
                    DNSRule_DefaultOptions().apply {
                        inbound = listOf("tun-in")
                        query_type = listOf("A", "AAAA")
                        disable_cache = true
                    }
                })
            }
            // force bypass (always top DNS rule)
            if (domainListDNSDirectForce.isNotEmpty()) {
                dns.rules.addAll(0, dnsRouteRules("dns-direct") {
                    DNSRule_DefaultOptions().apply {
                        makeSingBoxRule(domainListDNSDirectForce.toHashSet().toList())
                    }
                })
            }
            // ipv4_only / ipv6_only for queries that fall through to dns.final
            excludedQueryType("dns-remote")?.let { excluded ->
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf(excluded)
                    action = "predefined"
                    rcode = "NOERROR"
                })
            }
            // Smart DNS (sing-box 1.14 evaluate/match_response): for queries that reach
            // here, ask dns-direct and keep a China answer; otherwise dns.final (remote).
            if (DataStore.dnsSmartCn && !useFakeDns && enableDnsRouting) {
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf("A", "AAAA")
                    action = "evaluate"
                    server = "dns-direct"
                })
                dns.rules.add(DNSRule_DefaultOptions().apply {
                    query_type = listOf("A", "AAAA")
                    rule_set = listOf("geoip:cn")
                    action = "respond"
                    _hack_config_map["match_response"] = true
                })
                if (route.rule_set.none { it.tag == "geoip:cn" }) {
                    route.rule_set = route.rule_set + mutableListOf<RuleSet>().apply {
                        generateRuleSet(listOf("geoip:cn"), this)
                    }
                }
            }
        }

        if (!forTest) {
            DataStore.certificates.takeIf { it.isNotBlank() }?.let { pem ->
                certificate = CertificateOptions().apply {
                    store = "system"
                    certificate = listOf(pem)
                }
            }
        }

        if (!forTest) _hack_custom_config = DataStore.globalCustomConfig
    }.let {
        val configMap = it.asMap()
        Util.mergeJSON(configMap, proxy.requireBean().customConfigJson)
        ConfigBuildResult(
            isolateTestState(gson.toJson(configMap)),
            externalIndexMap,
            proxy.id,
            trafficMap,
            tagMap,
            if (buildSelector) group.id else -1L,
            testState
        )
    }

}
