package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.Logs
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean

/** The broadcast deadline cannot depend on cancellation of a synchronous database operation. */
internal fun launchShutdownCheckpoint(scope: CoroutineScope, finish: () -> Unit,
    timeoutMillis: Long = 5_000, save: suspend () -> Unit): Job {
    val finished = AtomicBoolean()
    fun finishOnce() { if (finished.compareAndSet(false, true)) finish() }
    val deadline = scope.launch(Dispatchers.Default) {
        delay(timeoutMillis)
        finishOnce()
    }
    val saving = scope.launch(Dispatchers.IO) {
        try { save() }
        catch (error: CancellationException) { throw error }
        catch (error: Exception) { Logs.w(error) }
    }
    // Also runs when the scope was cancelled before the save coroutine began.
    saving.invokeOnCompletion { deadline.cancel(); finishOnce() }
    return saving
}
