package com.headless.android.observation

import com.headless.android.capture.Screenshot

/** How settled the screen appears, judged purely on pixels. */
enum class ScreenStability {
    /** Not enough consecutive frames observed yet to judge. */
    UNKNOWN,

    /** Pixels are changing beyond the configured threshold. */
    CHANGING,

    /** Change has stayed under the threshold for the required duration. */
    STABLE
}

/**
 * One frame off the observation stream, annotated with how it relates to the frame
 * before it.
 *
 * [changeRatio] is null for the first frame in a stream (nothing to compare against).
 * Everything here is model-free: it describes pixel motion, never meaning.
 */
data class ScreenFrame(
    val screenshot: Screenshot,
    val sequence: Long,
    val timestampMillis: Long,
    val changeRatio: Float?,
    val stability: ScreenStability
)
