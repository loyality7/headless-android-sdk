package com.headless.android

import com.headless.android.apps.AppLauncher
import com.headless.android.capture.FrameCapture
import com.headless.android.capture.Screenshot
import com.headless.android.display.HeadlessDisplay
import com.headless.android.display.ImeIsolation
import com.headless.android.display.VirtualDisplayManager
import com.headless.android.input.InputController
import com.headless.android.observation.Condition
import com.headless.android.observation.FrameStream
import com.headless.android.observation.ScreenFrame
import com.headless.android.observation.StabilityPolicy
import com.headless.android.observation.WaitEngine
import com.headless.android.observation.WaitOutcome
import kotlinx.coroutines.flow.Flow
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

    /**
     * Attempt to prevent this display from ever showing a soft keyboard.
     *
     * Runs at construction, before any app can take text focus. The default secondary-display
     * policy (FALLBACK_DISPLAY) puts the keyboard on the USER'S physical display, which was
     * observed happening during automation and is a hard isolation violation.
     */
    val imeIsolation: ImeIsolation.Report =
        ImeIsolation(privilegeBackend).isolate(display.displayId)

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

    fun pressTab() {
        checkInputAllowed("pressTab")
        inputController.pressTab()
    }

    /** Deletes [count] characters backwards from the cursor. */
    fun deleteText(count: Int) {
        checkInputAllowed("deleteText")
        inputController.deleteText(count)
    }

    /** Clears the focused field (select-all then delete). */
    fun clearText() {
        checkInputAllowed("clearText")
        inputController.clearText()
    }

    fun screenshot(): Screenshot {
        checkOpen()
        return frameCapture.capture()
    }

    // ── Observation ────────────────────────────────────────────────────────────

    /**
     * Continuous annotated frame stream for this display: each frame carries its pixel
     * change ratio versus the previous frame and whether the screen has settled.
     *
     * Cold and collector-driven — nothing is captured until collected, and capture stops
     * when collection stops. Each frame holds a bitmap, so don't retain them.
     */
    fun frames(policy: StabilityPolicy = StabilityPolicy.DEFAULT, intervalMs: Long = 100L): Flow<ScreenFrame> {
        checkOpen()
        return FrameStream(frameCapture, policy, intervalMs).frames()
    }

    /**
     * Waits until [condition] holds, or the deadline passes.
     *
     * Prefer this over sleeping: it returns as soon as the condition is true, and a
     * timeout comes back as reportable evidence rather than silently proceeding on a
     * guess. Timeouts are outcomes, not exceptions.
     */
    suspend fun waitUntil(
        condition: Condition,
        timeoutMs: Long = 5_000L,
        description: String = "condition",
        policy: StabilityPolicy = StabilityPolicy.DEFAULT
    ): WaitOutcome {
        checkOpen()
        return WaitEngine(FrameStream(frameCapture, policy)).waitUntil(condition, timeoutMs, description)
    }

    /** Waits for the screen to stop changing. May legitimately time out on animated screens. */
    suspend fun waitForStable(
        timeoutMs: Long = 5_000L,
        policy: StabilityPolicy = StabilityPolicy.DEFAULT
    ): WaitOutcome {
        checkOpen()
        return WaitEngine(FrameStream(frameCapture, policy)).waitForStable(timeoutMs)
    }

    /** Waits for any pixel change beyond the policy threshold. */
    suspend fun waitForChange(
        timeoutMs: Long = 3_000L,
        policy: StabilityPolicy = StabilityPolicy.DEFAULT
    ): WaitOutcome {
        checkOpen()
        return WaitEngine(FrameStream(frameCapture, policy)).waitForChange(timeoutMs)
    }

    /** Waits for a change, then for the screen to settle — the usual post-action shape. */
    suspend fun waitForChangeThenStable(
        changeTimeoutMs: Long = 3_000L,
        stableTimeoutMs: Long = 5_000L,
        policy: StabilityPolicy = StabilityPolicy.DEFAULT
    ): WaitOutcome {
        checkOpen()
        return WaitEngine(FrameStream(frameCapture, policy))
            .waitForChangeThenStable(changeTimeoutMs, stableTimeoutMs)
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
