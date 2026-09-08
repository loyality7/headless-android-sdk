package com.headless.android.observation

import android.graphics.Bitmap
import android.graphics.Rect

/**
 * Pixel-level comparison between two frames.
 *
 * This is deliberately dumb and model-free: it answers "did pixels change, and how much"
 * — nothing about *what* changed or whether the change was the intended one. State-level
 * verification builds on this; semantic verification does not (that needs a
 * [com.headless.android.perception.ScreenAnalyzer]).
 *
 * Screens frequently never reach perfect stability (blinking cursors, clocks, spinners,
 * video, ads), so callers compare [Result.changeRatio] against a threshold rather than
 * testing for exact equality, and may exclude regions known to animate.
 */
object FrameDiff {

    data class Result(
        /** Fraction of sampled pixels that differed beyond [tolerancePerChannel]. 0.0 = identical. */
        val changeRatio: Float,
        val sampledPixels: Int,
        val changedPixels: Int
    )

    /**
     * Compares [a] and [b], optionally ignoring [ignoreRegions] (screen coordinates) and
     * sampling every [sampleStep]-th pixel in each axis for speed. A [sampleStep] of 4
     * examines ~1/16th of the pixels, which is far more than enough to detect UI changes
     * and keeps this cheap enough to run on a frame stream.
     *
     * Returns a [Result] with `changeRatio == 1f` if the two frames have different
     * dimensions (treated as a total change rather than an error).
     */
    fun compare(
        a: Bitmap,
        b: Bitmap,
        ignoreRegions: List<Rect> = emptyList(),
        sampleStep: Int = 4,
        tolerancePerChannel: Int = 8
    ): Result {
        require(sampleStep >= 1) { "sampleStep must be >= 1" }

        if (a.width != b.width || a.height != b.height) {
            return Result(changeRatio = 1f, sampledPixels = 0, changedPixels = 0)
        }

        val width = a.width
        val height = a.height

        // Pull full rows once rather than per-pixel getPixel() calls, which are far slower.
        val rowA = IntArray(width)
        val rowB = IntArray(width)

        var sampled = 0
        var changed = 0

        var y = 0
        while (y < height) {
            a.getPixels(rowA, 0, width, 0, y, width, 1)
            b.getPixels(rowB, 0, width, 0, y, width, 1)

            var x = 0
            while (x < width) {
                if (!isIgnored(x, y, ignoreRegions)) {
                    sampled++
                    if (pixelDiffers(rowA[x], rowB[x], tolerancePerChannel)) changed++
                }
                x += sampleStep
            }
            y += sampleStep
        }

        val ratio = if (sampled == 0) 0f else changed.toFloat() / sampled.toFloat()
        return Result(changeRatio = ratio, sampledPixels = sampled, changedPixels = changed)
    }

    private fun isIgnored(x: Int, y: Int, ignoreRegions: List<Rect>): Boolean {
        for (region in ignoreRegions) {
            if (region.contains(x, y)) return true
        }
        return false
    }

    private fun pixelDiffers(p1: Int, p2: Int, tolerance: Int): Boolean {
        if (p1 == p2) return false
        val dr = Math.abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
        if (dr > tolerance) return true
        val dg = Math.abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
        if (dg > tolerance) return true
        val db = Math.abs((p1 and 0xFF) - (p2 and 0xFF))
        if (db > tolerance) return true
        val da = Math.abs(((p1 shr 24) and 0xFF) - ((p2 shr 24) and 0xFF))
        return da > tolerance
    }
}
