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
        assertEquals("new", AssetImporter.readVersion(target, version))
        assertTrue(java.io.File(version.path + ".pending").isFile)
        java.io.File(version, "keep").delete(); version.delete()
        AssetImporter.retryVersionMetadata(target, version)
        assertEquals("new", version.readText())
        assertFalse(java.io.File(version.path + ".pending").exists())
        assertFalse(temporary.root.list()!!.any { it.startsWith(".import-") })
    }
    @Test fun interruptedMetadataDoesNotMislabelCustomDataAsTheLatestOfficialVersion() {
        val target = temporary.newFile("geosite.db").apply { writeText("custom data") }
        val version = temporary.newFile("geosite.version.txt").apply { writeText("latest official") }
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(target.readBytes()).joinToString("") { "%02x".format(it) }
        val pending = java.io.File(version.path + ".pending").apply { writeText("$hash\nCustom") }
        assertEquals("Custom", AssetImporter.readVersion(target, version))
        assertEquals("Custom", version.readText())
        assertFalse(pending.exists())
        pending.writeText("${"0".repeat(64)}\nnew version")
        assertEquals("Custom", AssetImporter.readVersion(target, version))
        assertFalse(pending.exists())
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

    @Test fun staleDownloadCannotOverwriteALaterCustomImport() {
        val file = temporary.newFile("geoip.db").apply { writeText("official old") }
        val version = temporary.newFile("geoip.version.txt").apply { writeText("v1") }
        val expected = AssetImporter.snapshot(file)
        AssetImporter.installVersioned(file, version, "Custom", { it.writeText("my rules") }, {})
        assertThrows(AssetImporter.Changed::class.java) {
            AssetImporter.installVersioned(file, version, "v2", { it.writeText("official new") }, {}, expected)
        }
        assertEquals("my rules", file.readText())
        assertEquals("Custom", AssetImporter.readVersion(file, version))
    }

    @Test fun metadataCleanupFailureKeepsARetryableRowUntilCleanupSucceeds() {
        val file = temporary.newFile("custom.srs").apply { writeText("rules") }
        val version = temporary.newFolder("custom.srs.version.txt")
        val blocker = java.io.File(version, "busy").apply { writeText("busy") }
        val queue = AssetDeletionQueue()
        queue.stage(file)
        assertTrue(queue.commit(listOf(file)) { version }.single().second is AssetImporter.MetadataCleanupFailed)
        assertFalse(file.exists())
        assertEquals(listOf(file), queue.visible(emptyList()))
        assertTrue(queue.needsMetadataCleanup(file))
        val recreated = AssetDeletionQueue().apply { restoreMetadataCleanup(temporary.root) }
        assertEquals(listOf(file), recreated.visible(emptyList()))
        blocker.delete()
        recreated.stage(file)
        assertTrue(recreated.commit(listOf(file)) { version }.isEmpty())
        assertTrue(recreated.visible(emptyList()).isEmpty())
        assertFalse(version.exists())
    }

    @Test fun deletingAReimportedFileUsesItsNewIdentityAfterMetadataFailure() {
        val file = temporary.newFile("custom.srs").apply { writeText("old") }
        val version = temporary.newFolder("custom.srs.version.txt")
        val blocker = java.io.File(version, "busy").apply { writeText("busy") }
        val queue = AssetDeletionQueue()
        queue.stage(file)
        assertEquals(1, queue.commit(listOf(file)) { version }.size)
        blocker.delete(); version.delete()
        AssetImporter.installVersioned(file, version, "Custom", { it.writeText("new content") }, {})
        queue.stage(file)
        assertTrue(queue.commit(listOf(file)) { version }.isEmpty())
        assertFalse(file.exists())
    }

}
