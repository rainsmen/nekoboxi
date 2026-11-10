package io.nekohasekai.sagernet.fmt

import moe.matsuri.nb4a.*
import org.junit.Assert.*
import org.junit.Test

class RouteRuleTest {
    @Test fun singleConditionsAreRetained() {
        val conditions = listOf<SingBoxOptions.Rule_DefaultOptions.() -> Unit>(
            { protocol = listOf("bittorrent") }, { network = listOf("udp") },
            { source_port = listOf(12345) }, { source_port_range = listOf("1000:2000") },
            { makeSingBoxRule(listOf("geoip:private"), true) },
        )
        conditions.forEach { assertFalse(SingBoxOptions.Rule_DefaultOptions().apply(it).checkEmpty()) }
        assertTrue(SingBoxOptions.Rule_DefaultOptions().checkEmpty())
    }
    @Test fun domainAndIpRuleSetsSurviveEitherOrder() {
        for (ip in listOf("1.2.3.0/24", "geoip:cn")) for (ipFirst in listOf(true, false)) {
            val rule = SingBoxOptions.Rule_DefaultOptions()
            if (ipFirst) rule.makeSingBoxRule(listOf(ip), true)
            rule.makeSingBoxRule(listOf("geosite:github"), false)
            if (!ipFirst) rule.makeSingBoxRule(listOf(ip), true)
            assertTrue("$ip / $ipFirst", rule.rule_set.contains("geosite:github"))
            val registered = mutableListOf<SingBoxOptions.RuleSet>()
            generateRuleSet(rule.rule_set, registered)
            assertTrue(registered.any { it.tag == "geosite:github" })
            if (ip.startsWith("geoip:")) assertTrue(registered.any { it.tag == ip })
            else assertEquals(listOf(ip), rule.ip_cidr)
        }
    }
    @Test fun dnsSourceConditionsAreNotEmpty() {
        val conditions = listOf<SingBoxOptions.DNSRule_DefaultOptions.() -> Unit>(
            { source_ip_cidr = listOf("192.168.0.1/32") }, { source_port = listOf(12345) },
            { source_port_range = listOf("1000:2000") }, { network = listOf("udp") },
        )
        conditions.forEach { assertFalse(SingBoxOptions.DNSRule_DefaultOptions().apply(it).checkEmpty()) }
        assertTrue(SingBoxOptions.DNSRule_DefaultOptions().checkEmpty())
    }

    @Test fun regularExpressionEscapesArePreservedInTrafficAndDnsRules() {
        val expression = """^\D+\.example$"""
        val traffic = SingBoxOptions.Rule_DefaultOptions().apply { makeSingBoxRule(listOf("regexp:$expression"), false) }
        val dns = SingBoxOptions.DNSRule_DefaultOptions().apply { makeSingBoxRule(listOf("regexp:$expression")) }
        assertEquals(listOf(expression), traffic.domain_regex)
        assertEquals(listOf(expression), dns.domain_regex)
        assertTrue(Regex(traffic.domain_regex.single()).matches("abc.example"))
        assertFalse(Regex(dns.domain_regex.single()).matches("123.example"))
    }

}
