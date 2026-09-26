package com.headless.android.state

import android.os.IBinder
import com.headless.android.privilege.BackendCapability
import com.headless.android.privilege.BackendInfo
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.privilege.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayJanitorTest {

    private class FakePrivilegeBackend(
        var displayOutput: String = "mDisplayId=0",
        var inputMethodOutput: String = "mSelfReportedDisplayId=0",
        var inputShown: Boolean = false
    ) : PrivilegeBackend {
        val executedCommands = mutableListOf<List<String>>()

        override val name: String = "fake"
        override fun isAvailable(): Boolean = true
        override fun isAuthorized(): Boolean = true
        override fun isAlive(): Boolean = true
        override fun capabilities(): Set<BackendCapability> = setOf(BackendCapability.SHELL)
        override fun info(): BackendInfo = BackendInfo(name, 2000, null, null)
        override suspend fun requestAuthorization(): Boolean = true
        override fun getSystemServiceBinder(serviceName: String): IBinder = error("Not supported in test")
        override fun close() {}

        override fun shell(command: Array<String>): ShellResult {
            val cmdList = command.toList()
            executedCommands.add(cmdList)
            return when {
                cmdList == listOf("dumpsys", "display") -> {
                    ShellResult(0, displayOutput, "")
                }
                cmdList == listOf("dumpsys", "input_method") -> {
                    val out = buildString {
                        append(inputMethodOutput)
                        if (inputShown) append("\n  mInputShown=true\n")
                    }
                    ShellResult(0, out, "")
                }
                cmdList.take(4) == listOf("input", "-d", "0", "keyevent") -> {
                    ShellResult(0, "", "")
                }
                else -> ShellResult(0, "", "")
            }
        }
    }

    @Test
    fun `inspect returns NORMAL when only display 0 is live and registered`() {
        val backend = FakePrivilegeBackend(
            displayOutput = "DisplayDeviceInfo{\"Built-in Screen\"}\n  mDisplayId=0\n  mDisplayId=0",
            inputMethodOutput = "Client ClientState{... mSelfReportedDisplayId=0}\n  mCurClient=ClientState{... mSelfReportedDisplayId=0}"
        )
        val janitor = DisplayJanitor(backend)
        val report = janitor.inspect()

        assertEquals(setOf(0), report.liveDisplayIds)
        assertEquals(emptySet<Int>(), report.nonDefaultDisplayIds)
        assertEquals(setOf(0), report.imeClientDisplayIds)
        assertEquals(emptySet<Int>(), report.staleImeDisplayIds)
        assertFalse(report.hasLeakedDisplays)
        assertFalse(report.hasStaleImeState)
        assertEquals(DisplayJanitor.ConsumerAdvice.NORMAL, report.advice)
    }

    @Test
    fun `inspect detects leaked non-default displays`() {
        val backend = FakePrivilegeBackend(
            displayOutput = "  mDisplayId=0\n  mDisplayId=12\n  mDisplayId=13",
            inputMethodOutput = "  mSelfReportedDisplayId=0"
        )
        val janitor = DisplayJanitor(backend)
        val report = janitor.inspect()

        assertEquals(setOf(0, 12, 13), report.liveDisplayIds)
        assertEquals(setOf(12, 13), report.nonDefaultDisplayIds)
        assertTrue(report.hasLeakedDisplays)
        assertEquals(DisplayJanitor.ConsumerAdvice.WARN_LEAKED_DISPLAYS, report.advice)
    }

    @Test
    fun `inspect detects stale IME records from dead displays below threshold`() {
        val backend = FakePrivilegeBackend(
            displayOutput = "  mDisplayId=0",
            inputMethodOutput = "  mSelfReportedDisplayId=0\n  mSelfReportedDisplayId=14\n  mSelfReportedDisplayId=15"
        )
        val janitor = DisplayJanitor(backend)
        val report = janitor.inspect()

        assertEquals(setOf(0), report.liveDisplayIds)
        assertEquals(setOf(0, 14, 15), report.imeClientDisplayIds)
        assertEquals(setOf(14, 15), report.staleImeDisplayIds)
        assertTrue(report.hasStaleImeState)
        assertEquals(DisplayJanitor.ConsumerAdvice.WARN_STALE_IME_SAFE_TO_PROCEED, report.advice)
    }

    @Test
    fun `inspect advises reboot when stale IME records exceed threshold`() {
        val staleEntries = (1..60).joinToString("\n") { "  mSelfReportedDisplayId=$it" }
        val backend = FakePrivilegeBackend(
            displayOutput = "  mDisplayId=0",
            inputMethodOutput = "  mSelfReportedDisplayId=0\n$staleEntries"
        )
        val janitor = DisplayJanitor(backend)
        val report = janitor.inspect()

        assertEquals(60, report.staleImeDisplayIds.size)
        assertEquals(DisplayJanitor.ConsumerAdvice.ADVISE_REBOOT, report.advice)
    }

    @Test
    fun `cleanUp dismisses soft keyboard on display 0 when shown`() {
        val backend = FakePrivilegeBackend(
            displayOutput = "  mDisplayId=0",
            inputMethodOutput = "  mSelfReportedDisplayId=0\n  mSelfReportedDisplayId=12",
            inputShown = true
        )
        val janitor = DisplayJanitor(backend)
        val report = janitor.cleanUp()

        assertTrue(report.hasStaleImeState)
        val dismissCalls = backend.executedCommands.filter {
            it == listOf("input", "-d", "0", "keyevent", "111")
        }
        assertEquals(1, dismissCalls.size)
    }
}
