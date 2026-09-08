package com.headless.android.observation

import android.graphics.Rect

/**
 * Defines what "the screen has settled" means for a given wait.
 *
 * Perfect stability is not a reachable state on real screens — blinking cursors,
 * clocks, spinners, autoplaying video, and animated ads all keep pixels moving
 * indefinitely. So stability is always a *threshold over a duration*, optionally
 * ignoring regions known to animate, never an equality test.
 */
data class StabilityPolicy(
    /**
     * Maximum per-frame [FrameDiff.Result.changeRatio] still considered "quiet".
     * 0.005 (0.5% of sampled pixels) tolerates a blinking cursor and small spinners
     * while still catching real content changes.
     */
    val changeThreshold: Float = 0.005f,

    /** How long change must stay under [changeThreshold] before declaring STABLE. */
    val quietDurationMillis: Long = 400L,

    /** Screen regions excluded from comparison (status bar clock, video surface, ads). */
    val ignoreRegions: List<Rect> = emptyList(),

    /** Sampling stride passed to [FrameDiff]; higher is faster and coarser. */
    val sampleStep: Int = 4,

    /** Per-channel tolerance before a pixel counts as changed (absorbs compression noise). */
    val tolerancePerChannel: Int = 8
) {
    companion object {
        /** Tolerant default suitable for web content with cursors and spinners. */
        val DEFAULT = StabilityPolicy()

        /** Stricter: for simple, static native UI where little should be moving. */
        val STRICT = StabilityPolicy(changeThreshold = 0.001f, quietDurationMillis = 600L)

        /**
         * Lenient: for screens with persistent motion (video, carousels) where waiting
         * for real quiet would time out. Larger threshold, shorter quiet window.
         */
        val LENIENT = StabilityPolicy(changeThreshold = 0.05f, quietDurationMillis = 300L)
    }
}
