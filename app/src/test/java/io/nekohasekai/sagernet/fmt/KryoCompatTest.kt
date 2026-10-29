package io.nekohasekai.sagernet.fmt

import io.nekohasekai.sagernet.fmt.hysteria.HysteriaBean
import org.junit.Assert.assertEquals
import org.junit.Test

/** Profiles are stored as Kryo bytes; a Kryo upgrade must still read them. */
class KryoCompatTest {

    // HysteriaBean serialized with Kryo 5.2.1 (the version before the 5.6 upgrade).
    private val kryo521 = "090000006578616d706c652e636fed3804000002000000000000007365637265f40000000081736e692e6578616d706ce5818181000000000000000000810000000000000000000a0000003434b3008181000000008181818101000000666978747572e58181"

    @Test
    fun readsBytesWrittenByOldKryo() {
        val bytes = kryo521.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val bean = KryoConverters.deserialize(HysteriaBean(), bytes)
        assertEquals("fixture", bean.name)
        assertEquals("example.com", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
        assertEquals("secret", bean.authPayload)
        assertEquals("sni.example", bean.sni)
        assertEquals(2, bean.protocolVersion)
    }

    @Test
    fun roundTrip() {
        val bean = HysteriaBean().apply {
            initializeDefaultValues()
            serverAddress = "h"
            obfsType = "gecko"
            realmId = "r"
        }
        val copy = KryoConverters.deserialize(HysteriaBean(), KryoConverters.serialize(bean))
        assertEquals("gecko", copy.obfsType)
        assertEquals("r", copy.realmId)
    }
}
