package io.nekohasekai.sagernet.utils

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AssetImporterTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun rejectedImportsRetainTheOriginalAndCleanTemporaryFiles() {
        val target = temporary.newFile("geoip.db").apply { writeText("original") }
        assertThrows(AssetImporter.Empty::class.java) {
            AssetImporter.replace(target, { ByteArrayInputStream(byteArrayOf()) }, { fail("empty file reached validation") })
        }
        assertEquals("original", target.readText())
        assertThrows(IOException::class.java) {
            AssetImporter.replace(target, { ByteArrayInputStream("invalid".toByteArray()) }, { throw IOException("bad format") })
        }
        assertEquals("original", target.readText())
        assertThrows(IOException::class.java) {
            AssetImporter.replace(target, { throw IOException("provider failed") }, {})
        }
        assertEquals("original", target.readText())
        assertEquals(listOf("geoip.db"), temporary.root.list()!!.toList())
        AssetImporter.replace(target, { ByteArrayInputStream("valid".toByteArray()) }, { assertEquals("valid", it.readText()) })
        assertEquals("valid", target.readText())
    }
    @Test fun failedOnlineReplacementNeverAdvancesVersionOrLeavesTemporaryFiles() {
        val target = temporary.newFolder("geoip.db")
        java.io.File(target, "keep").writeText("original")
        val version = temporary.newFile("geoip.version.txt").apply { writeText("old") }
        assertThrows(IOException::class.java) {
            AssetImporter.installVersioned(target, version, "new", { it.writeText("valid") }, {})
        }
        assertEquals("old", version.readText())
        assertEquals("original", java.io.File(target, "keep").readText())
        assertFalse(temporary.root.list()!!.any { it.startsWith(".import-") })
    }
    @Test fun metadataFailureIsReportedAfterSuccessfulInstallation() {
        val target = temporary.newFile("geoip.db").apply { writeText("old") }
        val version = temporary.newFolder("geoip.version.txt")
        java.io.File(version, "keep").writeText("old")
        assertNotNull(AssetImporter.installVersioned(target, version, "new", { it.writeText("valid") }, {}))
        assertEquals("valid", target.readText())
        assertFalse(temporary.root.list()!!.any { it.startsWith(".import-") })
    }
    @Test fun oversizedDownloadedFileCannotReplaceExistingAsset() {
        val target = temporary.newFile("geoip.db").apply { writeText("original") }
        assertThrows(AssetImporter.TooLarge::class.java) {
            AssetImporter.replaceWritten(target, { file ->
                java.io.RandomAccessFile(file, "rw").use { it.setLength(AssetImporter.MAX_BYTES + 1) }
            }, { fail("oversized asset reached validation") })
        }
        assertEquals("original", target.readText())
    }

    @Test fun refreshAndUndoSharePendingDeletionIdentity() {
        val file = temporary.newFile("custom.srs").apply { writeText("rules") }
        val queue = AssetDeletionQueue()
        queue.stage(file)
        assertTrue(queue.visible(listOf(file)).isEmpty())
        queue.undo(listOf(file))
        assertEquals(listOf(file), queue.visible(listOf(file)))
        queue.stage(file)
        assertTrue(queue.commit(listOf(file)) { java.io.File(temporary.root, "version") }.isEmpty())
        assertFalse(file.exists())
    }

    @Test fun failedDeletionKeepsVersionAndRestoresVisibility() {
        val file = temporary.newFolder("custom.srs")
        java.io.File(file, "keep").writeText("busy")
        val version = temporary.newFile("custom.version").apply { writeText("v1") }
        val queue = AssetDeletionQueue()
        queue.stage(file)
        assertEquals(1, queue.commit(listOf(file)) { version }.size)
        assertEquals("v1", version.readText())
        assertEquals(listOf(file), queue.visible(listOf(file)))
    }

    @Test fun pendingDeletionCannotRemoveNewlyImportedReplacement() {
        val file = temporary.newFile("custom.srs").apply { writeText("old") }
        val version = temporary.newFile("custom.version").apply { writeText("v1") }
        val queue = AssetDeletionQueue()
        queue.stage(file)
        AssetImporter.installVersioned(file, version, "v2", { it.writeText("new rules") }, {})
        assertEquals(1, queue.commit(listOf(file)) { version }.size)
        assertEquals("new rules", file.readText())
        assertEquals("v2", version.readText())
    }

}
