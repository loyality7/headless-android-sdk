package com.headless.android.command

// ponytail: retry+expect only, no DSL / no builder / no backoff strategies.
sealed interface Expect {
    data object None : Expect
    /** Require observable state change (pixels or package). */
    data object Change : Expect
    /** Require specific resumed package after action (e.g. after LaunchApp). */
    data class Package(val name: String) : Expect
}

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val backoffMs: Long = 500L,
    val retryOnUncertain: Boolean = true,
    /** Destructive cmds (DeleteText/ClearText) refuse unless true. Default deny. */
    val allowDestructive: Boolean = false
) {
    companion object {
        /** Commands that destroy user data with no undo. PressEnter/SEND excluded:
         *  context-dependent (search-submit vs mail-send), can't judge without perception. */
        fun isDestructive(cmd: AutomationCommand): Boolean = when (cmd) {
            is AutomationCommand.DeleteText -> true
            is AutomationCommand.ClearText -> true
            else -> false
        }
    }
}

object Transaction {
    // Non-retryable: safety or lifecycle — retrying risks typing into Display 0 or spinning on dead session.
    private val nonRetryable = listOf(
        "SessionClosedException",
        "DisplayIsolationViolationException",
        "PermissionDeniedException",
        "ShizukuUnavailableException"
    )

    fun isRetryableFailure(reason: String): Boolean =
        nonRetryable.none { reason.contains(it) }

    fun expectMet(result: CommandResult, expect: Expect): Boolean = when (expect) {
        is Expect.None -> true
        is Expect.Change -> when (result) {
            is CommandResult.Verified -> true
            else -> false
        }
        is Expect.Package -> when (result) {
            is CommandResult.Verified -> result.currentPackage == expect.name
            is CommandResult.Failed -> result.currentPackage == expect.name
            is CommandResult.Uncertain -> result.currentPackage == expect.name
        }
    }

    fun shouldRetry(result: CommandResult, attempt: Int, policy: RetryPolicy, expect: Expect): Boolean {
        if (attempt >= policy.maxAttempts) return false
        if (!expectMet(result, expect)) return when (result) {
            is CommandResult.Failed -> isRetryableFailure(result.reason)
            is CommandResult.Uncertain -> true // expect demanded proof, got none — retry
            is CommandResult.Verified -> true // package mismatch etc.
        }
        // Expect met, but Uncertain with retry enabled means "try once more for proof".
        if (result is CommandResult.Uncertain && policy.retryOnUncertain && expect == Expect.None) return true
        if (result is CommandResult.Failed) return isRetryableFailure(result.reason)
        return false
    }
}
