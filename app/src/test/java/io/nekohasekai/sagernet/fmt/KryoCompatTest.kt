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
        val bean = KryoConverters.withStrictDecoding { KryoConverters.deserialize(HysteriaBean(), bytes) }
        assertEquals("fixture", bean.name)
        assertEquals("example.com", bean.serverAddress)
        assertEquals("443", bean.serverPorts)
        assertEquals("secret", bean.authPayload)
        assertEquals("sni.example", bean.sni)
        assertEquals(2, bean.protocolVersion)
    }

    @Test fun subscriptionV1StillDecodesStrictly() {
        val output = com.esotericsoftware.kryo.io.ByteBufferOutput(256, -1)
        output.writeInt(1); output.writeInt(0); output.writeString("https://old.example")
        output.writeBoolean(false); output.writeBoolean(true); output.writeBoolean(false)
        output.writeString("agent"); output.writeBoolean(true); output.writeInt(60)
        output.writeInt(123); output.writeString("usage")
        val copy = KryoConverters.withStrictDecoding {
            KryoConverters.deserialize(io.nekohasekai.sagernet.database.SubscriptionBean(), output.toBytes())
        }
        output.close()
        assertEquals(123, copy.lastUpdated)
        assertEquals(0L, copy.lastAttempted)
        assertEquals("", copy.lastError)
        assertEquals("usage", copy.subscriptionUserinfo)
        copy.lastAttempted = 456L; copy.lastError = "failed"
        val current = KryoConverters.withStrictDecoding {
            KryoConverters.deserialize(io.nekohasekai.sagernet.database.SubscriptionBean(), KryoConverters.serialize(copy))
        }
        assertEquals(456L, current.lastAttempted)
        assertEquals("failed", current.lastError)
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
