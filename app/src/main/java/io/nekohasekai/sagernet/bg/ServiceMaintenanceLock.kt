package io.nekohasekai.sagernet.bg

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/** OS-owned leases survive service recreation and are released automatically on process death. */
object ServiceMaintenanceLock {
    private data class Entry(val channel: FileChannel, val lock: FileLock, val shared: Boolean, var users: Int = 1)
    private val held = mutableMapOf<String, Entry>()

    class Lease internal constructor(private val key: String) : Closeable {
        private var closed = false
        override fun close() = synchronized(ServiceMaintenanceLock) {
            if (!closed) {
                closed = true
                val entry = held.getValue(key)
                if (--entry.users == 0) {
                    held.remove(key)
                    try { entry.lock.release() } finally { entry.channel.close() }
                }
            }
        }
    }

    @Synchronized
    fun tryAcquire(file: File, shared: Boolean): Lease? {
        val key = file.canonicalPath
        held[key]?.let {
            if (!shared || !it.shared) return null
            it.users++
            return Lease(key)
        }
        file.parentFile?.mkdirs()
        val channel = RandomAccessFile(file, "rw").channel
        try {
            val lock = try { channel.tryLock(0L, Long.MAX_VALUE, shared) }
                catch (_: OverlappingFileLockException) { null }
            if (lock == null) { channel.close(); return null }
            held[key] = Entry(channel, lock, shared)
            return Lease(key)
        } catch (error: Throwable) {
            channel.close()
            throw error
        }
    }
}
