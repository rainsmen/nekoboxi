package io.nekohasekai.sagernet.ui

import org.junit.Assert.*
import org.junit.Test

class AppSelectionBackupTest {
    @Test fun invalidClipboardIsRejectedBeforeAnySelectionCanBeApplied() {
        for (text in listOf("hello", "true", "hello\ncom.example", "false\nhello world", "false\ncom.example\nhttps://example.org")) {
            assertThrows(IllegalArgumentException::class.java) { AppSelectionBackup.parse(text) }
        }
    }
    @Test fun exportedListsAndExplicitEmptySelectionRemainSupported() {
        assertEquals(AppSelectionBackup(true, "com.example\nandroid"), AppSelectionBackup.parse("true\ncom.example\nandroid"))
        assertEquals(AppSelectionBackup(false, ""), AppSelectionBackup.parse("false\n"))
        assertEquals(AppSelectionBackup(false, "com.example"), AppSelectionBackup.parse("false\r\ncom.example\r\n"))
    }
}
