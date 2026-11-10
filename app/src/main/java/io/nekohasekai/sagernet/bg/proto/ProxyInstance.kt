package io.nekohasekai.sagernet.bg.proto

import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.ServiceNotification
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.appScope
import io.nekohasekai.sagernet.bg.closeResources
import moe.matsuri.nb4a.utils.JavaUtil

class ProxyInstance(profile: ProxyEntity, var service: BaseService.Interface? = null) :
    BoxInstance(profile) {

    var notTmp = true

    var lastSelectorGroupId = -1L
    var displayProfileName = ServiceNotification.genTitle(profile)

    // for TrafficLooper
    var looper: TrafficLooper? = null

    override fun buildConfig() {
        super.buildConfig()
        lastSelectorGroupId = super.config.selectorGroupId
        //
        if (notTmp) Logs.d("Proxy configuration generated")
    }

    // only use this in temporary instance
    fun buildConfigTmp() {
        notTmp = false
        buildConfig()
    }

    override suspend fun init() {
        super.init()
        Logs.d("Prepared ${pluginConfigs.size} external plugins")
    }

    override suspend fun loadConfig() {
        super.loadConfig()
    }

    override fun launch() {
        box.setAsMain()
        looper = service?.let { TrafficLooper(it.data, appScope) }
        looper?.prepare(this) // Install counters before any inbound can accept connections.
        super.launch()
        looper?.start()
    }

    override suspend fun closeAndWait(): Unit = closeResources(
        { super.closeAndWait() },
        { looper?.stop() },
    )
}
