package com.headless.android.agent

/** Accessibility actions that can be performed on a [com.headless.android.perception.UiNode]. */
enum class NodeAction(val wire: String) {
    CLICK("click"),
    LONG_CLICK("long_click"),
    CONTEXT_CLICK("context_click"),
    FOCUS("focus"),
    CLEAR_FOCUS("clear_focus"),
    SELECT("select"),
    CLEAR_SELECTION("clear_selection"),
    SCROLL_FORWARD("scroll_forward"),
    SCROLL_BACKWARD("scroll_backward"),
    SCROLL_UP("scroll_up"),
    SCROLL_DOWN("scroll_down"),
    SCROLL_LEFT("scroll_left"),
    SCROLL_RIGHT("scroll_right"),
    SHOW_ON_SCREEN("show_on_screen"),
    SET_TEXT("set_text"),
    SET_SELECTION("set_selection"),
    COPY("copy"),
    CUT("cut"),
    PASTE("paste"),
    EXPAND("expand"),
    COLLAPSE("collapse"),
    DISMISS("dismiss"),
    SET_PROGRESS("set_progress");

    companion object {
        fun fromWire(name: String): NodeAction? = values().firstOrNull { it.wire == name }
    }
}

/**
 * Result of an accessibility action. [performed] only says the app accepted the action;
 * [verified] says the effect was observed (e.g. the field now holds the typed text).
 * Callers needing proof of a higher-level outcome (message sent, page loaded) must still
 * check the screen afterwards.
 */
data class ActionOutcome(
    val action: NodeAction,
    val performed: Boolean,
    val verified: Boolean,
    val detail: String,
    val textAfter: String? = null
)
