package com.headless.android.perception

import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.util.ArrayDeque
import javax.xml.parsers.SAXParserFactory

/**
 * Fast, streaming SAX parser for UIAutomator XML dumps (#23).
 *
 * Runs identically on Android runtimes and host JVM unit test suites without mock dependencies.
 */
object XmlUiHierarchyParser {

    fun parse(xmlContent: String): List<UiNode> {
        if (xmlContent.isBlank()) return emptyList()

        val factory = SAXParserFactory.newInstance()
        val parser = factory.newSAXParser()
        val handler = HierarchyHandler()
        parser.parse(InputSource(StringReader(xmlContent)), handler)
        return handler.buildRoots()
    }

    /**
     * Parses the bounds string formatted as `[x1,y1][x2,y2]` into [ElementBounds].
     * Avoids regex allocations for performance during real-time screen inspection.
     */
    fun parseBounds(raw: String?): ElementBounds {
        if (raw == null || !raw.startsWith("[")) return ElementBounds(0, 0, 0, 0)
        val comma1 = raw.indexOf(',')
        val close1 = raw.indexOf(']')
        val open2 = raw.indexOf('[', close1)
        val comma2 = raw.indexOf(',', open2)
        val close2 = raw.indexOf(']', comma2)
        if (comma1 != -1 && close1 != -1 && open2 != -1 && comma2 != -1 && close2 != -1) {
            val x1 = raw.substring(1, comma1).toIntOrNull() ?: 0
            val y1 = raw.substring(comma1 + 1, close1).toIntOrNull() ?: 0
            val x2 = raw.substring(open2 + 1, comma2).toIntOrNull() ?: 0
            val y2 = raw.substring(comma2 + 1, close2).toIntOrNull() ?: 0
            return ElementBounds(x1, y1, x2, y2)
        }
        return ElementBounds(0, 0, 0, 0)
    }

    private class HierarchyHandler : DefaultHandler() {
        private val rootBuilders = mutableListOf<NodeBuilder>()
        private val stack = ArrayDeque<NodeBuilder>()

        fun buildRoots(): List<UiNode> = rootBuilders.map { it.build() }

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            if (qName != "node" || attributes == null) return

            val index = attributes.getValue("index")?.toIntOrNull() ?: 0
            val text = attributes.getValue("text") ?: ""
            val resourceId = attributes.getValue("resource-id") ?: ""
            val className = attributes.getValue("class") ?: ""
            val packageName = attributes.getValue("package") ?: ""
            val contentDesc = attributes.getValue("content-desc") ?: ""
            val checkable = attributes.getValue("checkable")?.toBoolean() ?: false
            val checked = attributes.getValue("checked")?.toBoolean() ?: false
            val clickable = attributes.getValue("clickable")?.toBoolean() ?: false
            val enabled = attributes.getValue("enabled")?.toBoolean() ?: true
            val focusable = attributes.getValue("focusable")?.toBoolean() ?: false
            val focused = attributes.getValue("focused")?.toBoolean() ?: false
            val scrollable = attributes.getValue("scrollable")?.toBoolean() ?: false
            val longClickable = attributes.getValue("long-clickable")?.toBoolean() ?: false
            val password = attributes.getValue("password")?.toBoolean() ?: false
            val selected = attributes.getValue("selected")?.toBoolean() ?: false
            val bounds = parseBounds(attributes.getValue("bounds"))

            val builder = NodeBuilder(
                index = index,
                text = text,
                resourceId = resourceId,
                className = className,
                packageName = packageName,
                contentDesc = contentDesc,
                checkable = checkable,
                checked = checked,
                clickable = clickable,
                enabled = enabled,
                focusable = focusable,
                focused = focused,
                scrollable = scrollable,
                longClickable = longClickable,
                password = password,
                selected = selected,
                bounds = bounds
            )

            if (stack.isEmpty()) {
                rootBuilders.add(builder)
            } else {
                stack.peek()?.children?.add(builder)
            }
            stack.push(builder)
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            if (qName == "node" && !stack.isEmpty()) {
                stack.pop()
            }
        }
    }

    private class NodeBuilder(
        val index: Int,
        val text: String,
        val resourceId: String,
        val className: String,
        val packageName: String,
        val contentDesc: String,
        val checkable: Boolean,
        val checked: Boolean,
        val clickable: Boolean,
        val enabled: Boolean,
        val focusable: Boolean,
        val focused: Boolean,
        val scrollable: Boolean,
        val longClickable: Boolean,
        val password: Boolean,
        val selected: Boolean,
        val bounds: ElementBounds
    ) {
        val children = mutableListOf<NodeBuilder>()

        fun build(): UiNode = UiNode(
            index = index,
            text = text,
            resourceId = resourceId,
            className = className,
            packageName = packageName,
            contentDesc = contentDesc,
            checkable = checkable,
            checked = checked,
            clickable = clickable,
            enabled = enabled,
            focusable = focusable,
            focused = focused,
            scrollable = scrollable,
            longClickable = longClickable,
            password = password,
            selected = selected,
            bounds = bounds,
            children = children.map { it.build() }
        )
    }
}
