package com.headless.android.state

import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Detects and reports leaked virtual displays, and repairs input-method state left behind
 * by displays that have already been destroyed.
 *
 * ## Why this exists
 *
 * Earlier builds created a virtual display per session and never released it (the
 * `IVirtualDisplayCallback` token was discarded, so `releaseVirtualDisplay` could not be
 * called). 30+ displays accumulated on-device. Each one kept an app resident with
 * focusable windows, and InputMethodManagerService retained a client record per display —
 * observed as 77 stale `mSelfReportedDisplayId` entries and a keyboard that opened and
 * closed continuously on the user's physical screen.
 *
 * The leak itself is fixed at the source (see `HeadlessDisplay.release`), but a janitor is
 * still needed because:
 *  - a crashed/killed runtime cannot run its own cleanup, and
 *  - stale IME client records outlive the displays that created them.
 */
class DisplayJanitor(private val privilegeBackend: PrivilegeBackend) {

    companion object {
        const val OP = "DisplayJanitor"
        const val DEFAULT_DISPLAY = 0
        const val STALE_IME_REBOOT_THRESHOLD = 50
    }

    enum class ConsumerAdvice {
        /** Everything is clean. Normal operation. */
        NORMAL,
        /** Leaked displays from previous unreleased runs or other processes exist. */
        WARN_LEAKED_DISPLAYS,
        /** Inert stale IME records exist in system_server, keyboard dismissed on display 0. Safe to proceed. */
        WARN_STALE_IME_SAFE_TO_PROCEED,
        /** High volume of stale records (>= 50). Safe to proceed, but reboot advised during scheduled maintenance. */
        ADVISE_REBOOT
    }

    data class Report(
        /** Display IDs currently registered with DisplayManagerService. */
        val liveDisplayIds: Set<Int>,
        /** Live displays other than the default one — candidates for leaks. */
        val nonDefaultDisplayIds: Set<Int>,
        /** Display IDs the input-method service still holds client records for. */
        val imeClientDisplayIds: Set<Int>,
        /** IME records whose display no longer exists — pure garbage. */
        val staleImeDisplayIds: Set<Int>
    ) {
        val hasLeakedDisplays: Boolean get() = nonDefaultDisplayIds.isNotEmpty()
        val hasStaleImeState: Boolean get() = staleImeDisplayIds.isNotEmpty()

        val advice: ConsumerAdvice
            get() = when {
                hasLeakedDisplays -> ConsumerAdvice.WARN_LEAKED_DISPLAYS
                staleImeDisplayIds.size >= STALE_IME_REBOOT_THRESHOLD -> ConsumerAdvice.ADVISE_REBOOT
                hasStaleImeState -> ConsumerAdvice.WARN_STALE_IME_SAFE_TO_PROCEED
                else -> ConsumerAdvice.NORMAL
            }

        fun summary(): String = buildString {
            append("live=${liveDisplayIds.sorted()}")
            append(" nonDefault=${nonDefaultDisplayIds.sorted()}")
            append(" staleImeRecords=${staleImeDisplayIds.size}")
            append(" advice=$advice")
        }
    }

    /** Inspects display and IME state without changing anything. */
    fun inspect(): Report {
        val live = liveDisplayIds()
        val imeDisplays = imeClientDisplayIds()
        return Report(
            liveDisplayIds = live,
            nonDefaultDisplayIds = live - DEFAULT_DISPLAY,
            imeClientDisplayIds = imeDisplays,
            staleImeDisplayIds = imeDisplays - live
        )
    }

    /** Display IDs DisplayManagerService currently knows about. */
    fun liveDisplayIds(): Set<Int> {
        val out = try {
            privilegeBackend.shell(arrayOf("dumpsys", "display")).stdout
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "display query failed", e)
            return emptySet()
        }
        return Regex("""mDisplayId=(\d+)""").findAll(out)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
    }

    /** Display IDs InputMethodManagerService still holds client records for. */
    fun imeClientDisplayIds(): Set<Int> {
        val out = try {
            privilegeBackend.shell(arrayOf("dumpsys", "input_method")).stdout
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "input_method query failed", e)
            return emptySet()
        }
        return Regex("""mSelfReportedDisplayId=(\d+)""").findAll(out)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .toSet()
    }

    /**
     * Best-effort cleanup of state left by dead displays.
     *
     * Important limitation, stated plainly: **leaked virtual displays owned by another
     * process cannot be released from here.** `releaseVirtualDisplay` requires the exact
     * `IVirtualDisplayCallback` token used at creation, and displays are also owned by the
     * creating UID — so a display leaked by a previous (now dead) runtime process is
     * unreachable. Killing the owning process is what actually frees those, which is why
     * [inspect] reports them for the caller to act on rather than pretending to fix them.
     *
     * What this method *can* do is hide the keyboard and reset the IME so it stops acting
     * on records belonging to displays that no longer exist. Stale records themselves live
     * in system_server and only fully clear on reboot.
     *
     * @return the report gathered before cleanup was attempted.
     */
    fun cleanUp(): Report {
        val report = inspect()

        if (report.hasLeakedDisplays) {
            HeadlessLog.w(
                OP,
                "leaked virtual displays present: ${report.nonDefaultDisplayIds.sorted()} — " +
                    "these belong to their creating process and cannot be released from here; " +
                    "stop the owning process to free them"
            )
        }

        if (report.hasStaleImeState) {
            HeadlessLog.w(
                OP,
                "${report.staleImeDisplayIds.size} IME client records reference destroyed " +
                    "displays ${report.staleImeDisplayIds.sorted().take(10)}${if (report.staleImeDisplayIds.size > 10) "..." else ""}; " +
                    "hiding IME. Records persist in system_server until reboot."
            )
            hideKeyboard()
        }

        if (report.advice == ConsumerAdvice.ADVISE_REBOOT) {
            HeadlessLog.w(
                OP,
                "High volume of stale IME records (${report.staleImeDisplayIds.size}) accumulated in system_server. " +
                    "While safe to proceed, a device reboot is advised during scheduled maintenance."
            )
        }

        HeadlessLog.event(op = "$OP.cleanUp", success = true)
        return report
    }

    /**
     * Forces the soft keyboard down on the physical display. Used after cleanup because
     * dead-display IME records were observed re-triggering the keyboard on display 0.
     */
    fun hideKeyboard() {
        try {
            // KEYCODE_BACK on the default display dismisses a shown IME without
            // disturbing whatever the user has in the foreground.
            val shown = privilegeBackend.shell(arrayOf("dumpsys", "input_method"))
                .stdout.contains("mInputShown=true")
            if (shown) {
                privilegeBackend.shell(arrayOf("input", "-d", "0", "keyevent", "111")) // KEYCODE_ESCAPE
                HeadlessLog.i(OP, "dismissed soft keyboard on the physical display")
            }
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "hideKeyboard failed", e)
        }
    }
}
