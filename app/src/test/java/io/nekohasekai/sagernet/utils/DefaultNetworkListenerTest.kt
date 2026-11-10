package io.nekohasekai.sagernet.utils

import android.net.Network
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation

@Implements(Logs::class, isInAndroidSdk = false)
class QuietNetworkLogs {
    @Implementation fun w(error: Throwable) = Unit
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNetworkLogs::class])
class DefaultNetworkListenerTest {
    @Test fun aThrowingListenerDoesNotPreventDeliveryOrLaterRegistration() = runBlocking {
        val broken = Any()
        val healthy = Any()
        var fail = false
        val received = mutableListOf<Network?>()
        DefaultNetworkListener.start(broken) { if (fail) error("listener failed") }
        DefaultNetworkListener.start(healthy) { received.add(it) }
        try {
            val callback = shadowOf(SagerNet.connectivity).networkCallbacks.single()
            fail = true
            val network = org.robolectric.shadows.ShadowNetwork.newInstance(123)
            callback.onAvailable(network)
            callback.onLost(network)
            assertEquals(network, received[received.size - 2])
            assertNull(received.last())
        } finally {
            DefaultNetworkListener.stop(broken)
            DefaultNetworkListener.stop(healthy)
        }
        val later = Any()
        var invoked = false
        DefaultNetworkListener.start(later) { invoked = true }
        DefaultNetworkListener.stop(later)
        assertTrue(invoked)
    }
}
