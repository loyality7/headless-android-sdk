package com.headless.android.state

import java.io.File

/**
 * Persisted snapshot of session progress, captured outside the session's own memory space (#19).
 *
 * Stored on disk so that when a session dies (process killed by LMKD/OEM cleaner, display destroyed,
 * or Shizuku connection dropped), the next client process can inspect what was accomplished and
 * attempt an honest recovery rather than blindly assuming success or losing all progress.
 */
data class SessionCheckpoint(
    val sessionId: String,
    val displayId: Int?,
    val targetPackage: String?,
    val lastVerifiedState: String,
    val lastCompletedAction: String,
    val pendingAction: String?,
    val timestampMs: Long = System.currentTimeMillis()
) {

    /** Serializes checkpoint to a clean key-value / pipe string format. */
    fun serialize(): String {
        return listOf(
            sessionId,
            displayId?.toString() ?: "",
            targetPackage ?: "",
            escape(lastVerifiedState),
            escape(lastCompletedAction),
            escape(pendingAction ?: ""),
            timestampMs.toString()
        ).joinToString("|")
    }

    companion object {
        private fun escape(s: String): String =
            s.replace("\\", "\\\\").replace("|", "\\p").replace("\n", "\\n")

        private fun unescape(s: String): String =
            s.replace("\\n", "\n").replace("\\p", "|").replace("\\\\", "\\")

        fun deserialize(raw: String): SessionCheckpoint? {
            val parts = raw.trim().split("|")
            if (parts.size < 7) return null
            return try {
                SessionCheckpoint(
                    sessionId = parts[0],
                    displayId = parts[1].toIntOrNull(),
                    targetPackage = parts[2].ifEmpty { null },
                    lastVerifiedState = unescape(parts[3]),
                    lastCompletedAction = unescape(parts[4]),
                    pendingAction = unescape(parts[5]).ifEmpty { null },
                    timestampMs = parts[6].toLongOrNull() ?: System.currentTimeMillis()
                )
            } catch (_: Throwable) {
                null
            }
        }
    }
}

/**
 * File-backed persistence store for [SessionCheckpoint].
 */
class SessionCheckpointStore(private val dir: File) {

    private fun file(): File = File(dir, "session_checkpoint.txt")

    fun save(checkpoint: SessionCheckpoint) {
        try {
            dir.mkdirs()
            file().writeText(checkpoint.serialize())
        } catch (_: Throwable) {}
    }

    fun load(): SessionCheckpoint? {
        return try {
            val f = file()
            if (!f.exists()) return null
            SessionCheckpoint.deserialize(f.readText())
        } catch (_: Throwable) {
            null
        }
    }

    fun clear() {
        try {
            file().delete()
        } catch (_: Throwable) {}
    }

    fun hasCheckpoint(): Boolean = file().exists()
}
