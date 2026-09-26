package com.headless.android.perception

import com.headless.android.capture.Screenshot
import kotlinx.coroutines.runBlocking

/**
 * Native accessibility-backed element detection provider (#23).
 *
 * Resolves UI elements with 100% ground-truth accuracy directly from the accessibility tree,
 * without OCR distortion, model hallucinations, or external dependencies.
 */
class AccessibilityDetectionProvider(
    private val extractor: UiHierarchyExtractor,
    private val targetPackageProvider: () -> String? = { null }
) : ElementDetectionProvider {

    override fun detectElements(screenshot: Screenshot): List<ScreenElement> {
        val rootNode = runBlocking {
            extractor.dump(targetPackageProvider())
        } ?: return emptyList()

        return rootNode.toScreenElements()
    }
}
