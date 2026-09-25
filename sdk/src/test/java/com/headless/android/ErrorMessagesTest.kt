package com.headless.android

import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorMessagesTest {
    @Test fun `shizuku default names the bind refusal, not a dead server`() {
        val msg = ShizukuUnavailableException().message ?: ""
        assertTrue(msg.contains("process is bad"))
        assertTrue(msg.contains("uninstall"))
    }

    @Test fun `launch failure names the package`() {
        val e = AppLaunchException("com.android.chrome", "Binder transact returned false")
        assertTrue((e.message ?: "").contains("com.android.chrome"))
    }

    @Test fun `target errors carry ages and descriptions`() {
        val stale = TargetStalenessException("Search box", 5000L, 3000L)
        assertTrue((stale.message ?: "").contains("5000ms"))
        val missing = TargetNotFoundException("Sign in")
        assertTrue((missing.message ?: "").contains("Sign in"))
    }
}
