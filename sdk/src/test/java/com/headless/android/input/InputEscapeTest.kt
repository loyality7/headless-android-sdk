package com.headless.android.input

import org.junit.Assert.assertEquals
import org.junit.Test

class InputEscapeTest {
    @Test fun `spaces become percent-s`() {
        assertEquals("hello%sworld", InputController.escapeForInput("hello world"))
    }

    @Test fun `multi-word survives intact`() {
        assertEquals("RRR%smovie%strailer", InputController.escapeForInput("RRR movie trailer"))
    }

    @Test fun `no spaces unchanged`() {
        assertEquals("abc123", InputController.escapeForInput("abc123"))
    }

    @Test fun `empty stays empty`() {
        assertEquals("", InputController.escapeForInput(""))
    }

    @Test fun `unicode untouched except spaces`() {
        assertEquals("TATA%scar", InputController.escapeForInput("TATA car"))
    }
}
