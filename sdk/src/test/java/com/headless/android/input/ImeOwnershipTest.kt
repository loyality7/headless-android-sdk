package com.headless.android.input

import com.headless.android.DisplayIsolationViolationException
import com.headless.android.privilege.BackendCapability
import com.headless.android.privilege.BackendInfo
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.privilege.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** A keyboard on display 0 is a leak only if it serves a window on another display. */
class ImeOwnershipTest {

    private class FakeBackend(
        var windowDisplays: String = "",
        var inputMethod: String = "",
        var activities: String = ""
    ) : PrivilegeBackend {
        override val name = "Fake"
        override fun isAvailable() = true
        override fun isAuthorized() = true
        override suspend fun requestAuthorization() = true
        override fun isAlive() = true
        override fun capabilities(): Set<BackendCapability> = emptySet()
        override fun info() = BackendInfo("Fake", 2000, null, null)
        override fun getSystemServiceBinder(serviceName: String): android.os.IBinder = throw UnsupportedOperationException()
        override fun close() {}
        override fun shell(command: Array<String>): ShellResult {
            val cmd = command.joinToString(" ")
            val out = when {
                cmd.startsWith("dumpsys window") -> windowDisplays
                cmd.startsWith("dumpsys input_method") -> inputMethod
                cmd.startsWith("dumpsys activity") -> activities
                else -> ""
            }
            return ShellResult(0, out, "")
        }
    }

    // Lines copied from a real `dumpsys input_method` on the test phone (Android 14).
    private fun clientOn(display: Int, uid: Int = 10068) =
        "  mCurClient=ClientState{1bf8d59 mUid=$uid mPid=2646 mSelfReportedDisplayId=$display} mCurSeq=2800\n  mInputShown=true\n"

    private val keyboardVisible = "ImeInsetsSourceProvider\n  mImeShowing=true\n"

    private fun guard(b: PrivilegeBackend) = DisplayIsolationGuard(b, 6, 1080, 1920)

    @Test
    fun `current client is read from a real dump line`() {
        val client = ImeOwnership.currentClient("  mCurClient=ClientState{1bf8d59 mUid=10068 mPid=2646 mSelfReportedDisplayId=0} mCurSeq=2800")
        assertEquals(ImeClient(10068, 0), client)
        assertNull(ImeOwnership.currentClient("nothing useful here"))
    }

    @Test
    fun `owner rules - user's app on display 0 is fine, other displays and unknown are not`() {
        assertFalse(ImeOwnership.isAutomationKeyboard(ImeClient(10645, 0)))
        assertTrue(ImeOwnership.isAutomationKeyboard(ImeClient(10193, 6)))
        assertTrue("unreadable owner must fail safe", ImeOwnership.isAutomationKeyboard(null))
    }

    @Test
    fun `user opening their own keyboard on display 0 is not contamination`() {
        val status = guard(FakeBackend(windowDisplays = keyboardVisible, inputMethod = clientOn(0))).probeDisplayZero()

        assertTrue("keyboard is visible", status.imeShowingOnDisplayZero)
        assertFalse("but it is not a leak", status.isContaminated)
        assertTrue(status.detail, status.detail.contains("user's own app"))
    }

    @Test
    fun `keyboard serving the hidden display's window is contamination`() {
        val status = guard(FakeBackend(windowDisplays = keyboardVisible, inputMethod = clientOn(6, uid = 10193))).probeDisplayZero()

        assertTrue(status.isContaminated)
        assertTrue(status.detail, status.detail.contains("display 6"))
    }

    @Test
    fun `no keyboard is clean and unreadable owner stays strict`() {
        assertFalse(guard(FakeBackend(windowDisplays = "mImeShowing=false")).probeDisplayZero().isContaminated)
        assertTrue(guard(FakeBackend(windowDisplays = keyboardVisible, inputMethod = "")).probeDisplayZero().isContaminated)
    }

    private val resumedOnSix = """
Display #6 (activities from top to bottom):
  * Task{aaa #50 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t50}
    """.trimIndent()

    @Test
    fun `key injection is allowed while the user's own keyboard is open`() {
        // Must not throw: keys carry the hidden display's id, so they cannot reach display 0.
        guard(FakeBackend(windowDisplays = keyboardVisible, inputMethod = clientOn(0), activities = resumedOnSix))
            .validateKeyInjection("pressEnter", "com.android.chrome")
    }

    @Test
    fun `key injection is refused when the keyboard belongs to the automation`() {
        try {
            guard(FakeBackend(windowDisplays = keyboardVisible, inputMethod = clientOn(6, uid = 10193), activities = resumedOnSix))
                .validateKeyInjection("pressEnter", "com.android.chrome")
            fail("expected a refusal")
        } catch (e: DisplayIsolationViolationException) {
            assertTrue(e.reason.contains("active soft keyboard"))
        }
    }
}
