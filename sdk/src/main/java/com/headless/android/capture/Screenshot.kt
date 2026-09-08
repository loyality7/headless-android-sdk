package com.headless.android.capture

import android.graphics.Bitmap

/**
 * An immutable captured frame. Deliberately dumb — no OCR, no perception, no AI.
 * [com.headless.android.perception.ScreenAnalyzer] implementations consume this as raw input.
 */
data class Screenshot(
    val width: Int,
    val height: Int,
    val displayId: Int,
    val timestampNanos: Long,
    val bitmap: Bitmap,
    /**
     * True if this frame was newly composited by the display; false if it is a repeat of
     * the last captured frame, returned because the display had nothing new (an idle
     * screen composites no frames).
     *
     * Change detection must account for this: comparing a cached frame against itself
     * yields "no change", which is a statement about the *capture*, not about the UI.
     */
    val isFresh: Boolean = true
)
