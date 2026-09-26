package com.headless.android.perception

import android.graphics.Rect
import com.headless.android.capture.Screenshot

/**
 * Interface for pluggable on-device element detection providers (OCR, ML Kit, vision models).
 */
interface ElementDetectionProvider {
    fun detectElements(screenshot: Screenshot): List<ScreenElement>
}

/**
 * Primary on-device perception engine that analyzes screenshots and resolves semantic [Target]s.
 */
class PerceptionEngine(
    val detectionProvider: ElementDetectionProvider? = null
) : ScreenAnalyzer {

    override val name: String = "PerceptionEngine"

    override suspend fun analyze(screenshot: Screenshot): ScreenObservation {
        val elements = detectionProvider?.detectElements(screenshot) ?: emptyList()
        return ScreenObservation(
            sourceScreenshot = screenshot,
            elements = elements,
            analyzerName = name
        )
    }

    /**
     * Resolves a [Target] to the best matching [ScreenElement] in the given [observation].
     * Returns null if no match is found.
     */
    fun resolve(target: Target, observation: ScreenObservation): ScreenElement? {
        return resolveAll(target, observation).firstOrNull()
    }

    /**
     * Finds all matching [ScreenElement]s for a given [Target].
     */
    fun resolveAll(target: Target, observation: ScreenObservation): List<ScreenElement> {
        return when (target) {
            is Target.Element -> listOf(target.element)
            is Target.Point -> {
                val ix = target.x.toInt()
                val iy = target.y.toInt()
                listOf(
                    ScreenElement(
                        id = "point_${ix}_$iy",
                        type = "point",
                        text = null,
                        bounds = ElementBounds(ix, iy, ix, iy),
                        clickable = true,
                        editable = false,
                        confidence = 1.0f
                    )
                )
            }
            is Target.Region -> {
                // If an existing perceived element intersects the region, prioritize it;
                // otherwise return a synthesized element covering the region.
                val matching = observation.elements.filter {
                    it.bounds.intersects(target.bounds)
                }
                if (matching.isNotEmpty()) {
                    matching
                } else {
                    listOf(
                        ScreenElement(
                            id = "region_${target.bounds.left}_${target.bounds.top}",
                            type = "region",
                            text = null,
                            bounds = target.bounds,
                            clickable = true,
                            editable = false,
                            confidence = 1.0f
                        )
                    )
                }
            }
            is Target.Text -> {
                observation.elements.filter { elem ->
                    val text = elem.text ?: return@filter false
                    if (target.exact) {
                        text.equals(target.query, ignoreCase = target.ignoreCase)
                    } else {
                        text.contains(target.query, ignoreCase = target.ignoreCase)
                    }
                }
            }
            is Target.Id -> {
                observation.elements.filter { elem ->
                    elem.id.equals(target.id, ignoreCase = true) ||
                        elem.id.endsWith("/${target.id}", ignoreCase = true) ||
                        elem.id.endsWith(":id/${target.id}", ignoreCase = true)
                }
            }
        }
    }
}
