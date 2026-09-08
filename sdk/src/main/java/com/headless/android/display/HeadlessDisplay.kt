package com.headless.android.display

import android.media.ImageReader
import android.os.IBinder

/**
 * A created trusted virtual display, owned by exactly one [com.headless.android.HeadlessSession].
 *
 * Holds the [ImageReader] whose [android.view.Surface] backs the display — capture and display
 * share this reader because that's the same surface the display composites into.
 *
 * [callbackToken] is the `IVirtualDisplayCallback` binder passed to
 * `IDisplayManager.createVirtualDisplay`. It MUST be retained for the display's whole
 * lifetime: `IDisplayManager.releaseVirtualDisplay(token)` identifies the display by that
 * exact binder, so discarding it makes the display impossible to destroy. Earlier code
 * created the token as a throwaway local and leaked every display it ever made (30+
 * orphaned displays accumulated on-device, each keeping an app and its windows alive and
 * competing for IME focus).
 */
class HeadlessDisplay internal constructor(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    internal val imageReader: ImageReader,
    internal val callbackToken: IBinder,
    private val releaser: (IBinder) -> Unit
) {
    @Volatile
    private var released = false

    val isReleased: Boolean get() = released

    /**
     * Destroys the underlying virtual display and closes the capture surface.
     * Idempotent. Releases the display even if closing the ImageReader fails, and vice
     * versa — a leaked display is far more damaging than a leaked reader.
     */
    internal fun release() {
        if (released) return
        released = true

        var displayError: Throwable? = null
        try {
            releaser(callbackToken)
        } catch (e: Throwable) {
            displayError = e
        }

        try {
            imageReader.close()
        } catch (e: Throwable) {
            if (displayError == null) displayError = e
        }

        if (displayError != null) throw displayError
    }
}
