package com.headless.android.capture

import android.graphics.Bitmap
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import com.headless.android.FrameCaptureException
import com.headless.android.HeadlessLog
import java.io.Closeable

/**
 * Reads composited frames off a [com.headless.android.display.HeadlessDisplay]'s [ImageReader] continuously via a
 * background [HandlerThread] so the BufferQueue never exhausts, and retains the latest
 * composited frame for immediate retrieval.
 */
class FrameCapture(
    private val imageReader: ImageReader,
    private val displayId: Int
) : Closeable {

    companion object {
        private const val OP = "FrameCapture"
    }

    private val captureThread: HandlerThread =
        HandlerThread("HeadlessFrameCapture-$displayId").apply { start() }
    private val captureHandler: Handler = Handler(captureThread.looper)

    @Volatile
    private var lastFrame: Screenshot? = null

    init {
        imageReader.setOnImageAvailableListener({ reader ->
            val image: Image = try {
                reader.acquireLatestImage()
            } catch (e: Throwable) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val bitmap = imageToBitmap(image)
                lastFrame = Screenshot(
                    width = image.width,
                    height = image.height,
                    displayId = displayId,
                    timestampNanos = image.timestamp,
                    bitmap = bitmap,
                    isFresh = true
                )
            } catch (e: Throwable) {
                HeadlessLog.w(OP, "Failed converting frame: ${e.message}")
            } finally {
                image.close()
            }
        }, captureHandler)
    }

    /**
     * Captures the latest frame, or returns the most recently composited frame if the
     * screen is idle.
     *
     * Waits up to 3000ms for the very first frame if none has arrived yet.
     */
    fun capture(): Screenshot {
        // Direct read if background listener has already received a frame
        lastFrame?.let {
            HeadlessLog.event(displayId = displayId, op = OP, success = true)
            return it
        }

        // Try direct acquire in case listener has not triggered yet
        val directImage = try {
            imageReader.acquireLatestImage()
        } catch (_: Throwable) {
            null
        }

        if (directImage != null) {
            try {
                val bitmap = imageToBitmap(directImage)
                val shot = Screenshot(
                    width = directImage.width,
                    height = directImage.height,
                    displayId = displayId,
                    timestampNanos = directImage.timestamp,
                    bitmap = bitmap,
                    isFresh = true
                )
                lastFrame = shot
                HeadlessLog.event(displayId = displayId, op = OP, success = true)
                return shot
            } finally {
                directImage.close()
            }
        }

        // Wait up to 3000ms for first frame to arrive via listener
        val deadline = System.currentTimeMillis() + 3000L
        while (System.currentTimeMillis() < deadline) {
            lastFrame?.let {
                HeadlessLog.event(displayId = displayId, op = OP, success = true)
                return it
            }
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                break
            }
        }

        throw FrameCaptureException(
            "No frame available for display $displayId and nothing captured previously " +
                "(the display has not composited a frame — is anything running on it?)"
        )
    }

    override fun close() {
        try {
            imageReader.setOnImageAvailableListener(null, null)
        } catch (_: Throwable) {}
        captureThread.quitSafely()
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
