package com.headless.android.display

import android.hardware.display.VirtualDisplay
import android.media.ImageReader

/**
 * A created trusted virtual display, owned by exactly one [com.headless.android.HeadlessSession].
 * Holds the [ImageReader] whose [android.view.Surface] backs the display — capture and display
 * share this reader because that's the same surface the display composites into.
 */
class HeadlessDisplay internal constructor(
    val displayId: Int,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
    internal val imageReader: ImageReader,
    private val virtualDisplay: VirtualDisplay?
) {
    @Volatile
    private var released = false

    internal fun release() {
        if (released) return
        released = true
        virtualDisplay?.release()
        imageReader.close()
    }
}
