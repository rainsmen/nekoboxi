package io.nekohasekai.sagernet.fmt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DnsServerParserTest {

    private fun parse(address: String) = DnsServerParser.parse(address, "t")

    @Test
    fun plainHostIsUdp() {
        val s = parse("8.8.8.8")
        assertEquals("udp", s.type)
        assertEquals("8.8.8.8", s.server)
        assertNull(s.server_port)
        assertEquals("t", s.tag)
    }

    @Test
    fun plainHostWithPort() {
        val s = parse("1.1.1.1:5353")
        assertEquals("udp", s.type)
        assertEquals(5353, s.server_port)
    }

    @Test
    fun ipv6() {
        assertEquals("2001:4860:4860::8888", parse("2001:4860:4860::8888").server)
        val s = parse("tls://[2606:4700::1111]:8853")
        assertEquals("tls", s.type)
        assertEquals("2606:4700::1111", s.server)
        assertEquals(8853, s.server_port)
    }

    @Test
    fun https() {
        val s = parse("https://dns.google/dns-query")
        assertEquals("https", s.type)
        assertEquals("dns.google", s.server)
        assertNull(s.server_port)
        assertNull(s.path)

        val custom = parse("https://doh.example:8443/custom")
        assertEquals(8443, custom.server_port)
        assertEquals("/custom", custom.path)
    }

    @Test
    fun otherSchemes() {
        assertEquals("h3", parse("h3://dns.alidns.com/dns-query").type)
        assertEquals("quic", parse("quic://dns.adguard.com").type)
        assertEquals("tcp", parse("tcp://8.8.8.8").type)
        assertNull(parse("tls://1.1.1.1:853").server_port)
    }

    @Test
    fun localFakeipDhcp() {
        assertEquals("local", parse("local").type)
        assertEquals("fakeip", parse("fakeip").type)
        assertNull(parse("dhcp://auto").interface_)
        assertEquals("wlan0", parse("dhcp://wlan0").interface_)
    }

    @Test
    fun remoteDetection() {
        assertTrue(DnsServerParser.isRemote(parse("8.8.8.8")))
        assertFalse(DnsServerParser.isRemote(parse("local")))
        assertEquals("dns.google", DnsServerParser.hostOf("https://dns.google/dns-query"))
        assertNull(DnsServerParser.hostOf("local"))
        assertNull(DnsServerParser.hostOf("rcode://success"))
    }

    @Test(expected = DnsServerParser.InvalidDnsAddressException::class)
    fun rcodeRejected() {
        parse("rcode://refused")
    }

    @Test(expected = DnsServerParser.InvalidDnsAddressException::class)
    fun badPortRejected() {
        parse("8.8.8.8:99999")
    }
}
