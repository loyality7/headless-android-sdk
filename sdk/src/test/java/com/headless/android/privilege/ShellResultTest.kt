package com.headless.android.privilege

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellResultTest {

    @Test
    fun `uiautomator idle failure on stdout with exit 0 is not success`() {
        // Real output: exits 0 but prints this and writes no file.
        val r = ShellResult(0, "ERROR: could not get idle state.\n", "")
        assertTrue(r.looksLikeError)
        assertFalse(r.isSuccess)
    }

    @Test
    fun `ERROR text inside normal output is not treated as failure`() {
        val r = ShellResult(0, "  mLastError: ERROR: something old\nDisplay #0\n", "")
        assertFalse(r.looksLikeError)
        assertTrue(r.isSuccess)
    }
}
