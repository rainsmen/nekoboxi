package io.nekohasekai.sagernet.database.backup

import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Keep successful shares readable for the receiving app; remove partial and expired files. */
internal class BackupShareFile(cacheDir: File,
                               private val write: (File, String) -> Unit = { file, content -> file.writeText(content) }) {
    private val directory = File(cacheDir, "backup-shares")
    fun create(content: String): File {
        require(JSONObject(content).getInt("version") in 1..2)
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create backup share cache" }
        val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
        directory.listFiles().orEmpty().filter { it.isFile && it.lastModified() < cutoff }.forEach { it.delete() }
        val file = File(directory, "thbox-backup-${UUID.randomUUID()}.json")
        try {
            write(file, content)
            return file
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }
}
