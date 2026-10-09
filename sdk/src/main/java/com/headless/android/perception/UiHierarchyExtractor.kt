package com.headless.android.perception

import com.headless.android.AccessibilityAgentException
import com.headless.android.HeadlessLog
import com.headless.android.agent.UiAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads one display's UI tree from the accessibility agent (#23).
 *
 * Agent-only on purpose. The previous `uiautomator dump --all` fallback returned windows
 * from EVERY display, including the user's physical screen, and picked one by package or
 * size — it could read (and then act on coordinates from) display 0. The agent is asked
 * for a single display id and refuses display 0.
 */
class UiHierarchyExtractor(
    private val agent: UiAgent?,
    private val displayId: Int
) {
    companion object {
        private const val OP = "UiHierarchyExtractor"

        /** Prefers the window of [targetPackage], then the active window, then the largest. */
        fun pickWindow(windows: List<AgentTreeParser.Window>, targetPackage: String?): AgentTreeParser.Window? {
            if (targetPackage != null) {
                windows.firstOrNull { it.packageName == targetPackage }?.let { return it }
                windows.firstOrNull { w -> w.root.find { it.packageName == targetPackage } != null }?.let { return it }
            }
            windows.firstOrNull { it.active }?.let { return it }
            return windows.maxByOrNull { it.root.bounds.width * it.root.bounds.height }
        }
    }

    /** Root [UiNode] of the best window on this session's display, or null if unavailable. */
    suspend fun dump(targetPackage: String? = null): UiNode? {
        val a = agent
        if (a == null) {
            HeadlessLog.w(OP, "no accessibility agent configured; cannot read display $displayId")
            return null
        }
        return try {
            val response = withContext(Dispatchers.IO) { a.call("tree", displayId) }
            pickWindow(AgentTreeParser.windows(response), targetPackage)?.root
        } catch (e: AccessibilityAgentException) {
            HeadlessLog.w(OP, "accessibility tree for display $displayId failed: ${e.message}")
            null
        }
    }
}
