package com.headless.android.observation

import com.headless.android.HeadlessLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Waits on observation conditions with a deadline, returning evidence either way.
 *
 * This replaces fixed sleeps. Fixed sleeps are wrong in both directions: too short and the
 * action is judged before the UI reacted (the audit measured legitimate reactions arriving
 * over a 1.3-2.4s spread), too long and every action pays worst-case cost. Waiting on a
 * condition returns as soon as it's true and reports honestly when it never becomes true.
 */
class WaitEngine(private val frameStream: FrameStream) {

    private companion object {
        const val OP = "WaitEngine"
    }

    /**
     * Collects the frame stream until [condition] holds or [timeoutMs] elapses.
     *
     * Never throws on timeout — a timeout is a legitimate, reportable outcome, not an
     * error. Callers decide whether it means failure (an expected element never appeared)
     * or uncertainty (an action with no visible effect).
     */
    suspend fun waitUntil(
        condition: Condition,
        timeoutMs: Long = 5_000L,
        description: String = "condition"
    ): WaitOutcome {
        val start = System.currentTimeMillis()
        var observed = 0
        var last: ScreenFrame? = null

        val met = withTimeoutOrNull(timeoutMs) {
            frameStream.frames().first { frame ->
                observed++
                last = frame
                condition.isMet(frame)
            }
        }

        val waited = System.currentTimeMillis() - start

        return if (met != null) {
            HeadlessLog.d(OP, "$description met after ${waited}ms over $observed frames")
            WaitOutcome.Met(frame = met, waitedMillis = waited, framesObserved = observed)
        } else {
            HeadlessLog.d(OP, "$description NOT met within ${timeoutMs}ms ($observed frames)")
            WaitOutcome.TimedOut(
                lastFrame = last,
                waitedMillis = waited,
                framesObserved = observed,
                lastChangeRatio = last?.changeRatio,
                description = description
            )
        }
    }

    /**
     * Waits for the screen to settle.
     *
     * Note this can legitimately time out on screens that never quiet down (video,
     * carousels, animated ads). That's why it reports rather than throws, and why
     * [StabilityPolicy] supports ignore-regions and a lenient preset.
     */
    suspend fun waitForStable(timeoutMs: Long = 5_000L): WaitOutcome =
        waitUntil(Condition.Stable, timeoutMs, "screen stable")

    /** Waits for any change beyond the policy threshold — e.g. confirming an action landed. */
    suspend fun waitForChange(timeoutMs: Long = 3_000L): WaitOutcome =
        waitUntil(Condition.Changed, timeoutMs, "screen changed")

    /**
     * Waits for a change, then for the screen to settle again — the usual shape after an
     * action: "something happened, and it's finished happening".
     *
     * Returns the stability outcome, or the change outcome if the change never came.
     */
    suspend fun waitForChangeThenStable(
        changeTimeoutMs: Long = 3_000L,
        stableTimeoutMs: Long = 5_000L
    ): WaitOutcome {
        val changed = waitForChange(changeTimeoutMs)
        if (!changed.met) return changed
        return waitForStable(stableTimeoutMs)
    }
}
