package com.headless.android

import com.headless.android.apps.AppLauncher
import com.headless.android.capture.FrameCapture
import com.headless.android.capture.Screenshot
import com.headless.android.display.HeadlessDisplay
import com.headless.android.display.VirtualDisplayManager
import com.headless.android.input.InputController
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.state.SessionState
import com.headless.android.state.StateEngine
import java.util.UUID

/**
 * One isolated headless Android environment: a hidden trusted virtual display plus the
 * app running on it. Owns every resource (display, ImageReader, input) created for it —
 * [close] releases all of them. A closed session throws [SessionClosedException] on any
 * further call.
 */
class HeadlessSession internal constructor(
    private val privilegeBackend: PrivilegeBackend,
    displayWidth: Int,
    displayHeight: Int,
    displayDensityDpi: Int,
    /** Invoked once when this session finishes closing, so the runtime can stop tracking it. */
    private val onClosed: (HeadlessSession) -> Unit = {}
) {
    val id: String = UUID.randomUUID().toString()

    private val displayManager = VirtualDisplayManager(privilegeBackend)

    private val display: HeadlessDisplay =
        displayManager.createTrustedDisplay(displayWidth, displayHeight, displayDensityDpi)

    val displayId: Int get() = display.displayId

    private val appLauncher = AppLauncher(privilegeBackend)
    private val inputController = InputController(privilegeBackend, display.displayId)
    private val frameCapture = FrameCapture(display.imageReader, display.displayId)
    private val stateEngine = StateEngine(privilegeBackend)

    /** Package launched through this session, tracked so [close] can stop it. */
    @Volatile
    private var launchedPackage: String? = null

    @Volatile
    private var closed = false

    val isOpen: Boolean get() = !closed

    /** Live snapshot of this session's state, queried from the platform. */
    fun state(): SessionState = stateEngine.sessionState(id, display.displayId, !closed)

    fun launch(packageName: String) {
        checkOpen()
        appLauncher.launch(packageName, display.displayId)
        launchedPackage = packageName
        inputAllowed = true
    }

    /**
     * Whether input may be injected into this session's display.
     *
     * False until a launch has been *verified* on this display. Injecting before that is
     * the failure that typed into the user's real Chrome on display 0: a launch was
     * reported successful while the app had actually been placed on the physical display,
     * and every subsequent tap/type went to the real screen.
     */
    @Volatile
    private var inputAllowed = false

    /**
     * Refuses input unless the launched app is still provably on THIS display.
     *
     * Re-checks the platform on every call rather than trusting the launch-time result:
     * an app can be moved or redirected after launch, and a stale "it was fine earlier"
     * belief is exactly what makes injection unsafe.
     */
    private fun checkInputAllowed(action: String) {
        checkOpen()
        val pkg = launchedPackage
            ?: throw InputInjectionException(
                "Refusing $action: no app has been launched in this session, so the target " +
                    "display's contents are unverified. Call launch() first."
            )
        check(inputAllowed) {
            "Refusing $action: launch was never verified for this session."
        }
        val hosting = appLauncher.displayIdsHosting(pkg)
        if (!hosting.contains(display.displayId)) {
            inputAllowed = false
            throw InputInjectionException(
                "Refusing $action: '$pkg' is not on this session's display ${display.displayId} " +
                    "(currently on ${hosting.sorted().ifEmpty { "no display" }}). Injecting now could " +
                    "deliver input to the user's physical screen."
            )
        }
    }

    /** Force-stops [packageName]. Returns true if the stop command completed cleanly. */
    fun stopApp(packageName: String): Boolean {
        checkOpen()
        return appLauncher.stop(packageName)
    }

    /** Package name of the top resumed activity on this session's display, or null if none. */
    fun currentApp(): String? {
        checkOpen()
        return appLauncher.currentPackageOnDisplay(display.displayId)
    }

    /** True if [packageName] currently has a task on this session's display. */
    fun isAppOnDisplay(packageName: String): Boolean {
        checkOpen()
        return appLauncher.isOnDisplay(packageName, display.displayId)
    }

    fun tap(x: Float, y: Float) {
        checkInputAllowed("tap")
        inputController.tap(x, y)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        checkInputAllowed("swipe")
        inputController.swipe(x1, y1, x2, y2, durationMs)
    }

    fun type(text: String) {
        checkInputAllowed("type")
        inputController.type(text)
    }

    fun pressEnter() {
        checkInputAllowed("pressEnter")
        inputController.pressEnter()
    }

    fun pressBack() {
        checkInputAllowed("pressBack")
        inputController.pressBack()
    }

    fun screenshot(): Screenshot {
        checkOpen()
        return frameCapture.capture()
    }

    /**
     * Stops the app launched on this display, destroys the virtual display, and closes the
     * capture surface. Idempotent.
     *
     * The app is stopped *before* the display goes away: a process whose display is
     * destroyed underneath it can be left in a bad state, and on this device orphaned
     * apps on stale displays kept requesting IME focus on the physical screen.
     *
     * Display release is attempted even if stopping the app fails, and the session is
     * always marked closed and deregistered — a failure here must never leave the runtime
     * believing it still owns a session it cannot clean up.
     */
    fun close() {
        if (closed) return
        closed = true

        var failure: Throwable? = null

        launchedPackage?.let { pkg ->
            try {
                appLauncher.stop(pkg)
            } catch (e: Throwable) {
                HeadlessLog.w("HeadlessSession", "failed stopping $pkg during close", e)
            }
        }

        try {
            display.release()
        } catch (e: Throwable) {
            failure = e
            HeadlessLog.e("HeadlessSession", "failed releasing display ${display.displayId}", e)
        }

        HeadlessLog.event(
            sessionId = id,
            displayId = display.displayId,
            op = "HeadlessSession.close",
            success = failure == null
        )

        onClosed(this)

        failure?.let { throw it }
    }

    private fun checkOpen() {
        if (closed) throw SessionClosedException()
    }
}
