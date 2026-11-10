package io.nekohasekai.sagernet.fmt

import android.app.Application
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import moe.matsuri.nb4a.utils.Util
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.util.zip.DeflaterOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29], shadows = [io.nekohasekai.sagernet.bg.QuietNativeLog::class])
class UniversalLimitsTest {
    @Test fun declaredStringAndArrayLengthsFailBeforeAllocation() {
        // Tiny payload claims a string with 2^31-2 UTF-16 code units.
        val malicious = byteArrayOf(0, 0, 0, 0, -1, -1, -1, -1, 15)
        for (link in listOf("sn://socks:" + Util.b64EncodeUrlSafe(malicious),
            "sn://socks?" + Util.b64EncodeUrlSafe(Util.zlibCompress(malicious, 9)))) {
            assertThrows(com.esotericsoftware.kryo.KryoException::class.java) { parseUniversal(link) }
        }
        for (builder in listOf(false, true)) {
            BoundedKryoInput(malicious.copyOfRange(4, 9), BoundedKryoInput.Budget()).use {
                assertThrows(com.esotericsoftware.kryo.KryoException::class.java) {
                    if (builder) it.readStringBuilder() else it.readString()
                }
            }
        }
        BoundedKryoInput(byteArrayOf(1), BoundedKryoInput.Budget()).use {
            assertThrows(com.esotericsoftware.kryo.KryoException::class.java) { it.readBytes(Int.MAX_VALUE) }
        }
        BoundedKryoInput(byteArrayOf(-1, -1, -1, -1), BoundedKryoInput.Budget()).use {
            assertThrows(com.esotericsoftware.kryo.KryoException::class.java) { it.readCollectionCount(8) }
        }
        val output = ByteArrayOutputStream()
        com.esotericsoftware.kryo.io.ByteBufferOutput(output).use {
            it.writeString("中文😀"); it.writeString("ascii"); it.writeString(""); it.writeString(null)
        }
        BoundedKryoInput(output.toByteArray(), BoundedKryoInput.Budget()).use {
            assertEquals("中文😀", it.readString()); assertEquals("ascii", it.readStringBuilder().toString())
            assertEquals("", it.readString()); assertNull(it.readString())
        }
    }

    @Test fun sharedBudgetIsEnforcedAndReleasedAfterFailure() {
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "a".repeat(1024 * 1024) }
        val bytes = KryoConverters.serialize(bean)
        assertThrows(BoundedKryoInput.LimitException::class.java) {
            KryoConverters.withStrictDecoding {
                repeat(10) { KryoConverters.deserialize(SOCKSBean(), bytes) }
            }
        }
        assertEquals(bean.serverAddress, KryoConverters.withStrictDecoding { KryoConverters.deserialize(SOCKSBean(), bytes) }.serverAddress)
        val normal = KryoConverters.serialize(SOCKSBean().apply { initializeDefaultValues(); serverAddress = "legacy" })
        val truncated = normal.copyOf(normal.size - 1)
        assertEquals("legacy", KryoConverters.deserialize(SOCKSBean(), truncated).serverAddress)
        assertThrows(com.esotericsoftware.kryo.KryoException::class.java) {
            KryoConverters.withStrictDecoding { KryoConverters.deserialize(SOCKSBean(), truncated) }
        }
    }

    @Test fun boundedDecompressionRejectsExpansionTruncationAndTrailingBytes() {
        val compressed = ByteArrayOutputStream().also { output ->
            DeflaterOutputStream(output).use { it.write(ByteArray(Util.MAX_NODE_BYTES + 1)) }
        }.toByteArray()
        assertTrue(compressed.size < 20_000)
        assertThrows(IllegalArgumentException::class.java) { Util.zlibDecompress(compressed) }
        val valid = Util.zlibCompress("small fixture".toByteArray(), 9)
        assertThrows(IllegalArgumentException::class.java) { Util.zlibDecompress(valid.copyOf(valid.size - 1)) }
        assertThrows(IllegalArgumentException::class.java) { Util.zlibDecompress(valid + byteArrayOf(1)) }
        assertThrows(IllegalArgumentException::class.java) { Util.decodeNodePayload("A".repeat(Util.MAX_NODE_BYTES * 2 + 1)) }
    }

    @Test fun tinyPayloadsAndUniversalNodesRoundTripWithoutAcceptingTruncatedBeans() {
        for (input in listOf(byteArrayOf(), byteArrayOf(1), ByteArray(1024) { (it * 31).toByte() }))
            assertArrayEquals(input, Util.zlibDecompress(Util.zlibCompress(input, 9)))
        val bean = SOCKSBean().apply { initializeDefaultValues(); serverAddress = "example.org"; username = "u" }
        assertEquals(bean.serverAddress, parseUniversal(bean.toUniversalLink()).serverAddress)
        val bytes = KryoConverters.serialize(bean)
        val broken = "sn://socks?" + Util.b64EncodeUrlSafe(Util.zlibCompress(bytes.copyOf(bytes.size / 2), 9))
        assertThrows(Exception::class.java) { parseUniversal(broken) }
    }
}
