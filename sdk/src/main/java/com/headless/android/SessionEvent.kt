package com.headless.android

import com.headless.android.command.AutomationCommand
import com.headless.android.command.CommandResult
import com.headless.android.state.SessionState

/**
 * Structured event emitted on a [HeadlessSession]'s lifecycle and execution stream.
 *
 * Emitted in real-time, allowing consumers (driving agents, telemetry observers,
 * or UI clients) to monitor session activity, action execution, and safety states
 * without having to parse logs or constantly poll dumpsys.
 */
sealed interface SessionEvent {
    val sessionId: String
    val timestampMs: Long

    /** A new trusted virtual display was created for this session. */
    data class DisplayCreated(
        override val sessionId: String,
        val displayId: Int,
        val width: Int,
        val height: Int,
        val densityDpi: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** The virtual display owned by this session was destroyed and released. */
    data class DisplayReleased(
        override val sessionId: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An application was launched onto this session's display and placement was verified. */
    data class AppLaunched(
        override val sessionId: String,
        val packageName: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An application was stopped on this session's display. */
    data class AppStopped(
        override val sessionId: String,
        val packageName: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An application crashed or was killed unexpectedly on this session's display. */
    data class AppCrashed(
        override val sessionId: String,
        val packageName: String,
        val displayId: Int,
        val reason: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An input action or primitive is starting execution. */
    data class ActionStarted(
        override val sessionId: String,
        val action: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An input action completed execution. */
    data class ActionCompleted(
        override val sessionId: String,
        val action: String,
        val displayId: Int,
        val success: Boolean,
        val durationMs: Long,
        val error: String? = null,
        val outcome: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An [AutomationCommand] was executed through the command/transaction engine. */
    data class CommandExecuted(
        override val sessionId: String,
        val command: AutomationCommand,
        val result: CommandResult,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** Contamination was detected on Display 0 (e.g. IME shown on Display 0). */
    data class ContaminationAlert(
        override val sessionId: String,
        val detail: String,
        val displayZeroPackage: String? = null,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** An input action was blocked because it violated display boundaries or safety margins. */
    data class IsolationViolation(
        override val sessionId: String,
        val action: String,
        val reason: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** Privilege backend connection was lost mid-session (e.g. Shizuku daemon killed). */
    data class BackendLost(
        override val sessionId: String,
        val backendName: String,
        val reason: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** Session state snapshot changed. */
    data class StateChanged(
        override val sessionId: String,
        val state: SessionState,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** Session teardown has begun. */
    data class SessionClosing(
        override val sessionId: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent

    /** Session teardown is complete. */
    data class SessionClosed(
        override val sessionId: String,
        val displayId: Int,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : SessionEvent
}
