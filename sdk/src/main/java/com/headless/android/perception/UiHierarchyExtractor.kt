package com.headless.android.perception

import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Extracts and filters the UI hierarchy from Android's accessibility subsystem via [PrivilegeBackend] (#23).
 */
class UiHierarchyExtractor(
    private val privilegeBackend: PrivilegeBackend,
    private val displayId: Int
) {
    companion object {
        private const val OP = "UiHierarchyExtractor"
    }

    /**
     * Dumps the live UI hierarchy and returns the root [UiNode] belonging to this session.
     *
     * If [targetPackage] is provided, prioritizes the window belonging to that package.
     */
    suspend fun dump(targetPackage: String? = null): UiNode? {
        val uniqueDumpPath = "/sdcard/headless_ui_dump_${displayId}.xml"
        return try {
            val cmd = arrayOf("uiautomator", "dump", "--all", uniqueDumpPath)
            val dumpResult = privilegeBackend.shell(cmd)
            if (dumpResult.exitCode != 0) {
                HeadlessLog.w(OP, "uiautomator dump failed: ${dumpResult.summary()}")
                return null
            }

            val readCmd = arrayOf("cat", uniqueDumpPath)
            val xmlOutput = privilegeBackend.shell(readCmd).stdout
            if (xmlOutput.isBlank()) {
                HeadlessLog.w(OP, "UI dump was empty at $uniqueDumpPath")
                return null
            }

            val roots = XmlUiHierarchyParser.parse(xmlOutput)
            if (roots.isEmpty()) return null

            // If a specific package is expected, find the window matching it
            if (targetPackage != null) {
                val matched = roots.firstOrNull { it.packageName == targetPackage }
                if (matched != null) return matched
            }

            // Otherwise, match the largest root or the first non-system root
            roots.maxByOrNull { it.bounds.width * it.bounds.height } ?: roots.first()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "Failed to extract UI hierarchy for display $displayId", e)
            null
        } finally {
            try {
                privilegeBackend.shell(arrayOf("rm", "-f", uniqueDumpPath))
            } catch (_: Throwable) {}
        }
    }
}
