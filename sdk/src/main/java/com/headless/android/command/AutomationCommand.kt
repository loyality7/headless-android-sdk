package com.headless.android.command

/**
 * Canonical, transport-independent description of one automation request.
 *
 * This is the schema every external decision-maker translates *into* — an LLM tool call,
 * an MCP request, a REST call, a CLI invocation, or a human poking at adb. The runtime
 * knows nothing about where a command came from, and nothing about GPT/Claude/Gemini/etc.
 *
 * Adapters convert their own wire format into these types; the runtime executes only these.
 */
sealed interface AutomationCommand {

    /** Create a session (and its hidden display) if one isn't already open. */
    data object OpenSession : AutomationCommand

    /** Close the current session, destroying its display. */
    data object CloseSession : AutomationCommand

    data class LaunchApp(val packageName: String) : AutomationCommand

    data class StopApp(val packageName: String) : AutomationCommand

    data class Tap(val x: Float, val y: Float) : AutomationCommand

    /** Taps a semantic target (text, id, region, or element) resolved on screen. */
    data class TapTarget(val target: com.headless.android.perception.Target) : AutomationCommand

    data class Swipe(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float,
        val durationMs: Long
    ) : AutomationCommand

    data class TypeText(val text: String) : AutomationCommand

    /**
     * Deletes [count] characters backwards from the cursor.
     *
     * Added because a real task (composing a Gmail message) became unrecoverable without
     * it: text typed into the wrong field could not be removed, so the runtime could
     * neither complete nor cleanly abandon the task. An automation runtime that can only
     * add text and never remove it cannot correct its own mistakes.
     */
    data class DeleteText(val count: Int) : AutomationCommand

    /** Clears the focused field by selecting all and deleting. */
    data object ClearText : AutomationCommand

    data object PressEnter : AutomationCommand

    data object PressBack : AutomationCommand

    /** Moves focus to the next focusable field (Tab), for form traversal without coordinates. */
    data object PressTab : AutomationCommand

    /** Capture the current frame and report state. */
    data object Observe : AutomationCommand

    /** Inspects persisted checkpoint and attempts honest state recovery (#19). */
    data object RecoverSession : AutomationCommand
}

/**
 * Outcome of executing an [AutomationCommand].
 *
 * Deliberately three-valued, never a Boolean: "it worked", "it definitely didn't", and
 * "the command ran but I cannot prove what it did" are different facts, and collapsing
 * the third into either of the others is how an automation runtime starts lying to the
 * thing driving it.
 */
sealed interface CommandResult {

    /** Executed, and the expected effect was observed. */
    data class Verified(
        val command: AutomationCommand,
        val detail: String,
        val screenshotPath: String?,
        val currentPackage: String?,
        val changeRatio: Float?,
        val durationMs: Long
    ) : CommandResult

    /** Executed, but the expected effect provably did not happen. */
    data class Failed(
        val command: AutomationCommand,
        val reason: String,
        val screenshotPath: String?,
        val currentPackage: String?,
        val durationMs: Long
    ) : CommandResult

    /**
     * The command was issued without error, but its effect could not be confirmed —
     * e.g. it produced no observable screen change and no structural state change, which
     * is a legitimate outcome for actions like a mute toggle or a clipboard copy.
     */
    data class Uncertain(
        val command: AutomationCommand,
        val reason: String,
        val screenshotPath: String?,
        val currentPackage: String?,
        val changeRatio: Float?,
        val durationMs: Long
    ) : CommandResult
}
