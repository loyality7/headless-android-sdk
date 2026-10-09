package com.headless.android.input

import com.headless.android.DisplayIsolationViolationException
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.state.ActivityDumpParser

/**
 * Enforces strict isolation boundaries around a headless virtual display, ensuring that
 * Display 0 (the user's physical screen) is provably immune from automation side effects.
 *
 * Specifically addresses the 3 verified isolation failure modes:
 *  1. **OEM Gesture Cleaner / SwipeUpClean**: Injected swipes starting near screen edges (e.g. bottom
 *     navigation area) being intercepted by MIUI's system gesture layer as home/kill gestures.
 *  2. **Stray Key/Text Injection**: Key events escaping the target display when input focus has not
 *     been acquired or when Display 0 has a focused text field.
 *  3. **IME Keyboard Leak**: Virtual display apps triggering a soft keyboard on Display 0.
 */
class DisplayIsolationGuard(
    private val privilegeBackend: PrivilegeBackend,
    val displayId: Int,
    val displayWidth: Int,
    val displayHeight: Int,
    val bottomGestureInset: Int = (displayHeight * 0.07f).toInt().coerceAtLeast(100),
    val sideGestureInset: Int = (displayWidth * 0.04f).toInt().coerceAtLeast(40),
    val topGestureInset: Int = 80
) {
    companion object {
        private const val OP = "DisplayIsolationGuard"
    }

    /**
     * Sanitized swipe coordinates that avoid system gesture trigger zones.
     */
    data class SafeSwipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long,
        val clamped: Boolean
    )

    /**
     * Report describing current Display 0 contamination status.
     *
     * [imeShowingOnDisplayZero] is raw visibility (any keyboard, including the user's own).
     * [isContaminated] is the one to act on: it is true only for a keyboard that serves a
     * window on another display, i.e. a real leak from automation.
     */
    data class DisplayZeroStatus(
        val imeShowingOnDisplayZero: Boolean,
        val topActivityOnDisplayZero: String?,
        val isContaminated: Boolean,
        val detail: String
    )

    /**
     * Validates that tap coordinates fall within valid display boundaries and do not land
     * in dangerous outer margins.
     */
    fun validateTap(x: Float, y: Float) {
        if (x < 0f || x >= displayWidth || y < 0f || y >= displayHeight) {
            throw DisplayIsolationViolationException(
                reason = "Tap coordinates ($x, $y) are outside display bounds (${displayWidth}x${displayHeight})",
                displayId = displayId,
                action = "tap"
            )
        }
        if (y >= (displayHeight - bottomGestureInset)) {
            HeadlessLog.w(OP, "Warning: tap ($x, $y) is within bottom gesture zone ($bottomGestureInset px from bottom)")
        }
    }

    /**
     * Validates and sanitizes swipe coordinates so that strokes cannot be intercepted
     * as system navigation gestures (such as Xiaomi's SwipeUpClean home-kill gesture).
     */
    fun sanitizeSwipe(
        x1: Float,
        y1: Float,
        x2: Float,
        y2: Float,
        durationMs: Long
    ): SafeSwipe {
        // First, ensure all coordinates are within the physical bounds of the display
        if (x1 < 0f || x1 >= displayWidth || y1 < 0f || y1 >= displayHeight ||
            x2 < 0f || x2 >= displayWidth || y2 < 0f || y2 >= displayHeight
        ) {
            throw DisplayIsolationViolationException(
                reason = "Swipe coordinates ($x1,$y1)->($x2,$y2) exceed display bounds (${displayWidth}x${displayHeight})",
                displayId = displayId,
                action = "swipe"
            )
        }

        var clamped = false
        var safeX1 = x1
        var safeY1 = y1
        var safeX2 = x2
        var safeY2 = y2

        // Check for upward swipe starting in bottom system gesture zone:
        // On HyperOS/MIUI, swiping up from the bottom edge triggers SwipeUpClean, which killed the app!
        val bottomThreshold = displayHeight - bottomGestureInset
        if (safeY1 >= bottomThreshold && safeY2 < safeY1) {
            safeY1 = (bottomThreshold - 2).toFloat()
            clamped = true
            HeadlessLog.w(
                OP,
                "Clamped upward swipe starting at y1=$y1 to safeY1=$safeY1 to prevent OEM SwipeUpClean trigger"
            )
        }

        // Check for horizontal swipe starting in side back-gesture zones
        if (safeX1 < sideGestureInset && safeX2 > safeX1) {
            safeX1 = (sideGestureInset + 2).toFloat()
            clamped = true
            HeadlessLog.w(
                OP,
                "Clamped left-edge swipe starting at x1=$x1 to safeX1=$safeX1 to prevent back-gesture trigger"
            )
        } else if (safeX1 > (displayWidth - sideGestureInset) && safeX2 < safeX1) {
            safeX1 = (displayWidth - sideGestureInset - 2).toFloat()
            clamped = true
            HeadlessLog.w(
                OP,
                "Clamped right-edge swipe starting at x1=$x1 to safeX1=$safeX1 to prevent back-gesture trigger"
            )
        }

        // Check for downward swipe starting in top notification shade zone
        if (safeY1 < topGestureInset && safeY2 > safeY1) {
            safeY1 = (topGestureInset + 2).toFloat()
            clamped = true
            HeadlessLog.w(
                OP,
                "Clamped top-edge downward swipe starting at y1=$y1 to safeY1=$safeY1 to prevent notification shade pull"
            )
        }

        return SafeSwipe(safeX1, safeY1, safeX2, safeY2, durationMs, clamped)
    }

    /**
     * Validates that key injection (text typing, keyevents) will not leak to Display 0.
     *
     * Ensures that:
     * 1. The target package has a verified top activity on [displayId].
     * 2. Key events are not injected blindly if Display 0 holds an active text entry target.
     */
    fun validateKeyInjection(action: String, expectedPackage: String?) {
        if (expectedPackage == null) {
            throw DisplayIsolationViolationException(
                reason = "Refusing key injection: no target app is registered for this session",
                displayId = displayId,
                action = action
            )
        }

        // Check if the expected package is currently hosting a resumed activity on this display
        val activitiesDump = try {
            privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        } catch (e: Throwable) {
            throw DisplayIsolationViolationException(
                reason = "Failed to query activity state before $action: ${e.message}",
                displayId = displayId,
                action = action,
                cause = e
            )
        }

        val topPackageOnTarget = ActivityDumpParser.foregroundPackageOnDisplay(activitiesDump, displayId)
        if (topPackageOnTarget != expectedPackage) {
            val allHosting = ActivityDumpParser.displayIdsHosting(activitiesDump, expectedPackage)
            throw DisplayIsolationViolationException(
                reason = "Target package '$expectedPackage' is not resumed on display $displayId " +
                    "(current top: '$topPackageOnTarget', package is on displays: $allHosting)",
                displayId = displayId,
                action = action
            )
        }

        // Check Display 0 window focus to verify Display 0 is not actively focused on an input target
        // that could intercept global key events
        val windowDump = try {
            privilegeBackend.shell(arrayOf("dumpsys", "window", "displays")).stdout
        } catch (e: Throwable) {
            // Non-fatal if dumpsys window fails, but log warning
            HeadlessLog.w(OP, "Unable to inspect window displays dump before $action", e)
            ""
        }

        if (windowDump.isNotEmpty() && isDisplayZeroImeShowing(windowDump) &&
            ImeOwnership.isAutomationKeyboard(imeClient())
        ) {
            throw DisplayIsolationViolationException(
                reason = "Display 0 currently has an active soft keyboard showing; refusing $action to prevent key leakage",
                displayId = displayId,
                action = action
            )
        }
    }

    /**
     * Probes Display 0 for any signs of contamination (e.g. soft keyboard showing on Display 0).
     */
    fun probeDisplayZero(): DisplayZeroStatus {
        val windowDump = try {
            privilegeBackend.shell(arrayOf("dumpsys", "window", "displays")).stdout
        } catch (e: Throwable) {
            return DisplayZeroStatus(
                imeShowingOnDisplayZero = false,
                topActivityOnDisplayZero = null,
                isContaminated = false,
                detail = "Could not probe window displays: ${e.message}"
            )
        }

        val imeVisible = isDisplayZeroImeShowing(windowDump)
        // A keyboard on display 0 is only a leak if it serves a window on another display.
        // The user opening their own keyboard in an app on display 0 is not.
        val client = if (imeVisible) imeClient() else null
        val leak = imeVisible && ImeOwnership.isAutomationKeyboard(client)

        val activitiesDump = try {
            privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        } catch (e: Throwable) {
            ""
        }
        val displayZeroPkg = ActivityDumpParser.foregroundPackageOnDisplay(activitiesDump, 0)

        val detail = when {
            leak -> "CRITICAL: the soft keyboard on Display 0 serves a window on display " +
                "${client?.displayId ?: "unknown"} (automation), not an app on Display 0"
            imeVisible -> "A keyboard is open on Display 0 for the user's own app " +
                "(uid ${client?.uid}); not an automation leak"
            else -> "Display 0 is clean (topActivity=$displayZeroPkg, imeShowing=false)"
        }

        return DisplayZeroStatus(
            imeShowingOnDisplayZero = imeVisible,
            topActivityOnDisplayZero = displayZeroPkg,
            isContaminated = leak,
            detail = detail
        )
    }

    private fun imeClient(): ImeClient? = try {
        ImeOwnership.currentClient(privilegeBackend.shell(arrayOf("dumpsys", "input_method")).stdout)
    } catch (e: Throwable) {
        HeadlessLog.w(OP, "could not read input_method state", e)
        null
    }

    private fun isDisplayZeroImeShowing(windowDump: String): Boolean {
        // Checks ImeInsetsSourceProvider or mImeShowing in WindowManager
        // "mImeShowing=true" or "type=ime ... visible=true"
        if (windowDump.contains("mImeShowing=true")) {
            return true
        }
        val imeVisibleMatch = Regex("""type=ime[^\n]+visible=true""").containsMatchIn(windowDump)
        return imeVisibleMatch
    }
}
