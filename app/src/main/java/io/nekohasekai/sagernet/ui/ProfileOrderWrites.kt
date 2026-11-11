package io.nekohasekai.sagernet.ui

import io.nekohasekai.sagernet.ktx.appScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Preserve submission order across adapters and page recreation. Callers capture values first. */
internal class ProfileOrderWrites(private val scope: CoroutineScope) {
    private var tail: Job? = null

    @Synchronized fun submit(write: suspend () -> Unit): Job {
        val previous = tail
        return scope.launch(Dispatchers.IO) {
            previous?.join()
            write()
        }.also { tail = it }
    }

    companion object {
        val shared = ProfileOrderWrites(appScope)
    }
}
