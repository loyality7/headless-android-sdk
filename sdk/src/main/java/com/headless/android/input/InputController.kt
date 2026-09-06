package com.headless.android.input

import com.headless.android.HeadlessLog
import com.headless.android.InputInjectionException
import com.headless.android.privilege.PrivilegeBackend

/**
 * Injects touch, gesture, text, and key events into a specific display via the
 * privileged shell `input` command — the technique proven by the POC. Callers never
 * see `InputManager`, display IDs of raw motion events, or shell command strings.
 */
class InputController(
    private val privilegeBackend: PrivilegeBackend,
    private val displayId: Int
) {
    companion object {
        private const val OP = "InputController"
        private const val KEYCODE_ENTER = 66
        private const val KEYCODE_BACK = 4
    }

    fun tap(x: Float, y: Float) {
        run("tap", x.toInt().toString(), y.toInt().toString())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        run(
            "swipe",
            x1.toInt().toString(), y1.toInt().toString(),
            x2.toInt().toString(), y2.toInt().toString(),
            durationMs.toString()
        )
    }

    fun type(text: String) {
        // `input text` uses %s as its own space escape sequence.
        run("text", text.replace(" ", "%s"))
    }

    fun pressEnter() = pressKey(KEYCODE_ENTER)

    fun pressBack() = pressKey(KEYCODE_BACK)

    private fun pressKey(keyCode: Int) {
        run("keyevent", keyCode.toString())
    }

    private fun run(subCommand: String, vararg args: String) {
        val command = arrayOf("input", "-d", displayId.toString(), subCommand, *args)
        val exitCode = try {
            privilegeBackend.runShellCommand(command)
        } catch (e: Throwable) {
            HeadlessLog.event(displayId = displayId, op = "$OP.$subCommand", success = false)
            throw InputInjectionException("Failed to run input $subCommand", e)
        }
        val success = exitCode == 0
        HeadlessLog.event(displayId = displayId, op = "$OP.$subCommand", success = success)
        if (!success) {
            throw InputInjectionException("input $subCommand exited with code $exitCode")
        }
    }
}
