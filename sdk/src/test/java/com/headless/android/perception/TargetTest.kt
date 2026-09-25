package com.headless.android.perception

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TargetTest {

    private val elementSearch = ScreenElement(
        id = "com.android.chrome:id/search_box",
        type = "edit_text",
        text = "Search or type URL",
        bounds = ElementBounds(100, 200, 980, 300),
        clickable = true,
        editable = true,
        confidence = 0.95f
    )

    private val elementSubmit = ScreenElement(
        id = "com.android.chrome:id/submit_button",
        type = "button",
        text = "Search",
        bounds = ElementBounds(400, 500, 680, 600),
        clickable = true,
        editable = false,
        confidence = 1.0f
    )

    private val observation = ScreenObservation(
        sourceScreenshot = null,
        elements = listOf(elementSearch, elementSubmit),
        analyzerName = "TestAnalyzer"
    )

    private val engine = PerceptionEngine()

    @Test
    fun testScreenElementCenterAndBounds() {
        assertEquals(540f, elementSearch.centerX, 0.01f)
        assertEquals(250f, elementSearch.centerY, 0.01f)

        assertEquals(540f, elementSubmit.centerX, 0.01f)
        assertEquals(550f, elementSubmit.centerY, 0.01f)
    }

    @Test
    fun testElementStaleness() {
        // Element created just now has age ~0
        assertFalse(elementSearch.isStale(maxAgeMs = 1000L))

        // Create an element with artificial timestamp in the past
        val staleElement = elementSearch.copy(
            detectedAtEpochMs = System.currentTimeMillis() - 5000L
        )
        assertTrue(staleElement.isStale(maxAgeMs = 3000L))
        assertTrue(staleElement.ageMs() >= 5000L)
    }

    @Test
    fun testResolveTextFuzzy() {
        val target = Target.Text("Search", exact = false)
        val matches = engine.resolveAll(target, observation)

        // Both "Search or type URL" and "Search" contain "Search"
        assertEquals(2, matches.size)
        assertEquals(elementSearch, matches[0])
        assertEquals(elementSubmit, matches[1])
    }

    @Test
    fun testResolveTextExact() {
        val target = Target.Text("Search", exact = true)
        val match = engine.resolve(target, observation)

        assertNotNull(match)
        assertEquals(elementSubmit, match)
    }

    @Test
    fun testResolveIdExactAndSuffix() {
        val targetExact = Target.Id("com.android.chrome:id/search_box")
        val matchExact = engine.resolve(targetExact, observation)
        assertNotNull(matchExact)
        assertEquals(elementSearch, matchExact)

        val targetSuffix = Target.Id("search_box")
        val matchSuffix = engine.resolve(targetSuffix, observation)
        assertNotNull(matchSuffix)
        assertEquals(elementSearch, matchSuffix)
    }

    @Test
    fun testResolvePoint() {
        val target = Target.Point(540f, 960f)
        val match = engine.resolve(target, observation)

        assertNotNull(match)
        assertEquals(540f, match!!.centerX, 0.01f)
        assertEquals(960f, match.centerY, 0.01f)
    }

    @Test
    fun testResolveRegion() {
        // Region overlapping elementSubmit (400..680, 500..600)
        val target = Target.Region(ElementBounds(450, 520, 600, 580))
        val match = engine.resolve(target, observation)

        assertNotNull(match)
        assertEquals(elementSubmit, match)
    }

    @Test
    fun testResolveNotFound() {
        val target = Target.Text("NonExistentText", exact = true)
        val match = engine.resolve(target, observation)
        assertNull(match)
    }
}
