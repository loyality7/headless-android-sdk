package com.headless.android

import android.net.Uri
import com.headless.android.apps.AppLauncher
import com.headless.android.capture.FrameCapture
import com.headless.android.capture.Screenshot
import com.headless.android.display.HeadlessDisplay
import com.headless.android.display.ImeIsolation
import com.headless.android.display.VirtualDisplayManager
import com.headless.android.input.DisplayIsolationGuard
import com.headless.android.input.InputController
import com.headless.android.observation.Condition
import com.headless.android.observation.FrameStream
import com.headless.android.observation.ScreenFrame
import com.headless.android.observation.StabilityPolicy
import com.headless.android.observation.WaitEngine
import com.headless.android.observation.WaitOutcome
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import com.headless.android.perception.PerceptionEngine
import com.headless.android.perception.ScreenAnalyzer
import com.headless.android.perception.ScreenElement
import com.headless.android.perception.ScreenObservation
import com.headless.android.perception.Target
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
    val analyzer: ScreenAnalyzer = PerceptionEngine(),
    private val ledger: com.headless.android.state.SessionLedger? = null,
    private val onEvent: (SessionEvent) -> Unit = {},
    /**
     * Runs at the START of [close], before the app is stopped and the display released.
     * Used to restore the user's keyboard while the display still exists: releasing a
     * display while a custom IME is still default crashed the process (system binds the
     * IME for the dying display with a null display token).
     */
    private val onClosing: () -> Unit = {},
    /** Invoked once when this session finishes closing, so the runtime can stop tracking it. */
    private val onClosed: (HeadlessSession) -> Unit = {}
) {
    val id: String = UUID.randomUUID().toString()

    private val _events = MutableSharedFlow<SessionEvent>(
        replay = 16,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /** Real-time event stream for this session. */
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    internal fun emitEvent(event: SessionEvent) {
        _events.tryEmit(event)
        try {
            onEvent(event)
        } catch (e: Throwable) {
            HeadlessLog.w("HeadlessSession", "onEvent callback threw", e)
        }
    }

    private val displayManager = VirtualDisplayManager(privilegeBackend)

    private val display: HeadlessDisplay =
        displayManager.createTrustedDisplay(displayWidth, displayHeight, displayDensityDpi)

    val displayId: Int get() = display.displayId

    private val deadListener: () -> Unit = {
        emitEvent(
            SessionEvent.BackendLost(
                sessionId = id,
                backendName = privilegeBackend.name,
                reason = "Privilege backend service binder died unexpectedly"
            )
        )
    }

    init {
        privilegeBackend.addOnDeadListener(deadListener)
        emitEvent(
            SessionEvent.DisplayCreated(
                sessionId = id,
                displayId = display.displayId,
                width = displayWidth,
                height = displayHeight,
                densityDpi = displayDensityDpi
            )
        )
    }

    val isolationGuard = DisplayIsolationGuard(
        privilegeBackend = privilegeBackend,
        displayId = display.displayId,
        displayWidth = displayWidth,
        displayHeight = displayHeight
    )

    private val appLauncher = AppLauncher(privilegeBackend)
    private val inputController = InputController(privilegeBackend, display.displayId, isolationGuard)
    private val frameCapture = FrameCapture(display.imageReader, display.displayId)
    private val stateEngine = StateEngine(privilegeBackend)

    /**
     * Keeps the IME's window on the default display instead of this one.
     *
     * Runs at construction, before any app can take text focus. LOCAL was tried first (IME
     * confined to this display) but Android refuses to host an IME window on a non-system-owned
     * virtual display (source.android.com/docs/core/display/multi_display/ime-support) and
     * crashes WindowProviderService.createServiceBaseContext with a null Display when IMMS
     * rebinds the IME there on focus. FALLBACK_DISPLAY avoids that rebind entirely — the IME's
     * window stays on display 0, but since HeadlessImeService has no UI (onCreateInputView
     * returns null) nothing is actually visible there, and its InputConnection still targets
     * the focused editor on THIS display regardless of where the IME window lives.
     */
    val imeIsolation: ImeIsolation.Report =
        ImeIsolation(privilegeBackend).isolate(display.displayId, ImeIsolation.POLICY_FALLBACK_DISPLAY)

    /** Package launched through this session, tracked so [close] can stop it. */
    @Volatile
    private var launchedPackage: String? = null

    @Volatile
    private var closed = false

    private val closeLock = Any()

    val isOpen: Boolean get() = !closed

    private var lastState: SessionState? = null

    /** Live snapshot of this session's state, queried from the platform. */
    fun state(): SessionState {
        val s = stateEngine.sessionState(id, display.displayId, !closed)
        if (s != lastState) {
            emitEvent(SessionEvent.StateChanged(id, s))
            lastState = s
        }
        return s
    }

    private inline fun <T> trackAction(actionName: String, block: () -> T): T {
        emitEvent(SessionEvent.ActionStarted(id, actionName, display.displayId))
        val start = System.currentTimeMillis()
        return try {
            val result = block()
            emitEvent(
                SessionEvent.ActionCompleted(
                    sessionId = id,
                    action = actionName,
                    displayId = display.displayId,
                    success = true,
                    durationMs = System.currentTimeMillis() - start,
                    outcome = "success"
                )
            )
            result
        } catch (e: Throwable) {
            if (e is DisplayIsolationViolationException) {
                emitEvent(
                    SessionEvent.IsolationViolation(
                        sessionId = id,
                        action = actionName,
                        reason = e.message ?: "Display isolation violation",
                        displayId = display.displayId
                    )
                )
            }
            emitEvent(
                SessionEvent.ActionCompleted(
                    sessionId = id,
                    action = actionName,
                    displayId = display.displayId,
                    success = false,
                    durationMs = System.currentTimeMillis() - start,
                    error = e.message ?: e.javaClass.simpleName,
                    outcome = "failed"
                )
            )
            throw e
        }
    }

    fun launch(packageName: String, uri: Uri? = null) {
        checkOpen()
        trackAction("launch($packageName)") {
            appLauncher.launch(packageName, display.displayId, uri)
            launchedPackage = packageName
            inputAllowed = true
            ledger?.record(id, display.displayId, packageName)
            emitEvent(SessionEvent.AppLaunched(id, packageName, display.displayId))
        }
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
        if (!inputAllowed) {
            throw InputInjectionException("Refusing $action: launch was never verified for this session.")
        }
        val hosting = appLauncher.displayIdsHosting(pkg)
        if (!hosting.contains(display.displayId)) {
            inputAllowed = false
            emitEvent(
                SessionEvent.AppCrashed(
                    sessionId = id,
                    packageName = pkg,
                    displayId = display.displayId,
                    reason = "App is no longer hosted on display ${display.displayId} (found on ${hosting.sorted().ifEmpty { "no display" }})"
                )
            )
            throw InputInjectionException(
                "Refusing $action: '$pkg' is not on this session's display ${display.displayId} " +
                    "(currently on ${hosting.sorted().ifEmpty { "no display" }}). Injecting now could " +
                    "deliver input to the user's physical screen."
            )
        }
        if (action in listOf("type", "pressEnter", "pressBack", "pressTab", "deleteText", "clearText")) {
            isolationGuard.validateKeyInjection(action, pkg)
        }
    }

    /** Probes Display 0 to verify it has not been contaminated (e.g. keyboard showing on Display 0). */
    fun checkDisplayZero(): DisplayIsolationGuard.DisplayZeroStatus {
        checkOpen()
        val status = isolationGuard.probeDisplayZero()
        if (status.isContaminated || status.imeShowingOnDisplayZero) {
            emitEvent(
                SessionEvent.ContaminationAlert(
                    sessionId = id,
                    detail = status.detail,
                    displayZeroPackage = status.topActivityOnDisplayZero
                )
            )
        }
        return status
    }

    /** Force-stops [packageName]. Returns true if the stop command completed cleanly. */
    fun stopApp(packageName: String): Boolean {
        checkOpen()
        return trackAction("stopApp($packageName)") {
            ledger?.clear()
            val stopped = appLauncher.stop(packageName)
            if (stopped) {
                emitEvent(SessionEvent.AppStopped(id, packageName, display.displayId))
            }
            stopped
        }
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
        trackAction("tap($x, $y)") {
            inputController.tap(x, y)
        }
    }

    /** Analyzes the current screen and returns a structured [ScreenObservation]. */
    suspend fun observe(): ScreenObservation {
        checkOpen()
        val shot = screenshot()
        return analyzer.analyze(shot)
    }

    /** Finds the first perceived element matching [target], or null if not found. */
    suspend fun find(target: Target): ScreenElement? {
        val obs = observe()
        return (analyzer as? PerceptionEngine)?.resolve(target, obs)
    }

    /** Finds all perceived elements matching [target]. */
    suspend fun findAll(target: Target): List<ScreenElement> {
        val obs = observe()
        return (analyzer as? PerceptionEngine)?.resolveAll(target, obs) ?: emptyList()
    }

    /**
     * Taps a semantic [Target].
     *
     * If [target] is a [Target.Element], checks staleness: if older than [maxStalenessMs],
     * throws [TargetStalenessException].
     * If [target] is a query ([Target.Text], [Target.Id], [Target.Region]), searches the
     * live screen via [observe] and taps the matching element's center.
     * Throws [TargetNotFoundException] if no matching element exists.
     */
    suspend fun tap(target: Target, maxStalenessMs: Long = 3000L) {
        checkInputAllowed("tap(target)")
        val element: ScreenElement = when (target) {
            is Target.Point -> {
                tap(target.x, target.y)
                return
            }
            is Target.Element -> {
                val age = target.element.ageMs()
                if (age > maxStalenessMs) {
                    throw TargetStalenessException(
                        targetDescription = target.description,
                        ageMs = age,
                        maxAgeMs = maxStalenessMs
                    )
                }
                target.element
            }
            else -> {
                find(target) ?: throw TargetNotFoundException(target.description)
            }
        }

        tap(element.centerX, element.centerY)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        checkInputAllowed("swipe")
        trackAction("swipe($x1, $y1 -> $x2, $y2)") {
            inputController.swipe(x1, y1, x2, y2, durationMs)
        }
    }

    fun type(text: String) {
        checkInputAllowed("type")
        trackAction("type") {
            inputController.type(text)
        }
    }

    fun pressEnter() {
        checkInputAllowed("pressEnter")
        trackAction("pressEnter") {
            inputController.pressEnter()
        }
    }

    fun pressBack() {
        checkInputAllowed("pressBack")
        trackAction("pressBack") {
            inputController.pressBack()
        }
    }

    fun pressTab() {
        checkInputAllowed("pressTab")
        trackAction("pressTab") {
            inputController.pressTab()
        }
    }

    /** Deletes [count] characters backwards from the cursor. */
    fun deleteText(count: Int) {
        checkInputAllowed("deleteText")
        trackAction("deleteText($count)") {
            inputController.deleteText(count)
        }
    }

    /** Clears the focused field (select-all then delete). */
    fun clearText() {
        checkInputAllowed("clearText")
        trackAction("clearText") {
            inputController.clearText()
        }
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
        // #32: double-close from two threads must not double-stop/double-release.
        synchronized(closeLock) {
        if (closed) return
        closed = true
        emitEvent(SessionEvent.SessionClosing(id, display.displayId))
        try {
            privilegeBackend.removeOnDeadListener(deadListener)
        } catch (_: Throwable) {}

        try { onClosing() } catch (_: Throwable) {}
        ledger?.clear()

        var failure: Throwable? = null

        launchedPackage?.let { pkg ->
            try {
                appLauncher.stop(pkg)
                emitEvent(SessionEvent.AppStopped(id, pkg, display.displayId))
                // Small grace period to allow WindowManager to finalize window cleanup
                Thread.sleep(200)
            } catch (e: Throwable) {
                HeadlessLog.w("HeadlessSession", "failed stopping $pkg during close", e)
            }
        }

        try {
            frameCapture.close()
        } catch (e: Throwable) {
            HeadlessLog.w("HeadlessSession", "failed closing frameCapture", e)
        }

        try {
            display.release()
            emitEvent(SessionEvent.DisplayReleased(id, display.displayId))
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

        emitEvent(SessionEvent.SessionClosed(id, display.displayId))

        onClosed(this)

        failure?.let { throw it }
        }
    }

    private fun checkOpen() {
        if (closed) throw SessionClosedException()
    }
}
