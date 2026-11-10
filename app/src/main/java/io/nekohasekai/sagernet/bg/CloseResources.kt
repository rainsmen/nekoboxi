package io.nekohasekai.sagernet.bg

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Complete every cleanup, await each resource, and preserve all failures for the stop handshake. */
internal suspend fun closeResources(vararg actions: suspend () -> Unit): Unit = withContext(NonCancellable) {
    var failure: Throwable? = null
    for (action in actions) {
        try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
    }
    failure?.let { throw it }
}
