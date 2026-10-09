package com.headless.android.perception

/**
 * Platform-independent, structured representation of an Android UI hierarchy node
 * extracted via accessibility/UIAutomator dump (#23).
 */
data class UiNode(
    val index: Int = 0,
    val text: String = "",
    val resourceId: String = "",
    val className: String = "",
    val packageName: String = "",
    val contentDesc: String = "",
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val clickable: Boolean = false,
    val enabled: Boolean = true,
    val focusable: Boolean = false,
    val focused: Boolean = false,
    val scrollable: Boolean = false,
    val longClickable: Boolean = false,
    val password: Boolean = false,
    val selected: Boolean = false,
    val bounds: ElementBounds = ElementBounds(0, 0, 0, 0),
    val children: List<UiNode> = emptyList(),
    /** Accessibility-agent node reference; empty for nodes parsed from a uiautomator XML dump. */
    val ref: String = "",
    val editable: Boolean = false,
    val hint: String = ""
) {
    val centerX: Float get() = bounds.centerX
    val centerY: Float get() = bounds.centerY

    /**
     * Depth-first search for the first node matching [predicate].
     */
    fun find(predicate: (UiNode) -> Boolean): UiNode? {
        if (predicate(this)) return this
        for (child in children) {
            val match = child.find(predicate)
            if (match != null) return match
        }
        return null
    }

    /**
     * Depth-first search returning all nodes matching [predicate].
     */
    fun findAll(predicate: (UiNode) -> Boolean): List<UiNode> {
        val result = mutableListOf<UiNode>()
        collect(predicate, result)
        return result
    }

    private fun collect(predicate: (UiNode) -> Boolean, acc: MutableList<UiNode>) {
        if (predicate(this)) acc.add(this)
        for (child in children) {
            child.collect(predicate, acc)
        }
    }

    /**
     * Finds a node by visible text or content-description.
     */
    fun findByText(query: String, exact: Boolean = false, ignoreCase: Boolean = true): UiNode? {
        return find { node ->
            if (exact) {
                node.text.equals(query, ignoreCase) || node.contentDesc.equals(query, ignoreCase)
            } else {
                node.text.contains(query, ignoreCase) || node.contentDesc.contains(query, ignoreCase)
            }
        }
    }

    /**
     * Finds a node by Android resource-id (e.g. "com.android.chrome:id/url_bar" or simply "url_bar").
     */
    fun findById(id: String): UiNode? {
        return find { node ->
            node.resourceId.equals(id, ignoreCase = true) ||
                node.resourceId.endsWith("/$id", ignoreCase = true) ||
                node.resourceId.endsWith(":id/$id", ignoreCase = true)
        }
    }

    /**
     * Finds a node by content description.
     */
    fun findByDesc(desc: String, exact: Boolean = false, ignoreCase: Boolean = true): UiNode? {
        return find { node ->
            if (exact) {
                node.contentDesc.equals(desc, ignoreCase)
            } else {
                node.contentDesc.contains(desc, ignoreCase)
            }
        }
    }

    /**
     * Flattens the node tree into a list of [ScreenElement]s for the [PerceptionEngine].
     */
    fun toScreenElements(): List<ScreenElement> {
        val list = mutableListOf<ScreenElement>()
        flattenElements(list)
        return list
    }

    private fun flattenElements(acc: MutableList<ScreenElement>) {
        val hasSemanticInfo = text.isNotEmpty() ||
            contentDesc.isNotEmpty() ||
            clickable ||
            checkable ||
            resourceId.isNotEmpty()

        if (hasSemanticInfo && bounds.width > 0 && bounds.height > 0) {
            acc.add(
                ScreenElement(
                    id = resourceId.ifEmpty { "node_${bounds.left}_${bounds.top}" },
                    type = className.substringAfterLast('.'),
                    text = text.ifEmpty { contentDesc.ifEmpty { null } },
                    bounds = bounds,
                    clickable = clickable,
                    editable = className.endsWith("EditText", ignoreCase = true),
                    confidence = 1.0f
                )
            )
        }
        for (child in children) {
            child.flattenElements(acc)
        }
    }
}
