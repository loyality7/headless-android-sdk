package com.headless.android.ime

import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend
import java.io.File

/**
 * Switches the SYSTEM default keyboard to the headless IME for a session and restores
 * the user's keyboard after. Previous id persisted to disk — a crash mid-session must
 * still restore, or the user is left without a keyboard (#9 pattern).
 *
 * // ponytail: settings/ime shell calls only. No picker UI, no per-display IME (not user-facing).
 */
class ImeSwitcher(
    private val backend: PrivilegeBackend,
    private val dir: File?,
    private val headlessImeId: String
) {
    companion object {
        private const val OP = "ImeSwitcher"
        private const val PREV_FILE = "headless_ime_prev.txt"
    }

    fun currentId(): String? = try {
        backend.shell(arrayOf("settings", "get", "secure", "default_input_method"))
            .stdout.trim().takeIf { it.isNotEmpty() && it != "null" }
    } catch (e: Throwable) {
        HeadlessLog.w(OP, "current IME query failed", e)
        null
    }

    /** Switch to headless IME, remembering the user's keyboard. Idempotent. */
    fun switchToHeadless(): Boolean {
        val current = currentId()
        if (current == headlessImeId) return true
        try {
            if (current != null) persistPrev(current)
            backend.shell(arrayOf("ime", "enable", headlessImeId))
            backend.shell(arrayOf("ime", "set", headlessImeId))
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "switch failed", e)
            return false
        }
        val applied = currentId() == headlessImeId
        HeadlessLog.i(OP, "switch headless=$applied (was=$current)")
        return applied
    }

    /** Restore the user's keyboard. Never throws. */
    fun restore() {
        val prev = readPrev() ?: return
        try {
            backend.shell(arrayOf("ime", "set", prev))
            HeadlessLog.i(OP, "restored IME=$prev")
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "restore failed for $prev", e)
        } finally {
            clearPrev()
        }
    }

    private fun prevFile(): File? = dir?.let { File(it, PREV_FILE) }

    private fun persistPrev(id: String) {
        try {
            dir?.mkdirs()
            prevFile()?.writeText(id)
        } catch (_: Throwable) {}
    }

    private fun readPrev(): String? = try {
        prevFile()?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    } catch (_: Throwable) { null }

    private fun clearPrev() {
        try { prevFile()?.delete() } catch (_: Throwable) {}
    }
}
