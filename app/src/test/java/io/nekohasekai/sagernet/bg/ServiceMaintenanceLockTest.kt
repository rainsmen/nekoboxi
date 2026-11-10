package io.nekohasekai.sagernet.bg

import java.io.File
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServiceMaintenanceLockTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun probe(file: File, shared: Boolean): String {
        val classpath = File(FileLockProcessProbe::class.java.protectionDomain.codeSource.location.toURI()).path
        val process = ProcessBuilder(File(System.getProperty("java.home"), "bin/java").path, "-cp", classpath,
            FileLockProcessProbe::class.java.name, file.path, shared.toString()).redirectErrorStream(true).start()
        check(process.waitFor(10, TimeUnit.SECONDS)) { "Lock probe timed out" }
        check(process.exitValue() == 0) { process.inputStream.bufferedReader().readText() }
        return process.inputStream.bufferedReader().readText().trim()
    }
    @Test fun runningAndMaintenanceLeasesExcludeOtherProcesses() {
        val file = temporary.newFile()
        val first = ServiceMaintenanceLock.tryAcquire(file, true)!!
        val second = ServiceMaintenanceLock.tryAcquire(file, true)!!
        assertEquals("acquired", probe(file, true))
        assertEquals("blocked", probe(file, false))
        first.close()
        assertNull(ServiceMaintenanceLock.tryAcquire(file, false))
        second.close()
        ServiceMaintenanceLock.tryAcquire(file, false)!!.use {
            // A newly created service process is blocked throughout database restoration.
            assertEquals("blocked", probe(file, true))
            assertEquals("blocked", probe(file, false))
            assertNull(ServiceMaintenanceLock.tryAcquire(file, true))
        }
        assertEquals("acquired", probe(file, true))
    }
}
