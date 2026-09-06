package com.headless.android

import com.headless.android.apps.AppLauncher
import com.headless.android.capture.FrameCapture
import com.headless.android.capture.Screenshot
import com.headless.android.display.HeadlessDisplay
import com.headless.android.display.VirtualDisplayManager
import com.headless.android.input.InputController
import com.headless.android.privilege.PrivilegeBackend
import java.util.UUID

/**
 * One isolated headless Android environment: a hidden trusted virtual display plus the
 * app running on it. Owns every resource (display, ImageReader, input) created for it —
 * [close] releases all of them. A closed session throws [SessionClosedException] on any
 * further call.
 */
class HeadlessSession internal constructor(
    private val privilegeBackend: PrivilegeBackend,
    displayWidth: Int,
    displayHeight: Int,
    displayDensityDpi: Int
) {
    val id: String = UUID.randomUUID().toString()

    private val display: HeadlessDisplay =
        VirtualDisplayManager(privilegeBackend).createTrustedDisplay(displayWidth, displayHeight, displayDensityDpi)

    val displayId: Int get() = display.displayId

    private val appLauncher = AppLauncher(privilegeBackend)
    private val inputController = InputController(privilegeBackend, display.displayId)
    private val frameCapture = FrameCapture(display.imageReader, display.displayId)

    @Volatile
    private var closed = false

    fun launch(packageName: String) {
        checkOpen()
        appLauncher.launch(packageName, display.displayId)
    }

    fun tap(x: Float, y: Float) {
        checkOpen()
        inputController.tap(x, y)
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long) {
        checkOpen()
        inputController.swipe(x1, y1, x2, y2, durationMs)
    }

    fun type(text: String) {
        checkOpen()
        inputController.type(text)
    }

    fun pressEnter() {
        checkOpen()
        inputController.pressEnter()
    }

    fun pressBack() {
        checkOpen()
        inputController.pressBack()
    }

    fun screenshot(): Screenshot {
        checkOpen()
        return frameCapture.capture()
    }

    fun close() {
        if (closed) return
        closed = true
        display.release()
        HeadlessLog.event(sessionId = id, displayId = display.displayId, op = "HeadlessSession.close", success = true)
    }

    private fun checkOpen() {
        if (closed) throw SessionClosedException()
    }
}
