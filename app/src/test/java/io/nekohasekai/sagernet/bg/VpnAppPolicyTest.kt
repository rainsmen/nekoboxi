package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.os.ParcelFileDescriptor
import androidx.room.MultiInstanceInvalidationService
import io.nekohasekai.sagernet.*
import io.nekohasekai.sagernet.bg.proto.ProxyInstance
import io.nekohasekai.sagernet.database.*
import io.nekohasekai.sagernet.fmt.*
import io.nekohasekai.sagernet.fmt.socks.SOCKSBean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import java.io.File

/** Capture only Android's TUN boundary; exercise production policy and Builder creation. */
@Implements(android.net.VpnService.Builder::class)
class PolicyBuilderShadow {
    @RealObject lateinit var builder: android.net.VpnService.Builder
    val allowed = mutableListOf<String>()
    val disallowed = mutableListOf<String>()
    // Android's InetAddress.parseNumericAddress is absent from the host JVM.
    @Implementation fun addAddress(address: String, prefix: Int): android.net.VpnService.Builder =
        builder.addAddress(java.net.InetAddress.getByName(address), prefix)
    @Implementation fun addRoute(address: String, prefix: Int): android.net.VpnService.Builder = builder
    @Implementation fun addDnsServer(address: String): android.net.VpnService.Builder =
        builder.addDnsServer(java.net.InetAddress.getByName(address))
    @Implementation fun addAllowedApplication(name: String): android.net.VpnService.Builder {
        check(disallowed.isEmpty()); allowed.add(name); return builder
    }
    @Implementation fun addDisallowedApplication(name: String): android.net.VpnService.Builder {
        check(allowed.isEmpty()); disallowed.add(name); return builder
    }
    @Implementation fun establish(): ParcelFileDescriptor {
        builds.add(this)
        val file = File.createTempFile("tun-fixture", ".tmp", RuntimeEnvironment.getApplication().cacheDir)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE).also { file.delete() }
    }
    companion object { val builds = mutableListOf<PolicyBuilderShadow>() }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class, PolicyBuilderShadow::class])
class VpnAppPolicyTest {
    @Test fun everyEstablishUsesFreshCommittedPolicyAndDisablingClearsOldBrowserBypass() {
        val app = RuntimeEnvironment.getApplication()
        Shadows.shadowOf(app).declareComponentUnbindable(ComponentName(app, MultiInstanceInvalidationService::class.java))
        PolicyBuilderShadow.builds.clear()
        val controller = Robolectric.buildService(VpnService::class.java).create()
        val service = controller.get()
        service.data.proxy = ProxyInstance(ProxyEntity().putBean(SOCKSBean().apply { initializeDefaultValues() })).apply {
            config = ConfigBuildResult("{}", emptyList(), 1, emptyMap(), emptyMap(), -1)
        }
        DataStore.bypassLan = false; DataStore.appendHttpProxy = false
        DataStore.ipv6Mode = IPv6Mode.DISABLE
        fun establish(): PolicyBuilderShadow {
            service.startVpn("{}", "{}")
            service.conn!!.close(); service.conn = null
            return PolicyBuilderShadow.builds.last()
        }
        try {
            PerAppProxySettings.importSelection(true, "fixture.browser")
            val bypass = establish()
            assertEquals(listOf("fixture.browser"), bypass.disallowed)
            assertTrue(bypass.allowed.isEmpty())
            PerAppProxySettings.setMode(false, true)
            val disabled = establish()
            assertNotSame(bypass, disabled)
            assertTrue(disabled.disallowed.isEmpty()); assertTrue(disabled.allowed.isEmpty())
            assertEquals("fixture.browser", DataStore.individual)
            PerAppProxySettings.importSelection(false, "fixture.chat")
            val only = establish()
            assertEquals(setOf("fixture.chat", app.packageName), only.allowed.toSet())
            assertTrue(only.disallowed.isEmpty())
            PerAppProxySettings.importSelection(true, "fixture.other")
            assertEquals(listOf("fixture.other"), establish().disallowed)
            assertEquals(4, PolicyBuilderShadow.builds.size)
        } finally {
            service.conn?.close(); service.conn = null
            service.data.proxy = null; controller.destroy()
        }
    }
}
