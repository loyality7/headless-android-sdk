package com.headless.android.perception

import android.graphics.Rect

/**
 * Platform-independent bounding box for perceived screen elements.
 * Avoids depending on android.graphics.Rect stubs during host testing and decouples
 * perception representation from platform graphics internals.
 */
data class ElementBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    val centerX: Float get() = (left + right) / 2.0f
    val centerY: Float get() = (top + bottom) / 2.0f
    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun toRect(): Rect = Rect(left, top, right, bottom)

    fun intersects(other: ElementBounds): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom

    companion object {
        fun fromRect(rect: Rect): ElementBounds =
            ElementBounds(rect.left, rect.top, rect.right, rect.bottom)
    }
}

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
    val bounds: ElementBounds,
    val clickable: Boolean,
    val editable: Boolean,
    val confidence: Float,
    val detectedAtEpochMs: Long = System.currentTimeMillis()
) {
    constructor(
        id: String,
        type: String,
        text: String?,
        rect: Rect,
        clickable: Boolean,
        editable: Boolean,
        confidence: Float,
        detectedAtEpochMs: Long = System.currentTimeMillis()
    ) : this(
        id = id,
        type = type,
        text = text,
        bounds = ElementBounds.fromRect(rect),
        clickable = clickable,
        editable = editable,
        confidence = confidence,
        detectedAtEpochMs = detectedAtEpochMs
    )

    val centerX: Float get() = bounds.centerX
    val centerY: Float get() = bounds.centerY

    fun ageMs(): Long = System.currentTimeMillis() - detectedAtEpochMs
    fun isStale(maxAgeMs: Long): Boolean = ageMs() > maxAgeMs
}
