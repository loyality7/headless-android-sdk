package com.headless.android.perception

import com.headless.android.capture.Screenshot

/** Result of running a [ScreenAnalyzer] over a [Screenshot]. */
data class ScreenObservation(
    val sourceScreenshot: Screenshot? = null,
    val elements: List<ScreenElement>,
    val analyzerName: String
)
