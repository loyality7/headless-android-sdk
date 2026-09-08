package com.headless.android.observation

import com.headless.android.capture.FrameCapture
import com.headless.android.capture.Screenshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Turns discrete frame captures into a continuous, annotated observation stream.
 *
 * Each emitted [ScreenFrame] carries how it differs from its predecessor and whether the
 * screen has settled, so callers can wait on *actual* conditions instead of guessing with
 * fixed sleeps. Measured capture cost on-device was 13-41ms per frame, which is what makes
 * polling at this granularity viable.
 *
 * The stream is cold and driven by the collector: nothing is captured until collected, and
 * collection stops the moment the collector stops. That matters because each frame holds a
 * bitmap — an unbounded hot stream would be a memory problem, not a convenience.
 */
class FrameStream(
    private val frameCapture: FrameCapture,
    private val policy: StabilityPolicy = StabilityPolicy.DEFAULT,
    /** Minimum gap between capture attempts. Capture itself costs ~15-40ms. */
    private val intervalMs: Long = 100L
) {

    /**
     * Emits annotated frames until the collector stops.
     *
     * Frames that fail to capture are skipped rather than terminating the stream: capture
     * was observed to intermittently return nothing (an `acquireLatestImage` returning
     * null when no new frame has been composited), and a transient miss is not a reason to
     * tear down an observation.
     */
    fun frames(): Flow<ScreenFrame> = flow {
        var sequence = 0L
        var previous: Screenshot? = null
        var quietSinceMillis: Long? = null

        while (true) {
            val shot = try {
                frameCapture.capture()
            } catch (e: Throwable) {
                null
            }

            if (shot != null) {
                val now = System.currentTimeMillis()
                val ratio = previous?.let {
                    FrameDiff.compare(
                        a = it.bitmap,
                        b = shot.bitmap,
                        ignoreRegions = policy.ignoreRegions,
                        sampleStep = policy.sampleStep,
                        tolerancePerChannel = policy.tolerancePerChannel
                    ).changeRatio
                }

                val stability = when {
                    ratio == null -> ScreenStability.UNKNOWN
                    ratio > policy.changeThreshold -> {
                        quietSinceMillis = null
                        ScreenStability.CHANGING
                    }
                    else -> {
                        val since = quietSinceMillis ?: now.also { quietSinceMillis = it }
                        if (now - since >= policy.quietDurationMillis) ScreenStability.STABLE
                        else ScreenStability.CHANGING
                    }
                }

                emit(
                    ScreenFrame(
                        screenshot = shot,
                        sequence = sequence++,
                        timestampMillis = now,
                        changeRatio = ratio,
                        stability = stability
                    )
                )
                previous = shot
            }

            delay(intervalMs)
        }
    }
}
