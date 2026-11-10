package io.nekohasekai.sagernet.bg

import io.nekohasekai.sagernet.ktx.appScope
import android.os.SystemClock
import android.system.OsConstants
import androidx.annotation.MainThread
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.utils.Commandline
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import libcore.Libcore
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.concurrent.thread

class GuardedProcessPool(
    private val processFactory: (List<String>, Map<String, String>) -> Process = { cmd, env ->
        ProcessBuilder(cmd).directory(SagerNet.application.noBackupFilesDir).apply {
            environment().putAll(env)
        }.start()
    },
    private val gracefulStopMillis: Long = 1000,
    private val forcedStopMillis: Long = 5000,
    dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val onFatal: suspend (IOException) -> Unit,
) : CoroutineScope {
    private val guards = linkedSetOf<Guard>()

    private inner class Guard(
        private val cmd: List<String>,
        private val env: Map<String, String> = mapOf()
    ) {
        private lateinit var process: Process

        private fun streamLogger(input: InputStream, logger: (String) -> Unit) = try {
            input.bufferedReader().forEachLine(logger)
        } catch (_: IOException) {
        }    // ignore

        fun start() {
            process = processFactory(cmd, env)
        }

        @DelicateCoroutinesApi
        suspend fun looper(onRestartCallback: (suspend () -> Unit)?) {
            var running = true
            val cmdName = File(cmd.first()).nameWithoutExtension
            val exitChannel = Channel<Int>(Channel.UNLIMITED)
            try {
                while (true) {
                    val monitoredProcess = process
                    thread(name = "stderr-$cmdName", isDaemon = true) {
                        streamLogger(monitoredProcess.errorStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    thread(name = "stdout-$cmdName", isDaemon = true) {
                        streamLogger(monitoredProcess.inputStream) { Libcore.nekoLogPrintln("[$cmdName] $it") }
                    }
                    thread(name = "exit-$cmdName", isDaemon = true) {
                        exitChannel.trySend(monitoredProcess.waitFor())
                    }
                    val startTime = SystemClock.elapsedRealtime()
                    val exitCode = exitChannel.receive()
                    running = false
                    when {
                        SystemClock.elapsedRealtime() - startTime < 1000 -> throw IOException(
                            "$cmdName exits too fast (exit code: $exitCode)"
                        )

                        exitCode == 128 + OsConstants.SIGKILL -> Logs.w("$cmdName was killed")
                        else -> Logs.w(IOException("$cmdName unexpectedly exits with code $exitCode"))
                    }
                    Logs.i("restart process: ${Commandline.toString(cmd)} (last exit code: $exitCode)")
                    start()
                    running = true
                    onRestartCallback?.invoke()
                }
            } catch (e: IOException) {
                Logs.w("error occurred. stop guard: ${Commandline.toString(cmd)}")
                appScope.launch(Dispatchers.Main) { onFatal(e) }
            } finally {
                // Pool shutdown cancels every monitor before disposing its retained handles.
                if (running && !closing) terminate()
            }
        }

        suspend fun terminate() = withContext(NonCancellable + Dispatchers.IO) {
            if (process.isAlive) {
                process.destroy()
                if (!process.waitFor(gracefulStopMillis, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly()
                    if (!process.waitFor(forcedStopMillis, java.util.concurrent.TimeUnit.MILLISECONDS))
                        throw IOException("Plugin ${File(cmd.first()).nameWithoutExtension} did not exit after forced termination")
                }
            }
            // A retry checks this handle again; an earlier timeout is not proof it is still alive.
            check(!process.isAlive) { "Plugin process is still running" }
        }
    }

    @Volatile private var closing = false
    override val coroutineContext = dispatcher + SupervisorJob() + CoroutineExceptionHandler { _, error ->
        Logs.w(error)
        if (!closing) appScope.launch(Dispatchers.Main) { onFatal(IOException("Plugin guard failed", error)) }
    }
    var processCount = 0

    @MainThread
    fun start(
        cmd: List<String>,
        env: MutableMap<String, String> = mutableMapOf(),
        onRestartCallback: (suspend () -> Unit)? = null
    ) {
        check(!closing) { "Process pool is closing" }
        Logs.i("start process: ${Commandline.toString(cmd)}")
        Guard(cmd, env).apply {
            start() // if start fails, IOException will be thrown directly
            guards += this
            launch { looper(onRestartCallback) }
        }
        processCount += 1
    }

    suspend fun closeAndWait() {
        closing = true
        coroutineContext[Job]!!.cancelAndJoin()
        closeResources(*guards.toList().map { guard ->
            suspend { guard.terminate(); guards.remove(guard); Unit }
        }.toTypedArray())
    }
}
