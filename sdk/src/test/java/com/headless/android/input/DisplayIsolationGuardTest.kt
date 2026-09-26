package com.headless.android.input

import com.headless.android.DisplayIsolationViolationException
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.privilege.ShellResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class DisplayIsolationGuardTest {

    private class FakePrivilegeBackend(
        var activityActivitiesStdout: String = "",
        var windowDisplaysStdout: String = ""
    ) : PrivilegeBackend {
        override val name: String = "Fake"
        override fun isAvailable(): Boolean = true
        override fun isAuthorized(): Boolean = true
        override suspend fun requestAuthorization(): Boolean = true
        override fun isAlive(): Boolean = true
        override fun capabilities(): Set<com.headless.android.privilege.BackendCapability> = emptySet()
        override fun info(): com.headless.android.privilege.BackendInfo =
            com.headless.android.privilege.BackendInfo("Fake", 2000, null, null)
        override fun getSystemServiceBinder(serviceName: String): android.os.IBinder =
            throw UnsupportedOperationException()
        override fun close() {}
        override fun shell(command: Array<String>): ShellResult {
            val cmdStr = command.joinToString(" ")
            return when {
                cmdStr.contains("dumpsys activity activities") ->
                    ShellResult(exitCode = 0, stdout = activityActivitiesStdout, stderr = "")
                cmdStr.contains("dumpsys window displays") ->
                    ShellResult(exitCode = 0, stdout = windowDisplaysStdout, stderr = "")
                else -> ShellResult(exitCode = 0, stdout = "", stderr = "")
            }
        }
    }

    private val fakeBackend = FakePrivilegeBackend()
    private val guard = DisplayIsolationGuard(
        privilegeBackend = fakeBackend,
        displayId = 42,
        displayWidth = 1080,
        displayHeight = 1920,
        bottomGestureInset = 120,
        sideGestureInset = 48,
        topGestureInset = 80
    )

    @Test
    fun `valid tap within display bounds succeeds`() {
        guard.validateTap(540f, 960f)
        guard.validateTap(0f, 0f)
        guard.validateTap(1079f, 1919f)
    }

    @Test
    fun `tap outside bounds throws DisplayIsolationViolationException`() {
        try {
            guard.validateTap(-1f, 500f)
            fail("Expected DisplayIsolationViolationException for x < 0")
        } catch (e: DisplayIsolationViolationException) {
            assertEquals(42, e.displayId)
            assertEquals("tap", e.action)
        }

        try {
            guard.validateTap(1080f, 500f)
            fail("Expected DisplayIsolationViolationException for x >= width")
        } catch (e: DisplayIsolationViolationException) {
            assertEquals(42, e.displayId)
        }

        try {
            guard.validateTap(500f, 1920f)
            fail("Expected DisplayIsolationViolationException for y >= height")
        } catch (e: DisplayIsolationViolationException) {
            assertEquals(42, e.displayId)
        }
    }

    @Test
    fun `normal swipe in middle of screen is unmodified`() {
        val swipe = guard.sanitizeSwipe(540f, 1000f, 540f, 500f, 300L)
        assertEquals(540f, swipe.x1, 0.01f)
        assertEquals(1000f, swipe.y1, 0.01f)
        assertEquals(540f, swipe.x2, 0.01f)
        assertEquals(500f, swipe.y2, 0.01f)
        assertFalse(swipe.clamped)
    }

    @Test
    fun `upward swipe starting in bottom gesture zone is clamped to prevent SwipeUpClean`() {
        // y1 = 1850 is within bottom 120px (threshold is 1800).
        // This is the exact stroke that triggered MIUI SwipeUpClean process kill!
        val swipe = guard.sanitizeSwipe(540f, 1850f, 540f, 800f, 300L)
        assertTrue(swipe.clamped)
        assertTrue("safeY1 must be above threshold 1800", swipe.y1 < 1800f)
        assertEquals(1798f, swipe.y1, 0.01f)
        assertEquals(800f, swipe.y2, 0.01f)
    }

    @Test
    fun `edge swipes are clamped away from back-gesture zones`() {
        // Left edge swipe starting at x1 = 20 (side inset is 48)
        val leftSwipe = guard.sanitizeSwipe(20f, 500f, 300f, 500f, 200L)
        assertTrue(leftSwipe.clamped)
        assertTrue(leftSwipe.x1 > 48f)

        // Right edge swipe starting at x1 = 1060 (1080 - 48 = 1032)
        val rightSwipe = guard.sanitizeSwipe(1060f, 500f, 700f, 500f, 200L)
        assertTrue(rightSwipe.clamped)
        assertTrue(rightSwipe.x1 < 1032f)
    }

    @Test
    fun `key injection refused when target package is not on target display`() {
        fakeBackend.activityActivitiesStdout = """
Display #42 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.miui.calculator U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.miui.calculator/.Main t1}
        """.trimIndent()

        try {
            guard.validateKeyInjection("type", "com.android.chrome")
            fail("Expected DisplayIsolationViolationException")
        } catch (e: DisplayIsolationViolationException) {
            assertTrue(e.reason.contains("com.android.chrome"))
        }
    }

    @Test
    fun `key injection refused when Display 0 has active IME visible`() {
        fakeBackend.activityActivitiesStdout = """
Display #42 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t1}
        """.trimIndent()

        fakeBackend.windowDisplaysStdout = """
  ImeInsetsSourceProvider
    mImeShowing=true
    mWindowContainer=Window{188e308 u0 InputMethod}
        """.trimIndent()

        try {
            guard.validateKeyInjection("type", "com.android.chrome")
            fail("Expected DisplayIsolationViolationException because IME is showing on Display 0")
        } catch (e: DisplayIsolationViolationException) {
            assertTrue(e.reason.contains("Display 0 currently has an active soft keyboard"))
        }
    }

    @Test
    fun `key injection succeeds when target package is resumed and Display 0 is clean`() {
        fakeBackend.activityActivitiesStdout = """
Display #42 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t1}
        """.trimIndent()

        fakeBackend.windowDisplaysStdout = """
  ImeInsetsSourceProvider
    mImeShowing=false
        """.trimIndent()

        guard.validateKeyInjection("type", "com.android.chrome")
    }

    @Test
    fun `multi-display isolation guards check respective displays independently`() {
        val multiDisplayStdout = """
Display #25 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t1}
Display #26 (activities from top to bottom):
  * Task{bbb #2 type=standard A=1000:com.android.settings U=0 visible=true}
    topResumedActivity=ActivityRecord{h2 u0 com.android.settings/.Main t2}
        """.trimIndent()

        fakeBackend.activityActivitiesStdout = multiDisplayStdout
        fakeBackend.windowDisplaysStdout = "mImeShowing=false"

        val guard1 = DisplayIsolationGuard(fakeBackend, displayId = 25, displayWidth = 1080, displayHeight = 1920)
        val guard2 = DisplayIsolationGuard(fakeBackend, displayId = 26, displayWidth = 1080, displayHeight = 1920)

        // Guard 1 verifies display 25
        guard1.validateKeyInjection("type", "com.android.chrome")
        try {
            guard1.validateKeyInjection("type", "com.android.settings")
            fail("Guard 1 should reject com.android.settings since it is not top on display 25")
        } catch (e: DisplayIsolationViolationException) {
            assertTrue(e.reason.contains("com.android.settings"))
        }

        // Guard 2 verifies display 26
        guard2.validateKeyInjection("type", "com.android.settings")
        try {
            guard2.validateKeyInjection("type", "com.android.chrome")
            fail("Guard 2 should reject com.android.chrome since it is not top on display 26")
        } catch (e: DisplayIsolationViolationException) {
            assertTrue(e.reason.contains("com.android.chrome"))
        }
    }
}
