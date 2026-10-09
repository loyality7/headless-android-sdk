package com.headless.android.perception

/**
 * An actionable, compressed UI element formatted for LLM agent perception (#23).
 */
data class CompactUiElement(
    val index: Int,
    val type: String,
    val text: String?,
    val resourceId: String?,
    val bounds: ElementBounds,
    val clickable: Boolean,
    val editable: Boolean,
    /** Accessibility-agent reference used to act on this exact element; empty without the agent. */
    val ref: String = "",
    val scrollable: Boolean = false,
    val checked: Boolean = false,
    val enabled: Boolean = true,
    val focused: Boolean = false,
    val hint: String? = null
) {
    val centerX: Float get() = bounds.centerX
    val centerY: Float get() = bounds.centerY

    /**
     * Formats one element into a compact LLM prompt line:
     * `[1] url_bar (EditText): "google.com" [x=472, y=56]`
     */
    fun toPromptLine(): String {
        val idLabel = resourceId?.substringAfterLast(":id/")?.substringAfterLast('/')?.let { "$it " } ?: ""
        val content = when {
            !text.isNullOrBlank() -> "\"$text\""
            else -> ""
        }
        val kind = when {
            editable -> "(EditText)"
            scrollable -> "(Scrollable)"
            clickable -> "(Clickable)"
            else -> "($type)"
        }
        val state = buildString {
            if (checked) append(" checked")
            if (!enabled) append(" disabled")
            if (focused) append(" focused")
        }
        val hintPart = if (editable && text.isNullOrBlank() && !hint.isNullOrBlank()) "hint=\"$hint\"" else ""
        val center = "[x=${centerX.toInt()}, y=${centerY.toInt()}]"
        return "[$index] $idLabel$kind$state $content $hintPart $center".replace(Regex(" {2,}"), " ").trim()
    }
}

/**
 * Snapshot of compressed UI elements for an active screen.
 */
data class CompactUiSnapshot(
    val packageName: String,
    val elements: List<CompactUiElement>
) {
    private val elementMap = elements.associateBy { it.index }

    fun findByIndex(index: Int): CompactUiElement? = elementMap[index]

    /**
     * Compact text format optimized for minimal token count in LLM context windows.
     */
    fun toPromptText(): String = buildString {
        appendLine("Package: $packageName | Actionable Elements (${elements.size}):")
        for (elem in elements) {
            appendLine(elem.toPromptLine())
        }
    }

    /**
     * Compact JSON representation for structured LLM tool outputs.
     */
    fun toJson(): String = buildString {
        append("{\"package\":\"$packageName\",\"elements\":[")
        elements.forEachIndexed { i, elem ->
            if (i > 0) append(",")
            append("{\"idx\":${elem.index},\"type\":\"${elem.type}\"")
            elem.text?.let { append(",\"text\":\"${esc(it)}\"") }
            elem.resourceId?.let { append(",\"id\":\"${esc(it)}\"") }
            append(",\"x\":${elem.centerX.toInt()},\"y\":${elem.centerY.toInt()}}")
        }
        append("]}")
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
}

/**
 * Compresses deep Android UI XML hierarchies into concise, token-efficient snapshots for LLMs.
 * Filters out empty layout wrappers (FrameLayout, LinearLayout, zero-size views) and retains
 * only interactive and informative elements.
 */
object UiTreeCompressor {

    fun compress(root: UiNode): CompactUiSnapshot {
        val rawElements = mutableListOf<RawCandidate>()
        collectCandidates(root, rawElements)

        // Deduplicate identical or fully overlapping elements (e.g. TextView inside Clickable Button)
        val deduplicated = deduplicate(rawElements)

        val compactElements = deduplicated.mapIndexed { idx, candidate ->
            CompactUiElement(
                index = idx + 1,
                type = candidate.node.className.substringAfterLast('.'),
                text = candidate.text.ifBlank { null },
                resourceId = candidate.node.resourceId.ifBlank { null },
                bounds = candidate.node.bounds,
                clickable = candidate.node.clickable || candidate.node.checkable,
                editable = candidate.node.editable || candidate.node.className.endsWith("EditText", ignoreCase = true),
                ref = candidate.node.ref,
                scrollable = candidate.node.scrollable,
                checked = candidate.node.checked,
                enabled = candidate.node.enabled,
                focused = candidate.node.focused,
                hint = candidate.node.hint.ifBlank { null }
            )
        }

        return CompactUiSnapshot(
            packageName = root.packageName,
            elements = compactElements
        )
    }

    private data class RawCandidate(
        val node: UiNode,
        val text: String
    )

    private fun collectCandidates(node: UiNode, acc: MutableList<RawCandidate>) {
        val visibleText = node.text.ifBlank { node.contentDesc }.trim()
        val isInteractive = node.clickable || node.checkable || node.scrollable || node.editable || node.nodeTypeIsInteractive()
        val hasBounds = node.bounds.width > 0 && node.bounds.height > 0

        if (hasBounds && (visibleText.isNotEmpty() || isInteractive)) {
            acc.add(RawCandidate(node, visibleText))
        }

        for (child in node.children) {
            collectCandidates(child, acc)
        }
    }

    private fun deduplicate(candidates: List<RawCandidate>): List<RawCandidate> {
        val result = mutableListOf<RawCandidate>()
        for (c in candidates) {
            // If an element with the exact same center and text already exists, keep the more specific/interactive one
            val existingIdx = result.indexOfFirst {
                it.node.bounds == c.node.bounds && (it.text == c.text || it.text.isEmpty() || c.text.isEmpty())
            }
            if (existingIdx != -1) {
                val existing = result[existingIdx]
                if (!existing.node.clickable && c.node.clickable) {
                    result[existingIdx] = c
                } else if (existing.text.isEmpty() && c.text.isNotEmpty()) {
                    result[existingIdx] = c
                }
            } else {
                result.add(c)
            }
        }
        return result
    }

    private fun UiNode.nodeTypeIsInteractive(): Boolean {
        val simple = className.substringAfterLast('.')
        return simple in listOf("Button", "ImageButton", "EditText", "CheckBox", "RadioButton", "Switch", "SearchView")
    }
}
