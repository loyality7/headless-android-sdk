package com.headless.android.observation

/**
 * A predicate over the observation stream, used by the wait engine.
 *
 * Conditions are the generic primitive; specific waits ([WaitEngine.waitForStable],
 * [WaitEngine.waitForChange]) are thin wrappers. Building one engine over conditions
 * rather than a family of bespoke `waitForX` methods keeps timeout, polling and
 * evidence-reporting logic in exactly one place.
 *
 * These conditions are all **model-free** — they judge pixels and structural state only.
 * Semantic conditions ("the element 'Sign in' is visible") require a perception provider
 * and belong in that layer, plugging in via [Custom].
 */
fun interface Condition {

    /**
     * @return true when the condition is met by [frame].
     */
    fun isMet(frame: ScreenFrame): Boolean

    /** Human-readable description used in timeout evidence. Override via [named]. */
    companion object {

        /** Screen has stayed quiet for the policy's required duration. */
        val Stable = Condition { it.stability == ScreenStability.STABLE }

        /** Pixels changed beyond the policy threshold on this frame. */
        val Changed = Condition { it.stability == ScreenStability.CHANGING && (it.changeRatio ?: 0f) > 0f }

        /** Change exceeded an explicit ratio, regardless of policy. */
        fun changedMoreThan(ratio: Float) = Condition { (it.changeRatio ?: 0f) > ratio }

        /** Arbitrary caller-supplied predicate — the hook for semantic/perception conditions. */
        fun custom(predicate: (ScreenFrame) -> Boolean) = Condition { predicate(it) }
    }
}

/** Why a wait finished. */
sealed interface WaitOutcome {

    /** The condition was satisfied. */
    data class Met(
        val frame: ScreenFrame,
        val waitedMillis: Long,
        val framesObserved: Int
    ) : WaitOutcome

    /**
     * The deadline passed without the condition being met.
     *
     * Carries the last frame seen and observation counts so the caller can report *why*
     * it gave up rather than just that it did — a bare timeout with no evidence is the
     * kind of failure that's impossible to debug later.
     */
    data class TimedOut(
        val lastFrame: ScreenFrame?,
        val waitedMillis: Long,
        val framesObserved: Int,
        val lastChangeRatio: Float?,
        val description: String
    ) : WaitOutcome

    val met: Boolean get() = this is Met
}
