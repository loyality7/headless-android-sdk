package com.headless.android.perception

import android.graphics.Rect

/**
 * One perceived element on screen. Field set is intentionally analyzer-agnostic — an
 * OCR backend, an Accessibility backend, or a vision-LLM backend can all populate this
 * the same shape, with [confidence] distinguishing certain (Accessibility) from inferred
 * (OCR/vision) results.
 */
data class ScreenElement(
    val id: String,
    val type: String,
    val text: String?,
    val bounds: Rect,
    val clickable: Boolean,
    val editable: Boolean,
    val confidence: Float
)
