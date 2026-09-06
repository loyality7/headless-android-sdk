package com.headless.android.capture

import android.graphics.Bitmap

/**
 * An immutable captured frame. Deliberately dumb — no OCR, no perception, no AI.
 * [ScreenAnalyzer] implementations (perception package) consume this as raw input.
 */
class Screenshot(
    val width: Int,
    val height: Int,
    val displayId: Int,
    val timestampNanos: Long,
    val bitmap: Bitmap
)
