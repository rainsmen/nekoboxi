package io.nekohasekai.sagernet.utils

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Disk files stay present during Undo; every list refresh uses the same pending identities. */
class AssetDeletionQueue {
    private val pending = ConcurrentHashMap<File, AssetImporter.Identity>()
    private val metadataCleanup = ConcurrentHashMap<File, AssetImporter.Identity>()
    fun needsMetadataCleanup(file: File) = metadataCleanup.containsKey(file) && !file.exists()
    fun stage(file: File) {
        pending[file] = if (file.exists()) {
            metadataCleanup.remove(file)
            AssetImporter.identity(file)
        } else metadataCleanup[file] ?: AssetImporter.identity(file)
    }
    /** The remaining metadata is the durable retry record across Activity/process recreation. */
    fun restoreMetadataCleanup(directory: File) {
        directory.listFiles().orEmpty().forEach { metadata ->
            val name = metadata.name.removeSuffix(".pending")
            if (!name.endsWith(".version.txt")) return@forEach
            val data = File(directory, name.removeSuffix(".version.txt"))
            if (data.extension.lowercase() in setOf("db", "srs", "json") && !data.exists())
                metadataCleanup.putIfAbsent(data, AssetImporter.Identity(null, java.nio.file.attribute.FileTime.fromMillis(0), 0))
        }
    }
    fun visible(files: List<File>): List<File> =
        (files + metadataCleanup.keys.filter { !it.exists() }).distinct().filter { !pending.containsKey(it) }
    fun undo(files: List<File>) { files.forEach { pending.remove(it) } }
    fun commit(files: List<File>, versionFile: (File) -> File): List<Pair<File, Throwable>> = files.mapNotNull { file ->
        val expected = pending[file] ?: return@mapNotNull null
        val failure = runCatching { AssetImporter.deleteVersioned(file, versionFile(file), expected) }.exceptionOrNull()
        pending.remove(file, expected)
        if (failure is AssetImporter.MetadataCleanupFailed) metadataCleanup[file] = expected
        else metadataCleanup.remove(file)
        failure?.let { file to it }
    }
}
