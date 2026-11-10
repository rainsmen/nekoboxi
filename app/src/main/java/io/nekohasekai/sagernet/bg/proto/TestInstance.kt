package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.GuardedProcessPool
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.fmt.buildConfig
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.tryResume
import io.nekohasekai.sagernet.ktx.tryResumeWithException
import kotlinx.coroutines.delay
import libcore.Libcore
import moe.matsuri.nb4a.net.LocalResolverImpl
import kotlin.coroutines.suspendCoroutine

class TestInstance(profile: ProxyEntity, val link: String, private val timeout: Int) :
    BoxInstance(profile) {

    private var temporaryStateDirectory: java.io.File? = null
    private var isolatedTailscale = false

    suspend fun doTest(): Int = kotlinx.coroutines.coroutineScope {
        val owner = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
        processes = GuardedProcessPool { cause ->
            owner.cancel(kotlinx.coroutines.CancellationException("Test plugin failed", cause))
        }
        try {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { init() }
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { launch() }
            if (processes.processCount > 0) delay(500)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Libcore.urlTest(box, link, timeout) }
        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
        catch (error: Exception) {
            if (!isolatedTailscale) throw error
            throw IllegalStateException(io.nekohasekai.sagernet.ktx.app.getString(
                io.nekohasekai.sagernet.R.string.tailscale_test_isolated) + "\n" +
                error.readableMessage, error)
        } finally {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) { closeAndWait() }
        }
    }

    override suspend fun closeAndWait() {
        super.closeAndWait()
        // Preserve state if closing fails: an unfinished core may still own its files.
        temporaryStateDirectory?.deleteRecursively()
    }

    override fun buildConfig() {
        config = buildConfig(profile, true)
        temporaryStateDirectory = config.temporaryStateDirectory
        val endpoints = org.json.JSONObject(config.config).optJSONArray("endpoints")
        isolatedTailscale = endpoints != null && (0 until endpoints.length()).any {
            endpoints.getJSONObject(it).optString("type") == "tailscale"
        }
    }

    override suspend fun loadConfig() {
        // don't call destroyAllJsi here
        box = Libcore.newSingBoxInstance(config.config, LocalResolverImpl)
    }

}
