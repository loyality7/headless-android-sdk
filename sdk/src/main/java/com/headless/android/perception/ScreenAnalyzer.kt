package com.headless.android.perception

import com.headless.android.capture.Screenshot

/**
 * Turns a raw [Screenshot] into a structured [ScreenObservation]. No implementation ships
 * in the runtime yet — this is the seam future OCR, OpenCV, ML Kit/ONNX, a local vision
 * model, a cloud multimodal model, or (optionally, never required) Accessibility plug into.
 *
 * The runtime MUST function on screenshots alone; nothing in [com.headless.android.HeadlessSession]
 * may depend on a [ScreenAnalyzer] being present.
 */
interface ScreenAnalyzer {
    val name: String

    suspend fun analyze(screenshot: Screenshot): ScreenObservation
}
