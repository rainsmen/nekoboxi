package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.app
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProfileMutations
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.group.RawUpdater
import io.nekohasekai.sagernet.ktx.SubscriptionFoundException
import io.nekohasekai.sagernet.ktx.readableMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One scanned batch and its result survive Activity recreation. */
internal class ScanImportViewModel : ViewModel() {
    data class State(val running: Boolean = false, val count: Int = 0,
                     val subscription: String? = null, val error: String? = null, val complete: Boolean = false)
    private val mutable = MutableStateFlow(State())
    val state = mutable.asStateFlow()
    internal var parse: suspend (String) -> List<AbstractBean>? = { RawUpdater.parseRaw(it) }
    internal var selectTarget: (Long, String) -> Unit = ProfileMutations::selectImportGroup
    internal var commit: suspend (Long, List<AbstractBean>, String) -> Unit = { group, beans, generation ->
        ProfileManager.createProfiles(group, beans, generation)
    }

    fun start(inputs: List<suspend () -> String>) {
        if (mutable.value.running || mutable.value.complete || inputs.isEmpty()) return
        mutable.value = State(running = true)
        viewModelScope.launch {
            mutable.value = withContext(Dispatchers.IO) {
                try {
                    val generation = ProfileMutations.currentGeneration()
                    val target = runCatching { DataStore.selectedGroupForImport() }
                    val beans = mutableListOf<AbstractBean>()
                    var subscription: String? = null
                    for (input in inputs) {
                        try {
                            val parsed = parse(input())
                            require(!parsed.isNullOrEmpty()) { app.getString(R.string.no_proxies_found) }
                            beans += parsed
                        } catch (found: SubscriptionFoundException) {
                            require(inputs.size == 1) { app.getString(R.string.scan_subscription_single) }
                            subscription = found.link
                        }
                    }
                    if (subscription != null) State(subscription = subscription, complete = true)
                    else {
                        commit(target.getOrThrow(), beans, generation)
                        // Selection is only navigation; a later restore must not be overwritten.
                        runCatching { selectTarget(target.getOrThrow(), generation) }
                        State(count = beans.size, complete = true)
                    }
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) { State(error = error.readableMessage, complete = true) }
            }
        }
    }

    fun clearResult() { if (!mutable.value.running) mutable.value = State() }
}
