package com.headless.android.state

import java.io.File

/**
 * Crash-surviving ownership record for the single live session.
 *
 * Problem (#9): a session's display/reader/input handles die with its process, but the
 * launched APP can outlive it — reparented onto Display 0 or squatting on a dead display.
 * The next client then inherits poison it never created.
 *
 * Fix, minimal: one tiny file. [record] on launch, [clear] on stop/close. On the next
 * [HeadlessRuntime.createSession], [orphanedPackage] reports a package whose display is
 * gone; the runtime force-stops it BEFORE creating anything new. New client never
 * inherits; orphans get reaped, not adopted.
 *
 * // ponytail: one file, no DB / no heartbeat thread / no multi-session ledger.
 */
class SessionLedger(private val dir: File) {

    data class Entry(val sessionId: String, val displayId: Int, val packageName: String)

    private fun file(): File = File(dir, "session_ledger.txt")

    fun record(sessionId: String, displayId: Int, packageName: String) {
        try {
            dir.mkdirs()
            file().writeText("$sessionId|$displayId|$packageName")
        } catch (_: Throwable) {}
    }

    fun read(): Entry? = try {
        val parts = file().readText().trim().split("|")
        if (parts.size == 3) Entry(parts[0], parts[1].toInt(), parts[2]) else null
    } catch (_: Throwable) { null }

    fun clear() {
        try { file().delete() } catch (_: Throwable) {}
    }

    /** Package needing a force-stop: recorded display no longer exists. Null = nothing to repair. */
    fun orphanedPackage(liveDisplayIds: Set<Int>): String? {
        val entry = read() ?: return null
        return if (liveDisplayIds.contains(entry.displayId)) null else entry.packageName
    }
}
