package com.headless.android.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ElementBoundsTest {
    @Test fun `center math`() {
        val b = ElementBounds(100, 200, 300, 400)
        assertEquals(200f, b.centerX, 0.001f)
        assertEquals(300f, b.centerY, 0.001f)
        assertEquals(200, b.width)
        assertEquals(200, b.height)
    }

    @Test fun `overlap intersects`() {
        assertTrue(ElementBounds(0, 0, 100, 100).intersects(ElementBounds(50, 50, 150, 150)))
    }

    @Test fun `touching edges do not intersect`() {
        // Strict inequality: sharing an edge = adjacent, not overlapping. A tap on the
        // boundary must not resolve to both elements.
        assertFalse(ElementBounds(0, 0, 100, 100).intersects(ElementBounds(100, 0, 200, 100)))
        assertFalse(ElementBounds(0, 0, 100, 100).intersects(ElementBounds(0, 100, 100, 200)))
    }

    @Test fun `contained intersects`() {
        assertTrue(ElementBounds(0, 0, 200, 200).intersects(ElementBounds(50, 50, 100, 100)))
    }

    @Test fun `disjoint does not intersect`() {
        assertFalse(ElementBounds(0, 0, 50, 50).intersects(ElementBounds(100, 100, 150, 150)))
    }

    @Test fun `zero-area point inside matches, outside does not`() {
        // A degenerate tap-point inside a region must resolve to it; outside must not.
        assertTrue(ElementBounds(50, 50, 50, 50).intersects(ElementBounds(0, 0, 100, 100)))
        assertFalse(ElementBounds(500, 500, 500, 500).intersects(ElementBounds(0, 0, 100, 100)))
    }
}
