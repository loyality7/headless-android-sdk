package com.headless.android.input

/** The app window the soft keyboard is currently serving, from `dumpsys input_method`. */
data class ImeClient(val uid: Int, val displayId: Int)

/**
 * Tells whose keyboard is open. A keyboard visible on display 0 is only an automation leak
 * if it serves a window that lives on ANOTHER display (the hidden display being automated).
 * If the user opens their own keyboard in an app on display 0, the current IME client is
 * that app on display 0, and nothing is wrong.
 */
object ImeOwnership {

    // ICU regex (Android) rejects an unescaped '}', so both braces are escaped.
    private val CLIENT = Regex("""mCurClient=ClientState\{\w+ mUid=(\d+) mPid=\d+ mSelfReportedDisplayId=(\d+)\}""")

    fun currentClient(inputMethodDump: String): ImeClient? =
        CLIENT.find(inputMethodDump)?.let { ImeClient(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }

    /**
     * True when a keyboard that is visible on display 0 must be treated as a leak from
     * automation: its client is on a non-default display, or its owner cannot be determined
     * (fail safe — an unreadable dump must not hide a real leak).
     */
    fun isAutomationKeyboard(client: ImeClient?): Boolean = client == null || client.displayId != 0
}
