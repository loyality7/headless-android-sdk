package com.headless.android.state

import com.headless.android.HeadlessLog
import com.headless.android.HeadlessRuntime
import com.headless.android.HeadlessSession

/**
 * Result of an honest session recovery attempt (#19).
 */
sealed interface RecoveryResult {
    /**
     * Infrastructure was successfully recreated and live state matches the checkpoint.
     */
    data class Recovered(
        val checkpoint: SessionCheckpoint,
        val session: HeadlessSession,
        val detail: String
    ) : RecoveryResult

    /**
     * Recovery could not be completed safely or honestly.
     * The runtime refuses to guess or pretend to resume into an unknown state.
     */
    data class Unrecoverable(
        val checkpoint: SessionCheckpoint?,
        val reason: String
    ) : RecoveryResult
}

/**
 * Executes honest state recovery following an ungraceful session loss (#19).
 *
 * Enforces the strict rule:
 * Inspect current Android state -> Determine recoverability -> Recreate infrastructure ->
 * Verify state matches checkpoint -> Continue ONLY if it matches, else FAIL HONESTLY.
 */
class SessionRecovery(
    private val runtime: HeadlessRuntime,
    private val checkpointStore: SessionCheckpointStore,
    private val stateEngine: StateEngine
) {

    private companion object {
        const val OP = "SessionRecovery"
    }

    fun recover(): RecoveryResult {
        val checkpoint = checkpointStore.load() ?: return RecoveryResult.Unrecoverable(
            checkpoint = null,
            reason = "No persisted session checkpoint found on disk."
        )

        HeadlessLog.i(OP, "Found checkpoint for session=${checkpoint.sessionId}, target=${checkpoint.targetPackage}")

        // 1. Inspect current platform state
        val device = stateEngine.deviceState()
        if (!device.backendAlive || !runtime.isAuthorized()) {
            return RecoveryResult.Unrecoverable(
                checkpoint = checkpoint,
                reason = "Privilege backend is not alive or authorized; cannot recreate virtual display."
            )
        }

        val pkg = checkpoint.targetPackage
        if (pkg.isNullOrBlank()) {
            return RecoveryResult.Unrecoverable(
                checkpoint = checkpoint,
                reason = "Checkpoint contains no target package to recover."
            )
        }

        // 2. Check live displays to verify whether the old display is gone
        val liveDisplays = stateEngine.listDisplayIds()
        val oldDisplayAlive = checkpoint.displayId != null && liveDisplays.contains(checkpoint.displayId)
        if (oldDisplayAlive) {
            // Check if any open session already holds it
            val existing = runtime.openSessions().firstOrNull { it.displayId == checkpoint.displayId }
            if (existing != null && existing.isOpen) {
                val currentApp = existing.currentApp()
                if (currentApp == pkg) {
                    return RecoveryResult.Recovered(
                        checkpoint = checkpoint,
                        session = existing,
                        detail = "Existing session ${existing.id} is already healthy on display ${existing.displayId} hosting '$pkg'"
                    )
                }
            }
        }

        // 3. Recreate infrastructure: allocate fresh trusted virtual display
        val newSession = try {
            runtime.createSession()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "Failed to create new session during recovery", e)
            return RecoveryResult.Unrecoverable(
                checkpoint = checkpoint,
                reason = "Failed to recreate trusted virtual display infrastructure: ${e.message}"
            )
        }

        // 4. Verify state and placement matches checkpoint
        return try {
            newSession.launch(pkg)
            val currentState = newSession.state()
            if (currentState.currentPackage != pkg) {
                newSession.close()
                RecoveryResult.Unrecoverable(
                    checkpoint = checkpoint,
                    reason = "Resumed package '${currentState.currentPackage}' does not match checkpoint target '$pkg'"
                )
            } else {
                HeadlessLog.i(OP, "Honest recovery succeeded: recreated session ${newSession.id} on display ${newSession.displayId}")
                RecoveryResult.Recovered(
                    checkpoint = checkpoint,
                    session = newSession,
                    detail = "Virtual display recreated (displayId=${newSession.displayId}); '$pkg' resumed and verified."
                )
            }
        } catch (e: Throwable) {
            try { newSession.close() } catch (_: Throwable) {}
            HeadlessLog.w(OP, "Recovery state verification threw", e)
            RecoveryResult.Unrecoverable(
                checkpoint = checkpoint,
                reason = "State recovery verification failed: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }
}
