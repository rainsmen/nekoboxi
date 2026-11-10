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
    @Synchronized
    fun deleteVersioned(file: File, versionFile: File, expected: Identity = identity(file)) {
        if (file.exists()) {
            if (identity(file) != expected) throw IOException("File changed while deletion was pending: ${file.name}")
            Files.delete(file.toPath())
        }
        // Do not lose the version marker when deleting the actual asset failed.
        Files.deleteIfExists(versionFile.toPath())
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

    /** A returned error means the asset was installed, but its version marker was not. */
    @Synchronized
    fun installVersioned(destination: File, versionFile: File, version: String,
                         write: (File) -> Unit, validate: (File) -> Unit): Throwable? {
        replaceWritten(destination, write, validate)
        return runCatching {
            replaceWritten(versionFile, { it.writeText(version) }, {})
        }.exceptionOrNull()
    }
}
