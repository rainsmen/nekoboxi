package io.nekohasekai.sagernet.utils

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Disk files stay present during Undo; every list refresh uses the same pending identities. */
class AssetDeletionQueue {
    private val pending = ConcurrentHashMap<File, AssetImporter.Identity>()
    fun stage(file: File) { pending[file] = AssetImporter.identity(file) }
    fun visible(files: List<File>) = files.filter { !pending.containsKey(it) }
    fun undo(files: List<File>) { files.forEach { pending.remove(it) } }
    fun commit(files: List<File>, versionFile: (File) -> File): List<Pair<File, Throwable>> = files.mapNotNull { file ->
        val expected = pending[file] ?: return@mapNotNull null
        val failure = runCatching { AssetImporter.deleteVersioned(file, versionFile(file), expected) }.exceptionOrNull()
        pending.remove(file, expected)
        failure?.let { file to it }
    }
}
