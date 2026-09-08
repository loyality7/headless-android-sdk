package com.headless.android.state

import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Answers "what is true about the device and this session right now" by querying the
 * platform, never by trusting cached assumptions.
 *
 * This exists because state-level verification needs an independent source of truth:
 * if an action's own return value were the only evidence, a silently-misdirected
 * command (a wrong Binder transaction code, an input event routed to the wrong
 * display) would look identical to success.
 */
class StateEngine(private val privilegeBackend: PrivilegeBackend) {

    private companion object {
        const val OP = "StateEngine"
    }

    fun deviceState(): DeviceState = DeviceState(
        backend = privilegeBackend.info(),
        backendAlive = privilegeBackend.isAlive(),
        backendCapabilities = privilegeBackend.capabilities(),
        capturedAtMillis = System.currentTimeMillis()
    )

    /**
     * True if [displayId] still exists according to the platform's own display list.
     * Parses `dumpsys display`, which lists every display currently registered with
     * DisplayManagerService.
     */
    fun isDisplayAlive(displayId: Int): Boolean {
        val out = try {
            privilegeBackend.shell(arrayOf("dumpsys", "display")).stdout
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "isDisplayAlive query failed for display $displayId", e)
            return false
        }
        // DisplayManagerService prints entries like "mDisplayId=123" / "Display id=123".
        return out.contains("mDisplayId=$displayId") ||
            Regex("""Display\s+id=$displayId\b""").containsMatchIn(out) ||
            Regex("""displayId\s*=\s*$displayId\b""").containsMatchIn(out)
    }

    /** All display IDs the platform currently reports. */
    fun listDisplayIds(): List<Int> {
        val out = try {
            privilegeBackend.shell(arrayOf("dumpsys", "display")).stdout
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "listDisplayIds query failed", e)
            return emptyList()
        }
        return Regex("""mDisplayId=(\d+)""").findAll(out)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .toList()
    }

    /**
     * Package name of the top resumed activity on [displayId], or null.
     *
     * Delegates to [ActivityDumpParser]. Independent of [com.headless.android.apps.AppLauncher]'s
     * launch verification on purpose — verification must not consult the same code that
     * performed the action — but both share the one tested parser rather than each
     * hand-rolling their own.
     */
    fun currentPackageOnDisplay(displayId: Int): String? {
        val out = try {
            privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "currentPackageOnDisplay query failed for display $displayId", e)
            return null
        }
        return ActivityDumpParser.foregroundPackageOnDisplay(out, displayId)
    }

    fun sessionState(sessionId: String, displayId: Int, sessionOpen: Boolean): SessionState {
        val alive = if (sessionOpen) isDisplayAlive(displayId) else false
        return SessionState(
            sessionId = sessionId,
            displayId = displayId,
            sessionOpen = sessionOpen,
            displayAlive = alive,
            currentPackage = if (alive) currentPackageOnDisplay(displayId) else null,
            capturedAtMillis = System.currentTimeMillis()
        )
    }
}
