package com.headless.android.command

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransactionTest {
    private fun failed(reason: String) =
        CommandResult.Failed(AutomationCommand.PressBack, reason, null, null, 10L)
    private fun uncertain(pkg: String? = null) =
        CommandResult.Uncertain(AutomationCommand.PressBack, "no change", null, pkg, 0f, 10L)
    private fun verified(pkg: String? = null) =
        CommandResult.Verified(AutomationCommand.PressBack, "ok", null, pkg, 0.1f, 10L)

    @Test fun `retryable failures retry, safety ones do not`() {
        val p = RetryPolicy(maxAttempts = 3)
        assertTrue(Transaction.shouldRetry(failed("TargetNotFoundException: x"), 1, p, Expect.None))
        assertFalse(Transaction.shouldRetry(failed("SessionClosedException: dead"), 1, p, Expect.None))
        assertFalse(Transaction.shouldRetry(failed("DisplayIsolationViolationException: leak"), 1, p, Expect.None))
    }

    @Test fun `expect change rejects uncertain`() {
        val p = RetryPolicy(maxAttempts = 3)
        assertTrue(Transaction.shouldRetry(uncertain(), 1, p, Expect.Change))
        assertFalse(Transaction.shouldRetry(verified(), 1, p, Expect.Change))
    }

    @Test fun `expect package checks current package`() {
        val p = RetryPolicy(maxAttempts = 3)
        assertFalse(Transaction.shouldRetry(verified("com.android.chrome"), 1, p, Expect.Package("com.android.chrome")))
        assertTrue(Transaction.shouldRetry(verified("com.other"), 1, p, Expect.Package("com.android.chrome")))
    }

    @Test fun `no retry past max attempts`() {
        val p = RetryPolicy(maxAttempts = 2)
        assertFalse(Transaction.shouldRetry(failed("TargetNotFoundException"), 2, p, Expect.None))
    }

    @Test fun `destructive gate marks delete and clear only`() {
        assertTrue(RetryPolicy.isDestructive(AutomationCommand.DeleteText(3)))
        assertTrue(RetryPolicy.isDestructive(AutomationCommand.ClearText))
        assertFalse(RetryPolicy.isDestructive(AutomationCommand.PressBack))
        assertFalse(RetryPolicy.isDestructive(AutomationCommand.PressEnter))
        assertFalse(RetryPolicy.isDestructive(AutomationCommand.TypeText("hi")))
    }
}
