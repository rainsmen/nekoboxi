package io.nekohasekai.sagernet.database.backup

import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.UUID

/** Save only this private cache token in Android state, never a large credential-bearing Bundle. */
class BackupExportSnapshot(cacheDir: File) {
    private val directory = File(cacheDir, "pending-backups")

    fun stage(content: String): String {
        validate(content)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create backup cache" }
        val token = UUID.randomUUID().toString()
        file(token).writeText(content)
        return token
    }

    fun write(token: String, openOutput: () -> OutputStream) {
        val content = file(token).readText()
        validate(content) // Validate before creating or truncating the destination.
        openOutput().bufferedWriter().use { it.write(content) }
    }

    fun discard(token: String) { file(token).delete() }

    private fun file(token: String): File {
        require(UUID.fromString(token).toString() == token) { "Invalid backup snapshot" }
        return File(directory, "$token.json")
    }

    private fun validate(content: String) {
        val document = JSONObject(content)
        require(document.getInt("version") in 1..2) { "Invalid backup snapshot" }
    }
}
