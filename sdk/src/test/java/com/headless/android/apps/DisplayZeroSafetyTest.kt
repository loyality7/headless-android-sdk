package com.headless.android.apps

import com.headless.android.AppLaunchException
import com.headless.android.input.BinderInputInjector
import com.headless.android.input.InputController
import com.headless.android.privilege.BackendCapability
import com.headless.android.privilege.BackendInfo
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.privilege.ShellResult
import com.headless.android.state.ActivityDumpParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The SDK must never launch, inject, or kill anything on display 0 (the user's screen). */
class DisplayZeroSafetyTest {

    private class RecordingBackend(
        var activities: String = "",
        var displays: String = "mDisplayId=0\n"
    ) : PrivilegeBackend {
        val commands = mutableListOf<String>()
        override val name = "Fake"
        override fun isAvailable() = true
        override fun isAuthorized() = true
        override suspend fun requestAuthorization() = true
        override fun isAlive() = true
        override fun capabilities(): Set<BackendCapability> = emptySet()
        override fun info() = BackendInfo("Fake", 2000, null, null)
        override fun getSystemServiceBinder(serviceName: String): android.os.IBinder =
            throw UnsupportedOperationException("binder must not be reached")
        override fun close() {}
        override fun shell(command: Array<String>): ShellResult {
            val cmd = command.joinToString(" ")
            commands.add(cmd)
            if (cmd.startsWith("am stack remove")) {
                val id = cmd.substringAfterLast(' ')
                activities = activities.lines()
                    .filterNot { it.contains("#$id ") || it.contains(" t$id}") }
                    .joinToString("\n")
            }
            val out = when {
                cmd.startsWith("dumpsys activity") -> activities
                cmd.startsWith("dumpsys display") -> displays
                else -> ""
            }
            return ShellResult(0, out, "")
        }
    }

    private val dump = """
Display #7 (activities from top to bottom):
  * Task{aaa #50 type=standard A=10193:com.android.chrome U=0 visible=true}
    topResumedActivity=ActivityRecord{h1 u0 com.android.chrome/.Main t50}
Display #0 (activities from top to bottom):
  * Task{bbb #2 type=standard A=10193:com.android.chrome U=0 visible=true}
    child
    * Task{ccc #3 type=standard A=10193:com.android.chrome U=0 visible=true}
  ResumedActivity: ActivityRecord{h2 u0 com.android.chrome/.Main t2}
    """.trimIndent()

    @Test
    fun `launch refuses display 0 before touching the platform`() {
        val backend = RecordingBackend()
        try {
            AppLauncher(backend).launch("com.android.chrome", 0)
            fail("expected AppLaunchException")
        } catch (e: AppLaunchException) {
            assertTrue(e.message!!.contains("display 0"))
        }
        assertTrue("no command may run", backend.commands.isEmpty())
    }

    @Test
    fun `launch refuses a display that does not exist instead of falling back to display 0`() {
        val backend = RecordingBackend(displays = "mDisplayId=0\nmDisplayId=70\n")
        try {
            AppLauncher(backend).launch("com.android.chrome", 7)
            fail("expected AppLaunchException")
        } catch (e: AppLaunchException) {
            assertTrue(e.message!!.contains("does not exist"))
        }
    }

    @Test
    fun `input refuses display 0`() {
        val backend = RecordingBackend()
        try { InputController(backend, 0); fail() } catch (_: IllegalArgumentException) {}
        try { BinderInputInjector(backend, 0); fail() } catch (_: IllegalArgumentException) {}
    }

    @Test
    fun `root task ids are per display, per package, top level only`() {
        assertEquals(listOf(50), ActivityDumpParser.rootTaskIdsOnDisplay(dump, 7, "com.android.chrome"))
        assertEquals(listOf(2), ActivityDumpParser.rootTaskIdsOnDisplay(dump, 0, "com.android.chrome"))
        assertEquals(emptyList<Int>(), ActivityDumpParser.rootTaskIdsOnDisplay(dump, 7, "com.whatsapp"))
    }

    @Test
    fun `closing on the virtual display leaves the users copy on display 0 alone`() {
        val backend = RecordingBackend(activities = dump)

        assertTrue(AppLauncher(backend).closeOnDisplay("com.android.chrome", 7))

        assertTrue(backend.commands.contains("am stack remove 50"))
        assertFalse("must not remove the display-0 task", backend.commands.contains("am stack remove 2"))
        assertFalse("must not force-stop while the user has it on display 0",
            backend.commands.any { it.contains("force-stop") })
        assertTrue(ActivityDumpParser.displayIdsHosting(backend.activities, "com.android.chrome").contains(0))
    }

    @Test
    fun `orphan on display 0 is left alone`() {
        val backend = RecordingBackend(activities = dump)
        assertFalse(AppLauncher(backend).reapOrphan("com.android.chrome"))
        assertFalse(backend.commands.any { it.contains("force-stop") })
    }
}
