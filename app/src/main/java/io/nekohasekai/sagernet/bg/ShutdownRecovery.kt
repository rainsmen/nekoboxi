package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Pending cleanup belongs to the process, so Service recreation cannot discard it. */
internal object ShutdownRecovery {
    private data class Pending(var error: Throwable, val retry: suspend () -> Unit)
    private val pending = linkedMapOf<Any, Pending>()
    private val retries = Mutex()

    @Synchronized fun contains(owner: Any) = pending.containsKey(owner)
    @Synchronized fun hasPending() = pending.isNotEmpty()
    @Synchronized fun failure(): Throwable? = pending.values.firstOrNull()?.error
    @Synchronized fun retain(owner: Any, error: Throwable, retry: suspend () -> Unit) {
        pending[owner] = Pending(error, retry)
    }

    suspend fun retryAll() = retries.withLock {
        val snapshot = synchronized(this) { pending.toList() }
        closeResources(*snapshot.map { (owner, entry) ->
            suspend {
                try {
                    entry.retry()
                    synchronized(this) { if (pending[owner] === entry) pending.remove(owner) }
                } catch (error: Throwable) {
                    synchronized(this) { entry.error = error }
                    throw error
                }
            }
        }.toTypedArray())
    }
}
