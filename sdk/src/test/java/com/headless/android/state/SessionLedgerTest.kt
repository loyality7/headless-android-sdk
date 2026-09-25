package com.headless.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class SessionLedgerTest {
    private fun freshLedger(): SessionLedger =
        SessionLedger(Files.createTempDirectory("ledger").toFile())

    @Test fun `no entry means nothing to repair`() {
        assertNull(freshLedger().orphanedPackage(setOf(0, 14)))
    }

    @Test fun `live display means adopted not orphaned`() {
        val l = freshLedger()
        l.record("s1", 14, "com.android.chrome")
        assertNull(l.orphanedPackage(setOf(0, 14)))
    }

    @Test fun `dead display reports orphan package`() {
        val l = freshLedger()
        l.record("s1", 14, "com.android.chrome")
        assertEquals("com.android.chrome", l.orphanedPackage(setOf(0)))
    }

    @Test fun `clear removes repair duty`() {
        val l = freshLedger()
        l.record("s1", 14, "com.android.chrome")
        l.clear()
        assertNull(l.orphanedPackage(setOf(0)))
    }

    @Test fun `corrupt file reads as nothing`() {
        val dir = Files.createTempDirectory("ledger-bad").toFile()
        File(dir, "session_ledger.txt").writeText("garbage-no-pipes")
        assertNull(SessionLedger(dir).orphanedPackage(setOf(0)))
    }
}
