package io.nekohasekai.sagernet.database

import io.nekohasekai.sagernet.database.backup.BackupShareFile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [29])
class BackupShareFileTest {
    @get:Rule val temp = TemporaryFolder()
    @Test fun failedShareWriteRemovesPartialSensitiveContentAndCanRetry() {
        val content = """{"version":2,"profiles":[]}"""
        val broken = BackupShareFile(temp.root) { file, _ -> file.writeText("partial-secret"); throw IOException("fixture full disk") }
        assertThrows(IOException::class.java) { broken.create(content) }
        assertTrue(temp.root.walkTopDown().none { it.isFile })
        val shared = BackupShareFile(temp.root).create(content)
        assertEquals(content, shared.readText())
        val another = BackupShareFile(temp.root).create(content)
        assertTrue(shared.isFile)
        assertNotEquals(shared, another)
    }
}
