package com.headless.android.capture

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import com.headless.android.FrameCaptureException
import com.headless.android.HeadlessLog

/**
 * Reads the most recent composited frame off a [HeadlessDisplay]'s [ImageReader].
 * Pure capture — knows nothing about OCR, vision, or Accessibility.
 */
class FrameCapture(private val imageReader: ImageReader, private val displayId: Int) {

    companion object {
        private const val OP = "FrameCapture"
    }

    /** Captures the latest available frame as a [Screenshot]. Throws if none is available yet. */
    fun capture(): Screenshot {
        val image: Image = try {
            imageReader.acquireLatestImage()
        } catch (e: Throwable) {
            throw FrameCaptureException("acquireLatestImage failed", e)
        } ?: throw FrameCaptureException("No frame available yet for display $displayId")

        try {
            val bitmap = imageToBitmap(image)
            HeadlessLog.event(displayId = displayId, op = OP, success = true)
            return Screenshot(
                width = image.width,
                height = image.height,
                displayId = displayId,
                timestampNanos = image.timestamp,
                bitmap = bitmap
            )
        } finally {
            image.close()
        }
    }

    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val padded = Bitmap.createBitmap(
            image.width + rowPadding / pixelStride,
            image.height,
            Bitmap.Config.ARGB_8888
        )
        padded.copyPixelsFromBuffer(buffer)

        return if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
    }
}
