package io.nekohasekai.sagernet.database.backup

import java.io.File
import java.io.OutputStream
import java.util.UUID

/** Freeze the selected group's links before handing control to the system document picker. */
class ProfileExportSnapshot(cacheDir: File) {
    private val directory = File(cacheDir, "pending-profile-exports")

    fun stage(content: String): String {
        require(content.isNotBlank()) { "No profiles to export" }
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create export cache" }
        directory.listFiles()?.filter { it.lastModified() < System.currentTimeMillis() - 86_400_000 }
            ?.forEach { it.delete() }
        val token = UUID.randomUUID().toString()
        file(token).writeText(content)
        return token
    }

    fun write(token: String, openOutput: () -> OutputStream) {
        val content = file(token).readText()
        check(content.isNotBlank()) { "Export snapshot is empty" }
        openOutput().bufferedWriter().use { it.write(content) }
    }

    fun discard(token: String) { file(token).delete() }

    private fun file(token: String): File {
        require(UUID.fromString(token).toString() == token) { "Invalid export snapshot" }
        return File(directory, "$token.txt")
    }
}
