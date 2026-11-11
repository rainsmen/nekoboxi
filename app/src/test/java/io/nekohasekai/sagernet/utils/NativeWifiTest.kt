package io.nekohasekai.sagernet.utils

import android.content.Context
import android.net.wifi.WifiManager
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.QuietNativeLog
import io.nekohasekai.sagernet.fmt.*
import moe.matsuri.nb4a.NativeInterface
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [31], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
class NativeWifiTest {
    @Test fun deniedWifiPermissionIsUnavailableInsteadOfEscapingTheNativeCallback() {
        val app = RuntimeEnvironment.getApplication()
        val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
        Shadows.shadowOf(wifi).setAccessWifiStatePermission(false)
        val platform = NativeInterface()
        repeat(10) { assertEquals(0, JSONObject(platform.wifiState()).length()) }
        Shadows.shadowOf(wifi).setAccessWifiStatePermission(true)
        assertNotNull(JSONObject(platform.wifiState()))
    }
}
