package io.nekohasekai.sagernet.ui

import android.os.Looper
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.fmt.QuietSagerNet
import io.nekohasekai.sagernet.fmt.FixtureProcess
import io.nekohasekai.sagernet.bg.QuietNativeLog
import io.nekohasekai.sagernet.utils.AssetDownloader
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import android.view.View

@RunWith(RobolectricTestRunner::class)
@Config(application = SagerNet::class, sdk = [29], shadows = [QuietSagerNet::class, FixtureProcess::class, QuietNativeLog::class])
class AssetDownloadUiTest {
    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        assertTrue("background work did not complete", condition())
    }
    @Test fun olderMissingResultCannotOverwriteInstalledVersionForSameFile() {
        val controller = Robolectric.buildActivity(AssetsActivity::class.java).setup().visible()
        val release = java.util.concurrent.CountDownLatch(1)
        val entered = java.util.concurrent.CountDownLatch(1)
        val completed = java.util.concurrent.CountDownLatch(1)
        try {
            val activity = controller.get()
            val calls = AtomicInteger()
            activity.readAssetVersion = { _, _ ->
                if (calls.incrementAndGet() == 1) {
                    entered.countDown()
                    check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                    completed.countDown()
                    null
                } else "installed-fixture"
            }
            val file = File(activity.getExternalFilesDir(null), "geoip.db")
            val holder = activity.adapter.onCreateViewHolder(activity.layout.recyclerView, 0)
            holder.bind(file)
            val oldJob = requireNotNull(holder.statusJob)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            file.parentFile!!.mkdirs()
            file.writeText("fixture database")
            holder.bind(file)
            await { holder.binding.assetStatus.text.contains("installed-fixture") }
            release.countDown()
            assertTrue(completed.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // Drain the older async result after the new binding was rendered.
            await { oldJob.isCompleted }
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            assertTrue(holder.binding.assetStatus.text.contains("installed-fixture"))
        } finally {
            release.countDown()
            controller.pause().stop().destroy()
        }
    }

    @Test fun refreshRecreationAndHolderReuseKeepOneDownloadPerFile() {
        val controller = Robolectric.buildActivity(AssetsActivity::class.java).setup().visible()
        val release = CompletableDeferred<Unit>()
        val count = AtomicInteger()
        try {
            var activity = controller.get()
            val model = activity.downloads
            model.download = { _, _, _ -> count.incrementAndGet(); release.await(); AssetDownloader.Outcome() }
            val file = File(activity.getExternalFilesDir(null), "geoip.db")
            val other = File(activity.getExternalFilesDir(null), "custom.srs")
            val holder = activity.adapter.onCreateViewHolder(activity.layout.recyclerView, 0)
            holder.bind(file)
            holder.binding.rulesUpdate.performClick()
            await { count.get() == 1 }
            activity.adapter.reloadAssets()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            holder.bind(file)
            assertEquals(View.INVISIBLE, holder.binding.rulesUpdate.visibility)
            holder.binding.rulesUpdate.performClick()
            assertEquals(1, count.get())
            holder.bind(other)
            assertEquals(View.INVISIBLE, holder.binding.subscriptionUpdateProgress.visibility)
            controller.recreate()
            activity = controller.get()
            assertSame(model, activity.downloads)
            assertTrue(activity.downloads.isRunning(file))
            activity.downloads.start(File(file.path), File(file.parentFile, "geoip.version.txt"), "old")
            assertEquals(1, count.get())
            release.complete(Unit)
            await { !activity.downloads.isRunning(file) }
            assertTrue(activity.layout.refreshLayout.isEnabled)
        } finally {
            release.complete(Unit)
            controller.pause().stop().destroy()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
        }
    }
}
