package com.headless.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the three parsing bugs that each produced a confidently wrong
 * conclusion on-device. Every test here corresponds to a real observed failure, not a
 * hypothetical one.
 */
class ActivityDumpParserTest {

    // ── Bug 1: "Display #" matched inside detail lines, moving the section cursor ──

    @Test
    fun `incidental Display reference inside a detail line does not start a new section`() {
        // Real shape: a display-0 detail line mentioning another display id. Previously
        // this reassigned the current section and mis-attributed the following task.
        val dump = """
Display #14 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.android.chrome U=0 visible=true}
    mLastReportedConfigurations: some text mentioning Display #0 inline
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t1}
Display #0 (activities from top to bottom):
  * Task{bbb #2 type=home A=10068:com.launcher U=0 visible=true}
  ResumedActivity: ActivityRecord{h2 u0 com.launcher/.Home t2}
        """.trimIndent()

        val sections = ActivityDumpParser.parse(dump)

        assertEquals(setOf(14, 0), sections.keys)
        // Chrome must be attributed to 14 only — never to 0 via the inline mention.
        assertEquals(setOf(14), ActivityDumpParser.displayIdsHosting(dump, "com.android.chrome"))
        assertEquals(setOf(0), ActivityDumpParser.displayIdsHosting(dump, "com.launcher"))
    }

    @Test
    fun `indented Display line is never treated as a section header`() {
        assertNull(ActivityDumpParser.sectionHeaderId("      Display #0 (something):"))
        assertNull(ActivityDumpParser.sectionHeaderId("  * Task{x} on Display #3"))
        assertEquals(7, ActivityDumpParser.sectionHeaderId("Display #7 (activities from top to bottom):"))
    }

    // ── Bug 2: default display uses "ResumedActivity:", secondary uses "topResumedActivity=" ──

    @Test
    fun `resumed package is parsed for both default and secondary display spellings`() {
        val secondary = "    topResumedActivity=ActivityRecord{ba8cbfe u0 com.android.chrome/com.google.android.apps.chrome.Main t34449}"
        val default = "  ResumedActivity: ActivityRecord{87df313 u0 com.mi.android.globallauncher/com.miui.home.launcher.Launcher t2}"

        assertEquals("com.android.chrome", ActivityDumpParser.resumedPackageFromLine(secondary))
        assertEquals("com.mi.android.globallauncher", ActivityDumpParser.resumedPackageFromLine(default))
    }

    @Test
    fun `a default-display-style resumed line is never matched for a non-zero display`() {
        // resumedPackageFromLine(line, displayId) must only try RESUMED_DEFAULT when
        // displayId == 0. Without that restriction, a stray "ResumedActivity:" line
        // appearing inside a secondary display's section (device/OEM quirk, or an
        // unrelated line incidentally matching the same shape) would be accepted as
        // that secondary display's resumed package — attributing display 0's activity
        // to the virtual display and defeating the DisplayZeroGuard contamination check.
        val defaultStyleLine = "  ResumedActivity: ActivityRecord{87df313 u0 com.mi.android.globallauncher/com.miui.home.launcher.Launcher t2}"

        assertNull(ActivityDumpParser.resumedPackageFromLine(defaultStyleLine, 8))
        assertEquals(
            "com.mi.android.globallauncher",
            ActivityDumpParser.resumedPackageFromLine(defaultStyleLine, 0)
        )
    }

    @Test
    fun `resumed package on one display is not attributed to another`() {
        // This is the exact failure that reported com.whatsapp while Gmail was automated.
        val dump = """
Display #8 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10100:com.google.android.gm U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.google.android.gm/.GmailActivity t1}
Display #0 (activities from top to bottom):
  * Task{bbb #2 type=standard A=10200:com.whatsapp U=0 visible=true}
  ResumedActivity: ActivityRecord{h2 u0 com.whatsapp/.Main t2}
        """.trimIndent()

        assertEquals("com.google.android.gm", ActivityDumpParser.resumedPackageOnDisplay(dump, 8))
        assertEquals("com.whatsapp", ActivityDumpParser.resumedPackageOnDisplay(dump, 0))
    }

    // ── Bug 3: resumed package empty for every sample ──

    @Test
    fun `resumed package is not null for a well formed section`() {
        val dump = """
Display #2 (activities from top to bottom):
  * Task{98a8dac #34449 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{ba8cbfe u0 com.android.chrome/com.google.android.apps.chrome.Main t34449}
        """.trimIndent()

        assertEquals("com.android.chrome", ActivityDumpParser.resumedPackageOnDisplay(dump, 2))
    }

    // ── Placement semantics that safety depends on ──

    @Test
    fun `app present on multiple displays reports all of them`() {
        // Distinguishing "on our display" from "also on display 0" is what makes it
        // possible to refuse input when the app is on the user's physical screen.
        val dump = """
Display #5 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.android.chrome U=0 visible=true}
Display #0 (activities from top to bottom):
  * Task{bbb #2 type=standard A=10193:com.android.chrome U=0 visible=true}
        """.trimIndent()

        assertEquals(setOf(5, 0), ActivityDumpParser.displayIdsHosting(dump, "com.android.chrome"))
    }

    @Test
    fun `absent app reports no displays`() {
        val dump = """
Display #0 (activities from top to bottom):
  * Task{bbb #2 type=home A=10068:com.launcher U=0 visible=true}
        """.trimIndent()

        assertTrue(ActivityDumpParser.displayIdsHosting(dump, "com.android.chrome").isEmpty())
    }

    @Test
    fun `task package is extracted from the A=uid colon package field`() {
        val line = "  * Task{98a8dac #34449 type=standard A=10193:com.android.chrome U=0 visible=true sz=1}"
        assertEquals("com.android.chrome", ActivityDumpParser.taskPackageFromLine(line))
        assertNull(ActivityDumpParser.taskPackageFromLine("    not a task line A=10193:com.foo"))
    }

    @Test
    fun `component with an inner class name is parsed`() {
        // Regression: the class-name character class needs a literal '$'. Written as a
        // bare $ inside a Kotlin raw string it became a string template, yielding an
        // invalid pattern that made this object fail to initialize — surfacing on-device
        // as NoClassDefFoundError at every call site rather than as a parsing bug.
        val line = "    topResumedActivity=ActivityRecord{h1 u0 com.example.app/.Outer\$Inner t9}"
        assertEquals("com.example.app", ActivityDumpParser.resumedPackageFromLine(line))
    }

    @Test
    fun `parser object initializes`() {
        // Touching the object at all would have caught the ExceptionInInitializerError.
        assertNull(ActivityDumpParser.sectionHeaderId("not a header"))
    }

    @Test
    fun `display with a task but no resumed activity still reports the app`() {
        // Observed on-device: Chrome on a virtual display appeared as a pinned task with
        // NO topResumedActivity line. Reporting "no app" there is misleading.
        val dump = """
Display #20 (activities from top to bottom):
  * Task{d9e7ba4 #34485 type=standard A=10193:com.android.chrome U=0 visible=true mode=pinned sz=1}
      rootOfTask=true task=Task{d9e7ba4 #34485 type=standard A=10193:com.android.chrome}
        """.trimIndent()

        assertNull(ActivityDumpParser.resumedPackageOnDisplay(dump, 20))
        assertEquals("com.android.chrome", ActivityDumpParser.foregroundPackageOnDisplay(dump, 20))
        assertEquals(false, ActivityDumpParser.foregroundPackageIsResumed(dump, 20))
    }

    @Test
    fun `resumed activity takes precedence over task package`() {
        val dump = """
Display #3 (activities from top to bottom):
  * Task{aaa #1 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t1}
        """.trimIndent()

        assertEquals("com.android.chrome", ActivityDumpParser.foregroundPackageOnDisplay(dump, 3))
        assertEquals(true, ActivityDumpParser.foregroundPackageIsResumed(dump, 3))
    }

    @Test
    fun `empty and malformed dumps do not throw`() {
        assertTrue(ActivityDumpParser.parse("").isEmpty())
        assertTrue(ActivityDumpParser.parse("garbage\nmore garbage").isEmpty())
        assertNull(ActivityDumpParser.resumedPackageOnDisplay("", 0))
        assertTrue(ActivityDumpParser.displayIdsHosting("", "com.foo").isEmpty())
    }

    @Test
    fun `section present for a destroyed display is still parsed`() {
        // ActivityManager kept printing sections for displays 13/14 after DisplayManager
        // stopped reporting them. The parser must not crash or skip; callers cross-check
        // liveness separately via StateEngine.isDisplayAlive.
        val dump = """
Display #14 (activities from top to bottom):
Display #13 (activities from top to bottom):
Display #0 (activities from top to bottom):
  ResumedActivity: ActivityRecord{h2 u0 com.launcher/.Home t2}
        """.trimIndent()

        val sections = ActivityDumpParser.parse(dump)
        assertEquals(setOf(14, 13, 0), sections.keys)
        assertNull(sections[14]!!.resumedPackage)
        assertEquals("com.launcher", sections[0]!!.resumedPackage)
    }
}
