package com.headless.android.perception

import org.json.JSONObject

/** Converts the accessibility agent's JSON tree into [UiNode]s. */
object AgentTreeParser {

    /** One window of a display: its package and root node. */
    data class Window(val id: Int, val packageName: String, val title: String, val active: Boolean, val root: UiNode)

    fun windows(response: JSONObject): List<Window> {
        val array = response.optJSONArray("windows") ?: return emptyList()
        val out = ArrayList<Window>(array.length())
        for (i in 0 until array.length()) {
            val w = array.optJSONObject(i) ?: continue
            val rootJson = w.optJSONObject("root") ?: continue
            out.add(
                Window(
                    id = w.optInt("id"),
                    packageName = w.optString("pkg"),
                    title = w.optString("title"),
                    active = w.optBoolean("active"),
                    root = node(rootJson)
                )
            )
        }
        return out
    }

    fun node(json: JSONObject): UiNode {
        val b = json.optJSONArray("b")
        val kids = json.optJSONArray("kids")
        val children = if (kids == null) emptyList() else (0 until kids.length()).mapNotNull { kids.optJSONObject(it)?.let(::node) }
        return UiNode(
            text = json.optString("text"),
            resourceId = json.optString("rid"),
            className = json.optString("cls"),
            packageName = json.optString("pkg"),
            contentDesc = json.optString("desc"),
            checkable = json.optBoolean("cb"),
            checked = json.optBoolean("ch"),
            clickable = json.optBoolean("ck"),
            enabled = !json.optBoolean("dis"),
            focusable = json.optBoolean("fc"),
            focused = json.optBoolean("fd"),
            scrollable = json.optBoolean("sc"),
            longClickable = json.optBoolean("lc"),
            password = json.optBoolean("pw"),
            selected = json.optBoolean("sl"),
            bounds = if (b != null && b.length() == 4) ElementBounds(b.getInt(0), b.getInt(1), b.getInt(2), b.getInt(3)) else ElementBounds(0, 0, 0, 0),
            children = children,
            ref = json.optString("ref"),
            editable = json.optBoolean("ed"),
            hint = json.optString("hint")
        )
    }
}
