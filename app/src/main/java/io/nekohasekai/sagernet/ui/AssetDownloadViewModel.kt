package io.nekohasekai.sagernet.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.nekohasekai.sagernet.utils.AssetDownloader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

internal class AssetDownloadViewModel : ViewModel() {
    data class Completion(val file: File, val outcome: AssetDownloader.Outcome? = null, val error: Throwable? = null)
    private val active = MutableStateFlow(emptySet<String>())
    val running = active.asStateFlow()
    private val events = Channel<Completion>(Channel.UNLIMITED)
    val completed = events.receiveAsFlow()
    internal var download: suspend (File, File, String) -> AssetDownloader.Outcome = AssetDownloader::update
    private fun key(file: File) = file.absoluteFile.normalize().path
    fun isRunning(file: File) = key(file) in active.value

    /** Invoked on the UI thread; ownership survives Activity recreation and holder reuse. */
    fun start(file: File, versionFile: File, localVersion: String) {
        val key = key(file)
        if (key in active.value) return
        active.value += key
        viewModelScope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) { download(file, versionFile, localVersion) }
                events.send(Completion(file, outcome))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                events.send(Completion(file, error = error))
            } finally {
                active.value -= key
            }
        }
    }
}
