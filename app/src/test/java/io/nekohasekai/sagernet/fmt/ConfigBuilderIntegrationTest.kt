package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import io.nekohasekai.sagernet.fmt.internal.ChainBean
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.net.ServerSocket

@Implements(SagerNet::class, isInAndroidSdk = false)
class QuietSagerNet : org.robolectric.shadows.ShadowApplication() {
    // Keep real Application attach/Room/ConfigBuilder; only native Android initialization is excluded.
    @Implementation fun onCreate() = Unit
}

@Implements(moe.matsuri.nb4a.utils.JavaUtil::class, isInAndroidSdk = false)
class FixtureProcess {
    companion object { @JvmStatic @Implementation fun getProcessName() = "fixture" }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class])
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class ConfigBuilderIntegrationTest {
    private lateinit var profile: ProxyEntity
    @Before fun setup() {
        DataStore.serviceMode = Key.MODE_PROXY
        DataStore.rulesFirstCreate = true
        DataStore.enableClashAPI = false
        DataStore.dnsSmartCn = false
        DataStore.allowAccess = false
        DataStore.trafficSniffing = 1
        DataStore.enableDnsRouting = true
        DataStore.directDns = "local"
        DataStore.remoteDns = "udp://127.0.0.1:1053"
        DataStore.bypassLanInCore = false
        DataStore.resolveDestination = false
        val database = SagerDatabase.instance
        database.clearAllTables()
        database.groupDao().insert(listOf(ProxyGroup(id = 1, name = "integration")))
        profile = ProxyEntity(id = 1, groupId = 1).apply {
            putBean(SOCKSBean().apply {
                initializeDefaultValues(); serverAddress = "127.0.0.1"; serverPort = 1081; name = "fixture"
            })
        }
        database.proxyDao().insert(listOf(profile))
        database.rulesDao().insert(listOf(
            RuleEntity(enabled = true, id = 1, userOrder = 1, domains = "full:example.com", source = "192.168.1.20/32", outbound = -2),
            RuleEntity(enabled = true, id = 2, userOrder = 2, protocol = "bittorrent", outbound = -2),
            RuleEntity(enabled = true, id = 3, userOrder = 3, network = "udp", outbound = -2),
            RuleEntity(enabled = true, id = 4, userOrder = 4, sourcePort = "12345", outbound = -2),
            RuleEntity(enabled = true, id = 5, userOrder = 5, ip = "geoip:private", outbound = -1),
        ))
        DataStore.enableFakeDns = false
        DataStore.mixedPort = ServerSocket(0).use { it.localPort }
    }

    @Test fun wideChainsAreRejectedBeforeBulkLookup() {
        val wide = chain(2, *LongArray(5000) { 1L })
        SagerDatabase.proxyDao.insert(listOf(wide))
        assertEquals(DataValidationException.Reason.CHAIN_TOO_LARGE,
            assertThrows(DataValidationException::class.java) { buildConfig(wide) }.reason)
    }

    @Test fun chainBudgetIsSharedAcrossSelectorRoots() {
        // Every individual root fits; all selector roots together exceed the budget.
        SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(1)!!.apply { isSelector = true })
        val chains = (2L..71L).map { chain(it, *LongArray(60) { 1L }) }
        SagerDatabase.proxyDao.insert(chains)
        buildConfig(chains.first(), forTest = true)
        assertEquals(DataValidationException.Reason.CHAIN_TOO_LARGE,
            assertThrows(DataValidationException::class.java) { buildConfig(profile) }.reason)
    }

    @Test fun exponentiallyRepeatedChainsFailWithinTheExpansionBudget() {
        var previous = profile.id
        for (id in 2L..31L) {
            val chain = ProxyEntity(id = id, groupId = 1).apply {
                putBean(ChainBean().apply { initializeDefaultValues(); proxies = listOf(previous, previous) })
            }
            SagerDatabase.proxyDao.insert(listOf(chain))
            previous = id
        }
        val error = assertThrows(DataValidationException::class.java) {
            buildConfig(SagerDatabase.proxyDao.getById(previous)!!)
        }
        assertEquals(DataValidationException.Reason.CHAIN_TOO_LARGE, error.reason)
    }

    @Test fun clashSourceRemainsIdenticalWhileRuntimeTlsSettingChanges() {
        val entries = listOf(mapOf<String, Any?>("name" to "root", "dialer-proxy" to "next"), mapOf("name" to "next"))
        val beans = listOf("root", "next").map { name ->
            io.nekohasekai.sagernet.fmt.trojan.TrojanBean().apply {
                initializeDefaultValues(); this.name = name; serverAddress = "example.org"; serverPort = 443; password = "fixture"
            }
        }
        try {
            DataStore.globalAllowInsecure = false
            val before = io.nekohasekai.sagernet.group.ClashDependencies.expand(entries, beans).first() as moe.matsuri.nb4a.proxy.config.ConfigBean
            DataStore.globalAllowInsecure = true
            val after = io.nekohasekai.sagernet.group.ClashDependencies.expand(entries, beans).first() as moe.matsuri.nb4a.proxy.config.ConfigBean
            assertEquals(before.config, after.config)
            assertEquals(before.outboundDependencies, after.outboundDependencies)
            assertEquals(moe.matsuri.nb4a.proxy.config.OutboundDependencies.identity(before), moe.matsuri.nb4a.proxy.config.OutboundDependencies.identity(after))
            for (enabled in listOf(true, false)) {
                DataStore.globalAllowInsecure = enabled
                val runtime = moe.matsuri.nb4a.proxy.config.OutboundDependencies.build(after, "fixture")
                assertEquals(listOf(enabled, enabled), runtime.map { (it.asMap()["tls"] as Map<*, *>)["insecure"] })
            }
        } finally { DataStore.globalAllowInsecure = false }
    }

    @Test fun normalProxyConfigurationPreservesConditionsAndStartsInCore() {
        val output = File(requireNotNull(System.getProperty("thbox.configFixtures")))
        output.mkdirs()
        for (fakeDns in listOf(false, true)) {
            DataStore.enableFakeDns = fakeDns
            DataStore.mixedPort = ServerSocket(0).use { it.localPort }
            val config = buildConfig(profile, forTest = false, forExport = false).config
            val json = JSONObject(config)
            val route = json.getJSONObject("route").getJSONArray("rules")
            val rules = (0 until route.length()).map(route::getJSONObject)
            val sniff = rules.indexOfFirst { it.optString("action") == "sniff" }
            val hijack = rules.indexOfFirst { it.optJSONArray("protocol")?.optString(0) == "dns" }
            assertTrue(sniff >= 0 && hijack > sniff)
            assertTrue(rules.any { it.optJSONArray("protocol")?.optString(0) == "bittorrent" })
            assertTrue(rules.any { it.optJSONArray("source_port")?.optInt(0) == 12345 })
            assertTrue(rules.any { it.optBoolean("ip_is_private") })
            val multicast = rules.single { it.optJSONArray("ip_cidr")?.optString(0) == "224.0.0.0/3" }
            assertFalse(multicast.has("source_ip_cidr"))
            val dns = json.getJSONObject("dns").getJSONArray("rules")
            val blocked = (0 until dns.length()).map(dns::getJSONObject).single {
                it.optJSONArray("domain")?.optString(0) == "example.com"
            }
            assertEquals("192.168.1.20/32", blocked.getJSONArray("source_ip_cidr").getString(0))
            assertTrue((0 until dns.length()).map(dns::getJSONObject).any {
                it.optJSONArray("source_port")?.optInt(0) == 12345 && !it.has("domain")
            })
            assertTrue((0 until dns.length()).map(dns::getJSONObject).any {
                it.optJSONArray("network")?.optString(0) == "udp" && !it.has("domain")
            })
            File(output, "proxy-fake-dns-$fakeDns.json").writeText(config)
        }
    }
    private fun chain(id: Long, vararg nodes: Long) = ProxyEntity(id = id, groupId = 1).apply {
        putBean(ChainBean().apply { initializeDefaultValues(); name = "chain-$id"; proxies = nodes.toList() })
    }

    @Test fun sharedChainNodesAlwaysReferenceExistingTags() {
        val database = SagerDatabase.instance
        val extra = ProxyEntity(id = 2, groupId = 1).apply {
            putBean(SOCKSBean().apply { initializeDefaultValues(); name = "second"; serverAddress = "127.0.0.1"; serverPort = 1082 })
        }
        database.proxyDao().insert(listOf(extra, chain(3, 1, 2)))
        database.rulesDao().insert(listOf(RuleEntity(enabled = true, id = 6, domains = "chain.example", outbound = 3)))
        val directory = File(requireNotNull(System.getProperty("thbox.configFixtures"))).apply { mkdirs() }
        for (selector in listOf(false, true)) {
            database.groupDao().updateGroup(database.groupDao().getById(1)!!.copy(isSelector = selector))
            val config = buildConfig(profile, false, false).config
            val json = JSONObject(config)
            val array = json.getJSONArray("outbounds")
            val outbounds = (0 until array.length()).map(array::getJSONObject)
            val tags = outbounds.map { it.getString("tag") }.toSet()
            assertEquals(outbounds.size, tags.size)
            val detours = outbounds.filter { it.has("detour") }.map { it.getString("detour") }
            assertTrue(detours.isNotEmpty())
            detours.forEach { assertTrue("Missing detour $it in $tags", it in tags) }
            File(directory, "shared-chain-selector-$selector.json").writeText(config)
        }
    }

    @Test fun circularChainsFailWithAnActionableError() {
        val database = SagerDatabase.instance
        val first = chain(2, 3)
        database.proxyDao().insert(listOf(first, chain(3, 2)))
        val error = assertThrows(IllegalArgumentException::class.java) { buildConfig(first, false, false) }
        assertTrue(error.message.orEmpty().contains("Circular proxy chain"))
    }

    private fun socks(id: Long, name: String) = ProxyEntity(id = id, groupId = 1).apply {
        putBean(SOCKSBean().apply { initializeDefaultValues(); this.name = name; serverAddress = "127.0.0.1"; serverPort = 1080 + id.toInt() })
    }
    private fun outbounds(result: ConfigBuildResult): List<JSONObject> =
        JSONObject(result.config).getJSONArray("outbounds").let { array -> (0 until array.length()).map(array::getJSONObject) }

    @Test fun explicitSelectedProfileRuleDoesNotFollowTheDefaultSelector() {
        val db = SagerDatabase.instance
        db.proxyDao().insert(listOf(socks(2, "B")))
        db.groupDao().updateGroup(db.groupDao().getById(1)!!.copy(isSelector = true))
        db.rulesDao().insert(listOf(
            RuleEntity(id = 8, enabled = true, domains = "fixed.example", outbound = profile.id),
            RuleEntity(id = 9, enabled = true, domains = "default.example", outbound = 0)))
        for (selected in listOf(profile, db.proxyDao().getById(2)!!)) {
            val result = buildConfig(selected)
            val rules = JSONObject(result.config).getJSONObject("route").getJSONArray("rules")
            fun target(domain: String) = (0 until rules.length()).map(rules::getJSONObject)
                .single { it.toString().contains(domain) }.getString("outbound")
            assertEquals(result.profileTagMap[profile.id], target("fixed.example"))
            assertEquals("proxy", target("default.example"))
            assertNotEquals("proxy", target("fixed.example"))
        }
    }

    @Test fun reservedDisplayNamesAndRoutedThreeHopChainHaveUniqueTags() {
        val db = SagerDatabase.instance
        db.proxyDao().insert(listOf(socks(2, "proxy"), socks(3, "direct"), socks(4, "bypass"), chain(5, 2, 3, 4)))
        db.groupDao().updateGroup(db.groupDao().getById(1)!!.copy(isSelector = true))
        db.rulesDao().insert(listOf(RuleEntity(enabled = true, id = 6, domains = "three.example", outbound = 5)))
        val result = buildConfig(profile)
        val all = outbounds(result)
        assertEquals(all.size, all.map { it.getString("tag") }.toSet().size)
        assertEquals(2, all.count { it.optString("server_port") == "1083" })
        val directory = File(requireNotNull(System.getProperty("thbox.configFixtures"))).apply { mkdirs() }
        File(directory, "three-hop-reserved-selector.json").writeText(result.config)
    }

    @Test fun nestedAndFlatChainsUseTheSameHopOrder() {
        val db = SagerDatabase.instance
        db.proxyDao().insert(listOf(socks(2, "B"), socks(3, "C"), chain(4, 1, 2), chain(5, 4, 3), chain(6, 1, 2, 3)))
        fun ports(id: Long): List<Int> {
            val all = outbounds(buildConfig(db.proxyDao().getById(id)!!)).associateBy { it.getString("tag") }
            val result = mutableListOf<Int>()
            var tag = "proxy"
            while (tag.isNotEmpty()) {
                val outbound = all.getValue(tag)
                result += outbound.getInt("server_port")
                tag = outbound.optString("detour")
            }
            return result
        }
        assertEquals(listOf(1083, 1082, 1081), ports(5))
        assertEquals(ports(6), ports(5))
    }

    @Test fun groupFrontAndLandingChainsExpandInOrder() {
        val db = SagerDatabase.instance
        db.groupDao().insert(listOf(ProxyGroup(id = 2, name = "helpers")))
        db.proxyDao().insert(listOf(socks(2, "front-first"), socks(3, "front-last"), chain(4, 2, 3),
            socks(5, "landing-first"), socks(6, "landing-last"), chain(7, 5, 6)).map { it.copy(groupId = 2) })
        db.groupDao().updateGroup(db.groupDao().getById(1)!!.copy(frontProxy = 4, landingProxy = 7))
        val result = buildConfig(profile)
        val all = outbounds(result).associateBy { it.getString("tag") }
        val ports = mutableListOf<Int>()
        var tag = "proxy"
        while (tag.isNotEmpty()) {
            val outbound = all.getValue(tag)
            ports += outbound.getInt("server_port")
            tag = outbound.optString("detour")
        }
        assertEquals(listOf(1086, 1085, 1081, 1083, 1082), ports)
        writeFixture("group-front-landing-chains.json", result)
    }

    @Test fun protocolOnlyDnsRejectIsKeptAsCatchAllAction() {
        SagerDatabase.rulesDao.insert(listOf(RuleEntity(enabled = true, protocol = "dns", outbound = -2)))
        val result = buildConfig(profile)
        val dns = JSONObject(result.config).getJSONObject("dns").getJSONArray("rules")
        val reject = (0 until dns.length()).map(dns::getJSONObject).first { it.optString("action") == "predefined" }
        assertEquals("NOERROR", reject.getString("rcode"))
        assertFalse(reject.has("query_type"))
        writeFixture("protocol-only-dns-block.json", result)
    }

    @Test fun importedDetoursSurviveSelectorsAndAnAdditionalFrontProxy() {
        val imported = io.nekohasekai.sagernet.group.RawUpdater.parseJSON(JSONObject("""{"outbounds":[
            {"type":"socks","tag":"a","server":"127.0.0.1","server_port":1101,"detour":"b"},
            {"type":"socks","tag":"b","server":"127.0.0.1","server_port":1102}]}"""))
        val node = ProxyEntity(id = 2, groupId = 1).apply { putBean(imported.first()) }
        val db = SagerDatabase.instance
        db.proxyDao().insert(listOf(node))
        for (selector in listOf(false, true)) {
            db.groupDao().updateGroup(db.groupDao().getById(1)!!.copy(isSelector = selector, frontProxy = 1))
            val result = buildConfig(node)
            val all = outbounds(result).associateBy { it.getString("tag") }
            val root = all.getValue(result.profileTagMap.getValue(2))
            assertEquals(1101, root.getInt("server_port"))
            val second = all.getValue(root.getString("detour"))
            assertEquals(1102, second.getInt("server_port"))
            assertEquals(1081, all.getValue(second.getString("detour")).getInt("server_port"))
            assertEquals(outbounds(result).size, all.size)
            all.values.filter { it.has("detour") }.forEach { assertTrue(it.getString("detour") in all) }
            writeFixture("imported-detour-selector-$selector.json", result)
        }
    }

    @Test fun importedEndpointDependenciesKeepTheirKindAndStartWithSelectors() {
        val imported = io.nekohasekai.sagernet.group.RawUpdater.parseJSON(JSONObject("""{
          "outbounds":[{"type":"socks","tag":"entry","server":"127.0.0.1","server_port":1101,"detour":"wg"}],
          "endpoints":[{"type":"wireguard","tag":"wg","address":["10.0.0.2/32"],
            "private_key":"eCtXsJZ27+4PbhDkHnB923tkUn2Gj59wZw5wFA75MnU=",
            "peers":[{"address":"127.0.0.1","port":51820,
              "public_key":"Z1XXLsKYkYxuiYjJIkRvtIKFepCYHTgON+GwPq7SOV4=","allowed_ips":["0.0.0.0/0"]}]}]
        }"""))
        val bean = imported.single { it.name == "entry" } as moe.matsuri.nb4a.proxy.config.ConfigBean
        assertEquals(bean.outboundDependencies, bean.clone().outboundDependencies)
        val node = ProxyEntity(id = 2, groupId = 1).apply { putBean(bean) }
        SagerDatabase.proxyDao.insert(listOf(node))
        for (selector in listOf(false, true)) {
            SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(1)!!.copy(isSelector = selector))
            val result = buildConfig(node)
            val json = JSONObject(result.config)
            val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
            assertEquals("wireguard", endpoint.getString("type"))
            val root = outbounds(result).single { it.optInt("server_port") == 1101 }
            assertEquals(endpoint.getString("tag"), root.getString("detour"))
            assertFalse(outbounds(result).any { it.optString("type") == "wireguard" })
            writeFixture("imported-endpoint-selector-$selector.json", result)
        }
    }

    private fun writeFixture(name: String, result: ConfigBuildResult) {
        val directory = File(requireNotNull(System.getProperty("thbox.configFixtures"))).apply { mkdirs() }
        File(directory, name).apply { parentFile!!.mkdirs() }.writeText(result.config)
    }

    @Test fun singleMemberChainsKeepSeparateEntryCounters() {
        val db = SagerDatabase.instance
        db.proxyDao().insert(listOf(chain(2, 1), chain(3, 1)))
        db.groupDao().updateGroup(db.groupDao().getById(1)!!.copy(isSelector = true))
        val result = buildConfig(profile)
        val tags = (1L..3L).map { result.profileTagMap.getValue(it) }
        assertEquals(3, tags.toSet().size)
        assertEquals(setOf(1L), result.trafficMap.getValue(tags[0]).map { it.id }.toSet())
        assertEquals(setOf(1L, 2L), result.trafficMap.getValue(tags[1]).map { it.id }.toSet())
        assertEquals(setOf(1L, 3L), result.trafficMap.getValue(tags[2]).map { it.id }.toSet())
    }


    private fun tailscale(exit: Boolean) = ProxyEntity(id = 2, groupId = 1).apply {
        putBean(io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean().apply {
            initializeDefaultValues(); name = "tail"; controlUrl = "http://127.0.0.1:1"
            if (exit) exitNode = "100.101.102.103"
        })
    }

    @Test fun tailscaleTestsNeverShareStateWithRuntimeOrOtherTests() {
        val tail = tailscale(false)
        SagerDatabase.proxyDao.insert(listOf(tail))
        fun directory(result: ConfigBuildResult) = JSONObject(result.config).getJSONArray("endpoints")
            .getJSONObject(0).getString("state_directory")
        val runtime = buildConfig(tail)
        val first = buildConfig(tail, forTest = true)
        val second = buildConfig(tail, forTest = true)
        assertEquals(directory(runtime), directory(buildConfig(tail)))
        assertEquals(3, setOf(directory(runtime), directory(first), directory(second)).size)
        assertTrue(directory(first).startsWith(first.temporaryStateDirectory!!.absolutePath + "/"))
        val custom = tail.copy(id = 10).putBean(moe.matsuri.nb4a.proxy.config.ConfigBean().apply {
            initializeDefaultValues(); type = 0
            config = """{"endpoints":[{"type":"tailscale","state_directory":"/existing/login"}]}"""
        })
        assertEquals("/existing/login", directory(buildConfig(custom)))
        assertNotEquals("/existing/login", directory(buildConfig(custom, forTest = true)))
    }

    @Test fun tailscaleProfilesAndRepeatedChainInstancesNeverShareState() {
        val first = tailscale(false)
        val second = tailscale(false).copy(id = 3)
        val repeated = chain(4, 1, 2)
        SagerDatabase.proxyDao.insert(listOf(first, second, repeated))
        SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(1)!!.copy(isSelector = true))
        fun directories(result: ConfigBuildResult): List<String> {
            val endpoints = JSONObject(result.config).getJSONArray("endpoints")
            return (0 until endpoints.length()).map { endpoints.getJSONObject(it).getString("state_directory") }
        }
        val config = buildConfig(profile)
        val paths = directories(config)
        assertTrue(paths.size >= 3)
        assertEquals(paths.size, paths.toSet().size)
        assertEquals(paths, directories(buildConfig(profile)))
        assertTrue(paths.all { it.startsWith("tailscale-profiles/") })
        writeFixture("schema/tailscale-multiple-identities.json", config)
        (second.requireBean() as io.nekohasekai.sagernet.fmt.tailscale.TailscaleBean).authKey = "synthetic-new-key"
        SagerDatabase.proxyDao.updateProxy(second)
        assertNotEquals(paths, directories(buildConfig(profile)))
        val settings = io.nekohasekai.sagernet.database.preference.PublicDatabase.instance
        val key = io.nekohasekai.sagernet.database.backup.BackupRestore.GENERATION_KEY
        val old = settings.keyValuePairDao().get(key)
        try {
            settings.keyValuePairDao().put(io.nekohasekai.sagernet.database.preference.KeyValuePair(key).put("restored-fixture"))
            assertTrue(paths.toSet().intersect(directories(buildConfig(profile)).toSet()).isEmpty())
        } finally { if (old == null) settings.keyValuePairDao().delete(key) else settings.keyValuePairDao().put(old) }
    }

    @Test fun tailscaleSelectionAndExitNodeUseTheEndpointWithoutBypassingUserRules() {
        for (exit in listOf(false, true)) for (selector in listOf(false, true)) {
            val node = tailscale(exit)
            SagerDatabase.proxyDao.deleteById(2)
            SagerDatabase.proxyDao.insert(listOf(node))
            SagerDatabase.groupDao.updateGroup(SagerDatabase.groupDao.getById(1)!!.copy(isSelector = selector))
            SagerDatabase.rulesDao.reset()
            SagerDatabase.rulesDao.insert(listOf(RuleEntity(id = 1, enabled = true, ip = "100.101.102.103/32", outbound = -2)))
            // A selector with SOCKS selected must not automatically divert tailnet traffic.
            val result = buildConfig(if (selector) profile else node)
            val json = JSONObject(result.config)
            val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
            val tag = endpoint.getString("tag")
            assertEquals(if (exit) "100.101.102.103" else "", endpoint.optString("exit_node"))
            assertEquals(setOf(2L), result.trafficMap.getValue(tag).map { it.id }.toSet())
            assertFalse(outbounds(result).any { it.optString("tag") == tag })
            val route = json.getJSONObject("route")
            val rules = route.getJSONArray("rules")
            assertFalse((0 until rules.length()).any { rules.getJSONObject(it).has("preferred_by") })
            assertTrue((0 until rules.length()).any { rules.getJSONObject(it).optString("action") == "reject" })
            if (selector) {
                val selection = outbounds(result).single { it.optString("type") == "selector" }
                assertEquals(result.profileTagMap.getValue(1), selection.getString("default"))
                assertTrue(selection.getJSONArray("outbounds").toString().contains(tag))
            } else assertEquals(tag, route.getString("final"))
            writeFixture("schema/tailscale-exit-$exit-selector-$selector.json", result)
        }
    }

    @Test fun tailscaleCanBeEitherHopOfAChainWithoutDirectDetour() {
        SagerDatabase.proxyDao.insert(listOf(tailscale(true)))
        for (tailFirst in listOf(false, true)) {
            SagerDatabase.proxyDao.deleteById(3)
            val entry = if (tailFirst) chain(3, 1, 2) else chain(3, 2, 1)
            SagerDatabase.proxyDao.insert(listOf(entry))
            val result = buildConfig(entry)
            val json = JSONObject(result.config)
            val endpoint = json.getJSONArray("endpoints").getJSONObject(0)
            val socks = outbounds(result).single { it.optString("type") == "socks" }
            if (tailFirst) {
                assertEquals("proxy", endpoint.getString("tag"))
                assertEquals(socks.getString("tag"), endpoint.getString("detour"))
            } else {
                assertEquals("proxy", socks.getString("tag"))
                assertEquals(endpoint.getString("tag"), socks.getString("detour"))
            }
            assertFalse(outbounds(result).any { it.optString("type") == "direct" && it.has("detour") })
            writeFixture("schema/tailscale-chain-$tailFirst.json", result)
        }
    }

}
