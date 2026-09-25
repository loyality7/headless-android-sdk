package com.headless.android

import com.headless.android.perception.PerceptionEngine
import com.headless.android.perception.ScreenAnalyzer
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.state.DisplayJanitor
import com.headless.android.state.SessionLedger
import com.headless.android.state.StateEngine
import com.headless.android.ime.ImeSwitcher

/**
 * Runtime lifecycle owner. Holds the privilege backend, tracks every live session, and
 * mints [HeadlessSession]s under a hard session cap.
 *
 * Obtain one via [HeadlessAutomation.start], not directly.
 *
 * ## Why sessions are capped and tracked
 *
 * Each session owns a trusted virtual display, and each display keeps whatever app was
 * launched on it alive with focusable windows. Unbounded session creation was observed
 * leaking 30+ displays on-device, which kept as many apps resident and caused continuous
 * IME (keyboard) show/hide thrashing on the physical display. A virtual display is an
 * expensive, system-wide, user-visible-side-effect resource — not a cheap handle — so the
 * runtime refuses to create more than [maxSessions] at once and always knows what it owns.
 */
class HeadlessRuntime internal constructor(
    private val privilegeBackend: PrivilegeBackend,
    ledgerDir: java.io.File? = null,
    headlessImeId: String? = null,
    /**
     * Maximum simultaneously-open sessions. Defaults to 1: one agent drives one display.
     * A caller that genuinely needs a second display must close the first or raise this
     * deliberately, rather than leaking displays by accident.
     */
    val maxSessions: Int = 1,
    /** Auto-switch system keyboard to the headless IME while a session is open. */
    val autoSwitchIme: Boolean = true
) {

    companion object {
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_DENSITY_DPI = 320
    }

    private val stateEngine = StateEngine(privilegeBackend)
    private val janitor = DisplayJanitor(privilegeBackend)
    private val ledger = ledgerDir?.let { SessionLedger(it) }
    private val imeSwitcher = headlessImeId?.let { ImeSwitcher(privilegeBackend, ledgerDir, it) }
    private val sessions = mutableListOf<HeadlessSession>()
    private val lock = Any()

    @Volatile
    private var closed = false

    /** True if the privilege backend is currently authorized to create sessions. */
    fun isAuthorized(): Boolean = privilegeBackend.isAuthorized()

    /** True if the backend is available, authorized, and its transport is still alive. */
    fun isAlive(): Boolean = privilegeBackend.isAlive()

    /** Requests privilege authorization (e.g. the Shizuku permission prompt) if not already granted. */
    suspend fun requestAuthorization(): Boolean = privilegeBackend.requestAuthorization()

    /** Snapshot of backend identity, liveness and declared capabilities. */
    fun deviceState() = stateEngine.deviceState()

    /**
     * Inspects display/IME state for leaks left by earlier runs and repairs what it can.
     *
     * Worth calling at startup: a runtime that was killed (OEM task cleaner, LMKD) cannot
     * run its own cleanup, so its displays and IME records outlive it and can make the
     * user's physical keyboard misbehave.
     */
    fun cleanUpStaleState(): DisplayJanitor.Report = janitor.cleanUp()

    /** Read-only view of leaked-display / stale-IME state. */
    fun inspectStaleState(): DisplayJanitor.Report = janitor.inspect()

    /** Sessions currently open and owned by this runtime. */
    fun openSessions(): List<HeadlessSession> = synchronized(lock) { sessions.toList() }

    /**
     * Creates a new isolated headless session with its own hidden virtual display.
     *
     * Throws [IllegalStateException] if [maxSessions] sessions are already open — closing
     * one is required first. This is deliberate: silently allowing another display is how
     * displays leak.
     */
    fun createSession(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        densityDpi: Int = DEFAULT_DENSITY_DPI,
        analyzer: ScreenAnalyzer = PerceptionEngine()
    ): HeadlessSession {
        check(!closed) { "HeadlessRuntime is closed" }
        if (!privilegeBackend.isAuthorized()) {
            throw PermissionDeniedException("Privilege backend is not authorized; call requestAuthorization() first")
        }

        synchronized(lock) {
            reapClosedSessions()
            repairOrphan()
            check(sessions.size < maxSessions) {
                "Session limit reached (${sessions.size}/$maxSessions). Close an existing " +
                    "session before creating another — each session holds a virtual display."
            }
            val session = HeadlessSession(
                privilegeBackend = privilegeBackend,
                displayWidth = width,
                displayHeight = height,
                displayDensityDpi = densityDpi,
                analyzer = analyzer,
                ledger = ledger,
                onClosing = { imeSwitcher?.restore() },
                onClosed = { closedSession ->
                    synchronized(lock) {
                        sessions.remove(closedSession)
                        if (sessions.none { it.isOpen }) imeSwitcher?.restore()
                    }
                }
            )
            sessions.add(session)
            if (autoSwitchIme && imeSwitcher?.switchToHeadless() == false) {
                HeadlessLog.w(
                    "HeadlessRuntime",
                    "headless IME switch failed — text focus may crash Gboard (#1); non-text automation unaffected"
                )
            }
            sessions.add(session)
            HeadlessLog.event(
                sessionId = session.id,
                displayId = session.displayId,
                op = "HeadlessRuntime.createSession",
                success = true
            )
            return session
        }
    }

    private fun reapClosedSessions() {
        sessions.removeAll { !it.isOpen }
    }

    /**
     * #9 repair: a previous process died holding a session. Its display is gone but its
     * app may have reparented onto Display 0. Force-stop the orphan BEFORE creating
     * anything new, so the new client never inherits it.
     */
    private fun repairOrphan() {
        val l = ledger ?: return
        val orphan = try {
            l.orphanedPackage(janitor.liveDisplayIds())
        } catch (e: Throwable) {
            HeadlessLog.w("HeadlessRuntime", "orphan check failed", e)
            return
        } ?: return
        try {
            privilegeBackend.shell(arrayOf("am", "force-stop", orphan))
            HeadlessLog.i("HeadlessRuntime", "reaped orphan session app: $orphan")
        } catch (e: Throwable) {
            HeadlessLog.w("HeadlessRuntime", "orphan force-stop failed for $orphan", e)
        } finally {
            l.clear()
        }
    }

    /**
     * Closes every session this runtime owns (destroying their displays), then releases the
     * privilege backend. Always prefer this over letting the process exit with sessions
     * open — a display outliving its owner is the leak that caused IME thrashing on-device.
     */
    fun close() {
        if (closed) return
        closed = true

        val toClose = synchronized(lock) { sessions.toList() }
        for (session in toClose) {
            try {
                session.close()
            } catch (e: Throwable) {
                HeadlessLog.w("HeadlessRuntime", "failed closing session ${session.id}", e)
            }
        }
        synchronized(lock) { sessions.clear() }
        try { imeSwitcher?.restore() } catch (_: Throwable) {}

        // Final leak check: if any non-default display survived our own cleanup, say so
        // loudly rather than exiting quietly and leaving the user with a misbehaving
        // keyboard and resident apps on invisible displays.
        try {
            val report = janitor.inspect()
            if (report.hasLeakedDisplays) {
                HeadlessLog.e(
                    "HeadlessRuntime",
                    "LEAK: virtual displays still alive after runtime close: " +
                        report.nonDefaultDisplayIds.sorted().toString()
                )
            }
        } catch (e: Throwable) {
            HeadlessLog.w("HeadlessRuntime", "post-close leak check failed", e)
        }

        privilegeBackend.close()
    }
}
