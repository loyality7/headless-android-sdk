package com.headless.android.state

import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Watchdog that outlives the app process.
 *
 * A hidden display is owned by the app process; if that process dies (crash, reinstall,
 * OEM kill) the display is deleted and Android moves its apps onto display 0 — the user's
 * physical screen. Nothing running in the dead process can undo that, so a detached shell
 * loop (started through the privilege backend, not a child of the app) watches for the
 * display to vanish and immediately removes the exact task ids this session launched.
 *
 * A normal close calls [stop] first, which deletes the flag file; the loop then exits
 * without touching anything. Limitation: only tasks present when [start] is called are
 * covered, and the screen can show the stray app for the poll interval (about a second).
 */
class DisplayGuard(private val backend: PrivilegeBackend) {

    companion object {
        private const val OP = "DisplayGuard"
        private const val FLAG_DIR = "/data/local/tmp"

        fun flagPath(displayId: Int) = "$FLAG_DIR/headless_guard_$displayId"

        /** Shell loop run detached. Pure function so it can be unit tested. */
        fun script(displayId: Int, taskIds: List<Int>, flag: String): String {
            require(displayId > 0) { "guard refuses display $displayId" }
            val removals = taskIds.joinToString("; ") { "am stack remove $it" }
            return "while [ -f $flag ]; do " +
                "if ! dumpsys display | grep -Eq 'mDisplayId=$displayId([^0-9]|\$)'; then " +
                "${removals.ifEmpty { ":" }}; rm -f $flag; exit 0; fi; sleep 0.4; done"
        }
    }

    /** Starts (or restarts) the watchdog for [displayId], covering [taskIds]. */
    fun start(displayId: Int, taskIds: List<Int>) {
        if (taskIds.isEmpty()) return
        stop(displayId)
        val flag = flagPath(displayId)
        val loop = script(displayId, taskIds, flag)
        // Outer shell backgrounds the loop in its own session and exits at once, so this
        // call returns immediately and Shizuku's owner-death cleanup cannot reach the loop.
        val launcher = "touch $flag; setsid sh -c '$loop' >/dev/null 2>&1 </dev/null &"
        try {
            backend.shell(arrayOf("sh", "-c", launcher))
            HeadlessLog.i(OP, "guarding display $displayId tasks=$taskIds")
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "could not start display guard for $displayId", e)
        }
    }

    /** Stops the watchdog without removing anything. Call before a normal display release. */
    fun stop(displayId: Int) {
        try {
            backend.shell(arrayOf("rm", "-f", flagPath(displayId)))
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "could not clear guard flag for $displayId", e)
        }
    }
}
