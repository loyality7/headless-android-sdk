package com.headless.android

import org.junit.Assert.assertEquals
import org.junit.Test

class BinderCodesTest {
    @Test fun `unknown stub falls back loudly`() {
        assertEquals(21, BinderCodes.stubCode("no.such.Class", "TRANSACTION_x", 21))
        assertEquals(1, BinderCodes.stubCode("no.such.Class", "TRANSACTION_y", 1))
    }

    @Test fun `wrong field name on real class falls back`() {
        // java.lang.String exists on JVM but has no such field — exact-name miss, no fuzzy.
        assertEquals(24, BinderCodes.stubCode("java.lang.String", "TRANSACTION_nope", 24))
    }

    @Test fun `production entry points fall back on JVM classpath`() {
        // Framework Stub classes don't exist in unit tests — must return verified fallbacks.
        assertEquals(21, BinderCodes.displayCreate())
        assertEquals(24, BinderCodes.displayRelease())
        assertEquals(1, BinderCodes.atmStartActivity())
    }
}
