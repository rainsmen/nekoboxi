package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.utils.DefaultNetworkListener
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implements
import org.robolectric.annotation.Implementation
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@Implements(libcore.BoxInstance::class, isInAndroidSdk = false)
class BlockingResetCore {
    @Implementation fun __constructor__() = Unit
    @Implementation fun resetConnections() {
        entered.countDown()
        check(release.await(10, TimeUnit.SECONDS))
    }
    companion object {
        var entered = CountDownLatch(1)
        var release = CountDownLatch(1)
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, BlockingResetCore::class])
class AutomaticResetTest {
    @org.junit.Before fun excludeRemoteRoomService() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app).declareComponentUnbindable(
            android.content.ComponentName(app, androidx.room.MultiInstanceInvalidationService::class.java))
    }
    @Test fun nativeResetCannotBlockTheActorRequiredByNativeClose() = runBlocking {
        BlockingResetCore.entered = CountDownLatch(1)
        BlockingResetCore.release = CountDownLatch(1)
        val controller = Robolectric.buildService(ProxyService::class.java).create()
        val service = controller.get()
        service.data.state = BaseService.State.Connected
        service.data.proxy = io.nekohasekai.sagernet.bg.proto.ProxyInstance(
            io.nekohasekai.sagernet.database.ProxyEntity().putBean(
                io.nekohasekai.sagernet.fmt.socks.SOCKSBean().apply { initializeDefaultValues() }), service).apply {
            box = libcore.BoxInstance()
            config = io.nekohasekai.sagernet.fmt.ConfigBuildResult("{}", emptyList(), 0, emptyMap(), emptyMap(), 0)
        }
        val key = Any()
        try {
            // The production helper is invoked from the real actor callback. The mocked
            // native reset holds a lifecycle lock until after its stop handshake completes.
            withTimeout(3000) {
                DefaultNetworkListener.start(key) { service.requestAutomaticConnectionReset() }
                assertTrue(BlockingResetCore.entered.await(3, TimeUnit.SECONDS))
                DefaultNetworkListener.stop(key)
            }
        } finally {
            BlockingResetCore.release.countDown()
            service.data.state = BaseService.State.Stopped
            controller.destroy()
        }
    }
}
