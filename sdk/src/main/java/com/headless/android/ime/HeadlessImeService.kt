package com.headless.android.ime

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import com.headless.android.HeadlessLog

/**
 * No-UI input method for the hidden display: survives field focus without crashing,
 * shows nothing anywhere, commits nothing by itself (text still arrives via the
 * shell `input` path). Also snapshots surrounding text so tests can READ BACK what
 * actually landed — the accuracy loop typing alone can never give.
 *
 * // ponytail: no keyboard view, no candidates, no settings UI. Focus survival + readback only.
 */
class HeadlessImeService : InputMethodService() {

    companion object {
        private const val OP = "HeadlessIme"

        /** Last text observed around the cursor in the currently bound field. */
        @Volatile
        var lastBeforeCursor: CharSequence? = null
            private set

        @Volatile
        var lastAfterCursor: CharSequence? = null
            private set

        @Volatile
        var bindCount: Int = 0
            private set

        fun snapshot(): String {
            val before = lastBeforeCursor?.toString() ?: ""
            val after = lastAfterCursor?.toString() ?: ""
            return before + "|" + after
        }
    }

    override fun onCreateInputView(): View? = null // no keyboard, ever

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onEvaluateInputViewShown(): Boolean = false

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        bindCount++
        // NEVER block the main thread here: getTextBeforeCursor is a synchronous binder
        // call into the target app — observed wedging the whole process (all later intents
        // queued forever) when the app was busy. Snapshot on a throwaway thread instead.
        Thread { snapshotNow() }.start()
        // Never show anything: field keeps focus, user sees nothing, Gboard never involved.
    }

    override fun onFinishInput() {
        Thread { snapshotNow() }.start()
        super.onFinishInput()
    }

    private fun snapshotNow() {
        try {
            val ic = currentInputConnection ?: return
            lastBeforeCursor = ic.getTextBeforeCursor(200, 0)
            lastAfterCursor = ic.getTextAfterCursor(200, 0)
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "readback failed", e)
        }
    }
}
