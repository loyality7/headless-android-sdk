package com.headless.android.capture

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import com.headless.android.FrameCaptureException
import com.headless.android.HeadlessLog

/**
 * Reads the most recent composited frame off a [HeadlessDisplay]'s [ImageReader].
 * Pure capture — knows nothing about OCR, vision, or Accessibility.
 *
 * ## Idle screens produce no new frames
 *
 * `ImageReader.acquireLatestImage()` only returns an image when the display has composited
 * a *new* frame since the last read. A screen that is simply sitting still — an open menu,
 * a settled page — composites nothing, so naive capture fails on exactly the screens an
 * agent most wants to inspect. Observed on-device as repeated
 * "No frame available yet for display 6" while a Gmail account switcher sat open on screen.
 *
 * So the last successfully captured frame is retained and returned when the reader has
 * nothing new. [Screenshot.timestampNanos] still carries the original composition time, and
 * [Screenshot.isFresh] distinguishes a newly composited frame from a repeat, so callers
 * doing change detection are never misled into thinking a stale frame is new evidence.
 */
class FrameCapture(private val imageReader: ImageReader, private val displayId: Int) {

    companion object {
        private const val OP = "FrameCapture"
    }

    @Volatile
    private var lastFrame: Screenshot? = null

    /**
     * Captures the latest frame, or re-returns the last known one if the display has not
     * composited anything new.
     *
     * Throws [FrameCaptureException] only when there is genuinely nothing to return —
     * i.e. no new frame *and* no previous frame ever captured for this display.
     */
    fun capture(): Screenshot {
        val image: Image? = try {
            imageReader.acquireLatestImage()
        } catch (e: Throwable) {
            throw FrameCaptureException("acquireLatestImage failed", e)
        }

        if (image == null) {
            val cached = lastFrame
                ?: throw FrameCaptureException(
                    "No frame available for display $displayId and nothing captured previously " +
                        "(the display has never composited a frame — is anything running on it?)"
                )
            HeadlessLog.d(OP, "no new frame for display $displayId; returning cached frame")
            return cached.copy(isFresh = false)
        }

        try {
            val bitmap = imageToBitmap(image)
            HeadlessLog.event(displayId = displayId, op = OP, success = true)
            val shot = Screenshot(
                width = image.width,
                height = image.height,
                displayId = displayId,
                timestampNanos = image.timestamp,
                bitmap = bitmap,
                isFresh = true
            )
            lastFrame = shot
            return shot
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
