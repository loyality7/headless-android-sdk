package com.headless.android.perception

import android.graphics.Rect

/**
 * Target definition for semantic interaction.
 *
 * Eliminates fragile hardcoded (x, y) coordinates by allowing automation callers
 * (local LLMs, test runners, or agents) to target elements by text, resource ID,
 * perceived element, or bounding regions.
 */
sealed class Target {
    abstract val description: String

    /** Matches an element whose visible text contains or equals [query]. */
    data class Text(
        val query: String,
        val exact: Boolean = false,
        val ignoreCase: Boolean = true
    ) : Target() {
        override val description: String get() = "Text('$query', exact=$exact)"
    }

    /** Matches an element with a specific semantic or resource ID. */
    data class Id(val id: String) : Target() {
        override val description: String get() = "Id('$id')"
    }

    /** Direct target reference to an existing perceived element. */
    data class Element(val element: ScreenElement) : Target() {
        override val description: String get() = "Element('${element.id}', text='${element.text}')"
    }

    /** Matches an area whose center will be targeted. */
    data class Region(val bounds: ElementBounds) : Target() {
        constructor(rect: Rect) : this(ElementBounds.fromRect(rect))
        constructor(left: Int, top: Int, right: Int, bottom: Int) : this(ElementBounds(left, top, right, bottom))
        override val description: String get() = "Region([${bounds.left},${bounds.top}][${bounds.right},${bounds.bottom}])"
    }

    /**
     * Explicit coordinate point (x, y). Validated by DisplayIsolationGuard
     * to guarantee it stays safely within the target display bounds and away from edge gestures.
     */
    data class Point(val x: Float, val y: Float) : Target() {
        override val description: String get() = "Point($x, $y)"
    }
}
