package io.nekohasekai.sagernet.utils

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

object AssetImporter {
    data class Identity(val key: String?, val modified: java.nio.file.attribute.FileTime, val size: Long)
    fun identity(file: File): Identity {
        val attrs = Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
        return Identity(attrs.fileKey()?.toString(), attrs.lastModifiedTime(), attrs.size())
    }
    private fun <T> withAssetLock(destination: File, action: () -> T): T =
        java.io.RandomAccessFile(File(destination.parentFile, ".assets.lock"), "rw").use { file ->
            file.channel.lock(0, 1, false).use { action() }
        }

    data class Snapshot(val identity: Identity?)
    class Changed : IOException("Asset changed while the update was downloading")
    class MetadataCleanupFailed(cause: Throwable) : IOException("Data deleted; version metadata cleanup failed", cause)

    @Synchronized
    fun snapshot(file: File): Snapshot = withAssetLock(file) { Snapshot(if (file.exists()) identity(file) else null) }

    @Synchronized
    fun deleteVersioned(file: File, versionFile: File, expected: Identity = identity(file)) = withAssetLock(file) {
        if (file.exists()) {
            if (identity(file) != expected) throw IOException("File changed while deletion was pending: ${file.name}")
            Files.delete(file.toPath())
        }
        try {
            Files.deleteIfExists(versionFile.toPath())
            Files.deleteIfExists(pendingFile(versionFile).toPath())
        } catch (error: IOException) { throw MetadataCleanupFailed(error) }
        Unit
    }

    class Empty : IOException()
    class TooLarge : IOException()
    const val MAX_BYTES = 256L * 1024 * 1024

    /** Source, validation and move failures leave the existing destination intact. */
    fun replace(destination: File, open: () -> InputStream, validate: (File) -> Unit) =
        replaceWritten(destination, { temporary -> copy(open, temporary) }, validate)

    fun copy(open: () -> InputStream, temporary: File) {
        open().use { source ->
            temporary.outputStream().use { target ->
                val buffer = ByteArray(8192)
                var total = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_BYTES) throw TooLarge()
                    target.write(buffer, 0, count)
                }
                if (total == 0L) throw Empty()
            }
        }
    }

    @Synchronized
    fun replaceWritten(destination: File, write: (File) -> Unit, validate: (File) -> Unit) {
        val temporary = File.createTempFile(".import-", ".tmp", destination.parentFile)
        try {
            write(temporary)
            if (temporary.length() == 0L) throw Empty()
            if (temporary.length() > MAX_BYTES) throw TooLarge()
            validate(temporary)
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally { temporary.delete() }
    }

    private fun pendingFile(versionFile: File) = File(versionFile.path + ".pending")
    private fun digest(file: File): String {
        val hash = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            while (true) {
                val size = input.read(buffer)
                if (size < 0) break
                hash.update(buffer, 0, size)
            }
        }
        return hash.digest().joinToString("") { "%02x".format(it) }
    }

    private fun pendingVersion(destination: File, versionFile: File): String? {
        val pending = pendingFile(versionFile)
        if (!pending.exists()) return null
        if (!pending.isFile || pending.length() > 4096) throw IOException("Invalid asset version journal")
        val parts = pending.readText().split('\n', limit = 2)
        if (parts.size != 2 || !parts[0].matches(Regex("[0-9a-f]{64}")) || parts[1].isBlank() || '\n' in parts[1])
            throw IOException("Invalid asset version journal")
        if (!destination.isFile || digest(destination) != parts[0]) {
            Files.delete(pending.toPath()) // Data replacement never committed; keep the old version.
            return null
        }
        return parts[1]
    }

    @Synchronized
    fun retryVersionMetadata(destination: File, versionFile: File) = withAssetLock(destination) {
        retryVersionMetadataLocked(destination, versionFile)
    }

    private fun retryVersionMetadataLocked(destination: File, versionFile: File) {
        val version = pendingVersion(destination, versionFile) ?: return
        replaceWritten(versionFile, { it.writeText(version) }, {})
        Files.deleteIfExists(pendingFile(versionFile).toPath())
    }

    /** A valid journal describes the installed data even while its version marker is unwritable. */
    @Synchronized
    fun readVersion(destination: File, versionFile: File): String? = withAssetLock(destination) {
        val pending = pendingVersion(destination, versionFile)
        if (pending != null) {
            runCatching { retryVersionMetadataLocked(destination, versionFile) }
            return@withAssetLock pending
        }
        versionFile.takeIf { it.isFile }?.readText()?.trim()
    }

    /** A returned error means data committed; the hash-bound journal makes metadata retryable. */
    @Synchronized
    fun installVersioned(destination: File, versionFile: File, version: String,
                         write: (File) -> Unit, validate: (File) -> Unit,
                         expected: Snapshot? = null): Throwable? = withAssetLock(destination) {
        if (expected != null && expected.identity != if (destination.exists()) identity(destination) else null)
            throw Changed()
        require(version.isNotBlank() && version.length <= 2048 && '\n' !in version && '\r' !in version)
        retryVersionMetadataLocked(destination, versionFile)
        val journal = pendingFile(versionFile)
        try {
            replaceWritten(destination, write) { temporary ->
                validate(temporary)
                replaceWritten(journal, { it.writeText(digest(temporary) + "\n" + version) }, {})
            }
        } catch (error: Exception) {
            runCatching { Files.deleteIfExists(journal.toPath()) }
            throw error
        }
        runCatching { retryVersionMetadataLocked(destination, versionFile) }.exceptionOrNull()
    }
}
