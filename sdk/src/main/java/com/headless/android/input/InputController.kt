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
    private val displayId: Int,
    private val isolationGuard: DisplayIsolationGuard? = null,
    private val directInjector: BinderInputInjector? = BinderInputInjector(privilegeBackend, displayId)
) {
    companion object {
        private const val OP = "InputController"
        private const val KEYCODE_ENTER = 66
        private const val KEYCODE_BACK = 4
        private const val KEYCODE_DEL = 67
        private const val KEYCODE_TAB = 61

        /**
         * `input text` uses %s as its space escape (AOSP does a literal replace).
         * Senders pass RAW text with real spaces — escaping happens here, once, at the
         * shell boundary. Pre-escaped %s in user input is passed through untouched.
         */
        fun escapeForInput(text: String): String = text.replace(" ", "%s")
    }

    fun tap(x: Float, y: Float) {
        isolationGuard?.validateTap(x, y)
        if (directInjector?.tap(x, y) == true) {
            HeadlessLog.i(OP, "tap($x, $y) injected via direct Binder")
            return
        }
        run("tap", x.toInt().toString(), y.toInt().toString())
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        val safe = isolationGuard?.sanitizeSwipe(x1, y1, x2, y2, durationMs)
        val finalX1 = safe?.x1 ?: x1
        val finalY1 = safe?.y1 ?: y1
        val finalX2 = safe?.x2 ?: x2
        val finalY2 = safe?.y2 ?: y2
        val finalDuration = safe?.durationMs ?: durationMs

        if (directInjector?.swipe(finalX1, finalY1, finalX2, finalY2, finalDuration) == true) {
            HeadlessLog.i(OP, "swipe injected via direct Binder")
            return
        }
        run(
            "swipe",
            finalX1.toInt().toString(), finalY1.toInt().toString(),
            finalX2.toInt().toString(), finalY2.toInt().toString(),
            finalDuration.toString()
        )
    }

    fun type(text: String) {
        run("text", escapeForInput(text))
    }

    fun pressEnter() = pressKey(KEYCODE_ENTER)

    fun pressBack() = pressKey(KEYCODE_BACK)

    fun pressTab() = pressKey(KEYCODE_TAB)

    /**
     * Deletes [count] characters backwards from the cursor.
     *
     * Injected via direct Binder when possible (<10ms per DEL), falling back to single
     * multi-arg shell invocation.
     */
    fun deleteText(count: Int) {
        require(count > 0) { "count must be > 0" }
        if (directInjector != null) {
            var allOk = true
            for (i in 0 until count) {
                if (!directInjector.pressKey(KEYCODE_DEL)) {
                    allOk = false
                    break
                }
            }
            if (allOk) return
        }
        val args = Array(count) { KEYCODE_DEL.toString() }
        run("keyevent", *args)
    }

    /**
     * Clears the focused field: select-all, then delete.
     *
     * Uses CTRL+A rather than a long-press/menu flow because it needs no coordinates and
     * no assumptions about the app's selection UI.
     */
    fun clearText() {
        // KEYCODE_A (29) with the CTRL meta state (4096) = select all.
        run("keycombination", "113", "29") // KEYCODE_CTRL_LEFT + KEYCODE_A
        pressKey(KEYCODE_DEL)
    }

    private fun pressKey(keyCode: Int) {
        if (directInjector?.pressKey(keyCode) == true) {
            HeadlessLog.i(OP, "pressKey($keyCode) injected via direct Binder")
            return
        }
        run("keyevent", keyCode.toString())
    }

    /**
     * Runs one `input` subcommand and performs **execution-level** verification only:
     * the command ran and reported no error. This says nothing about whether the UI
     * reacted — state and semantic verification are the transaction engine's job, not
     * this primitive's.
     */
    private fun run(subCommand: String, vararg args: String) {
        val command = arrayOf("input", "-d", displayId.toString(), subCommand, *args)
        val result = try {
            privilegeBackend.shell(command)
        } catch (e: Throwable) {
            HeadlessLog.event(displayId = displayId, op = "$OP.$subCommand", success = false)
            throw InputInjectionException("Failed to run input $subCommand", e)
        }
        HeadlessLog.event(displayId = displayId, op = "$OP.$subCommand", success = result.isSuccess)
        if (!result.isSuccess) {
            throw InputInjectionException("input $subCommand failed: ${result.summary()}")
        }
    }
}
