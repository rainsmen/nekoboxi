package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.database.backup.ProfileExportSnapshot
import java.io.ByteArrayOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProfileExportSnapshotTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun aRecreatedExporterWritesTheOriginalGroupSnapshot() {
        val content = "socks5://example.org:1080#original\n".repeat(10_000)
        val token = ProfileExportSnapshot(temporary.root).stage(content)
        val restored = ProfileExportSnapshot(temporary.root)
        val output = ByteArrayOutputStream()
        restored.write(token) { output }
        assertEquals(content, output.toString("UTF-8"))
        restored.discard(token)
        var opened = false
        assertThrows(java.io.FileNotFoundException::class.java) {
            restored.write(token) { opened = true; ByteArrayOutputStream() }
        }
        assertFalse("An expired snapshot must never truncate the target", opened)
    }

    @Test fun emptyOrInvalidSnapshotsCannotOpenTheDestination() {
        val exporter = ProfileExportSnapshot(temporary.root)
        assertThrows(IllegalArgumentException::class.java) { exporter.stage("") }
        var opened = false
        assertThrows(IllegalArgumentException::class.java) {
            exporter.write("../../outside") { opened = true; ByteArrayOutputStream() }
        }
        assertFalse(opened)
    }
}
