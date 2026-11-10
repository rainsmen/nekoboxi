package io.nekohasekai.sagernet.bg

import android.content.Context
import io.nekohasekai.sagernet.aidl.ISagerNetService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Wait for final statistics writes, not merely for the close broadcast to be sent. */
object ServiceStopper {
    suspend fun <T> withStopped(context: Context, block: suspend (ISagerNetService) -> T): T {
        val connected = CompletableDeferred<ISagerNetService>()
        val connection = SagerConnection(5, listenForDeath = true)
        val requestId = java.util.UUID.randomUUID().toString()
        var lease: ServiceMaintenanceLock.Lease? = null
        try {
            withContext(Dispatchers.Main.immediate) {
                connection.connect(context, object : SagerConnection.Callback {
                    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit
                    override fun onServiceConnected(service: ISagerNetService) { connected.complete(service) }
                    override fun onBinderDied() {
                        connected.completeExceptionally(IllegalStateException("Service disconnected during maintenance"))
                    }
                })
            }
            val service = withTimeout(20_000) { connected.await() }
            withContext(Dispatchers.IO) {
                withTimeout(20_000) {
                    service.stopForMaintenance(requestId)
                    while (true) {
                        val result = service.getMaintenanceResult(requestId)
                        if (result != null) {
                            check(result.isEmpty()) { result }
                            break
                        }
                        delay(50)
                    }
                    val lockFile = java.io.File(context.createDeviceProtectedStorageContext().filesDir, "service-maintenance.lock")
                    while (lease == null) {
                        lease = ServiceMaintenanceLock.tryAcquire(lockFile, shared = false)
                        if (lease == null) delay(50)
                    }
                }
            }
            // A recreated service cannot acquire a running lease while this process owns maintenance.
            return block(service)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                runCatching { connection.service?.finishMaintenance(requestId) }
                lease?.close()
            }
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main.immediate) { connection.disconnect(context) }
        }
    }

    suspend fun clearTraffic(context: Context, groupId: Long): Boolean = withContext(Dispatchers.Main.immediate) {
        val connected = CompletableDeferred<ISagerNetService>()
        val connection = SagerConnection(5, listenForDeath = true)
        try {
            connection.connect(context, object : SagerConnection.Callback {
                override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) = Unit
                override fun onServiceConnected(service: ISagerNetService) { connected.complete(service) }
                override fun onBinderDied() { connected.completeExceptionally(IllegalStateException("Service disconnected")) }
            })
            val service = withTimeout(20_000) { connected.await() }
            withContext(Dispatchers.IO) { service.clearTraffic(groupId) }
        } finally { connection.disconnect(context) }
    }

}
