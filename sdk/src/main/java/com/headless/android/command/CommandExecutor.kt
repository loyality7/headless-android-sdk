package com.headless.android.command

import com.headless.android.HeadlessLog
import com.headless.android.HeadlessRuntime
import com.headless.android.HeadlessSession
import com.headless.android.capture.Screenshot
import com.headless.android.observation.FrameDiff
import java.io.File
import java.io.FileOutputStream

/**
 * Executes [AutomationCommand]s against a single session, applying execution-level and
 * state-level verification, and reporting a three-valued [CommandResult].
 *
 * Semantic verification is intentionally absent here — that requires a perception provider,
 * which the runtime does not ship and must not depend on. What this class can prove is:
 * the command ran (execution), and whether pixels and/or the resumed package changed
 * (state). Anything beyond that is reported as [CommandResult.Uncertain] rather than
 * guessed at.
 *
 * Holds at most one session, matching the runtime's default one-agent-one-display rule.
 */
class CommandExecutor(
    private val runtime: HeadlessRuntime,
    private val outputDir: File,
    /** Pixel-change ratio above which the screen is considered to have actually changed. */
    private val changeThreshold: Float = 0.005f,
    /** Settle time after an action before observing its effect. */
    private val postActionDelayMs: Long = 1200L
) {
    private companion object {
        const val OP = "CommandExecutor"
    }

    private var session: HeadlessSession? = null
    private var lastFrame: Screenshot? = null
    private var frameCounter = 0

    fun execute(command: AutomationCommand): CommandResult {
        val start = System.currentTimeMillis()
        return try {
            when (command) {
                AutomationCommand.OpenSession -> openSession(command, start)
                AutomationCommand.CloseSession -> closeSession(command, start)
                is AutomationCommand.LaunchApp -> launchApp(command, start)
                is AutomationCommand.StopApp -> stopApp(command, start)
                AutomationCommand.Observe -> observe(command, start)
                is AutomationCommand.Tap -> act(command, start) { it.tap(command.x, command.y) }
                is AutomationCommand.Swipe -> act(command, start) {
                    it.swipe(command.x1, command.y1, command.x2, command.y2, command.durationMs)
                }
                is AutomationCommand.TypeText -> act(command, start) { it.type(command.text) }
                AutomationCommand.PressEnter -> act(command, start) { it.pressEnter() }
                AutomationCommand.PressBack -> act(command, start) { it.pressBack() }
            }
        } catch (e: Throwable) {
            HeadlessLog.e(OP, "command $command threw", e)
            CommandResult.Failed(
                command = command,
                reason = "${e.javaClass.simpleName}: ${e.message}",
                screenshotPath = null,
                currentPackage = null,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun requireSession(): HeadlessSession =
        session?.takeIf { it.isOpen }
            ?: throw IllegalStateException("No open session — issue OpenSession first")

    private fun openSession(command: AutomationCommand, start: Long): CommandResult {
        session?.takeIf { it.isOpen }?.let { existing ->
            return CommandResult.Verified(
                command = command,
                detail = "session already open: ${existing.id} display=${existing.displayId}",
                screenshotPath = null,
                currentPackage = existing.currentApp(),
                changeRatio = null,
                durationMs = System.currentTimeMillis() - start
            )
        }
        val created = runtime.createSession()
        session = created
        lastFrame = null
        // Verified at state level: the display must actually exist per the platform.
        val state = created.state()
        return if (state.displayAlive) {
            CommandResult.Verified(
                command = command,
                detail = "session=${created.id} display=${created.displayId}",
                screenshotPath = null,
                currentPackage = state.currentPackage,
                changeRatio = null,
                durationMs = System.currentTimeMillis() - start
            )
        } else {
            CommandResult.Failed(
                command = command,
                reason = "session created but display ${created.displayId} not registered with the platform",
                screenshotPath = null,
                currentPackage = null,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun closeSession(command: AutomationCommand, start: Long): CommandResult {
        val current = session
            ?: return CommandResult.Verified(
                command, "no session open", null, null, null,
                System.currentTimeMillis() - start
            )
        val displayId = current.displayId
        current.close()
        session = null
        lastFrame = null
        return CommandResult.Verified(
            command = command,
            detail = "closed session ${current.id}, released display $displayId",
            screenshotPath = null,
            currentPackage = null,
            changeRatio = null,
            durationMs = System.currentTimeMillis() - start
        )
    }

    private fun launchApp(command: AutomationCommand.LaunchApp, start: Long): CommandResult {
        val s = requireSession()
        s.launch(command.packageName) // throws AppLaunchException if placement can't be verified
        Thread.sleep(postActionDelayMs)
        val frame = captureQuietly(s)
        val pkg = s.currentApp()
        // State verification: the launcher already confirmed a task on our display.
        return CommandResult.Verified(
            command = command,
            detail = "launched ${command.packageName} on display ${s.displayId}",
            screenshotPath = frame?.let { saveFrame(it, "launch") },
            currentPackage = pkg,
            changeRatio = null,
            durationMs = System.currentTimeMillis() - start
        )
    }

    private fun stopApp(command: AutomationCommand.StopApp, start: Long): CommandResult {
        val s = requireSession()
        val ok = s.stopApp(command.packageName)
        Thread.sleep(postActionDelayMs)
        val frame = captureQuietly(s)
        return if (ok) {
            CommandResult.Verified(
                command, "stopped ${command.packageName}",
                frame?.let { saveFrame(it, "stop") }, s.currentApp(), null,
                System.currentTimeMillis() - start
            )
        } else {
            CommandResult.Failed(
                command, "force-stop of ${command.packageName} reported failure",
                frame?.let { saveFrame(it, "stop") }, s.currentApp(),
                System.currentTimeMillis() - start
            )
        }
    }

    private fun observe(command: AutomationCommand, start: Long): CommandResult {
        val s = requireSession()
        val frame = s.screenshot()
        val ratio = lastFrame?.let { FrameDiff.compare(it.bitmap, frame.bitmap).changeRatio }
        lastFrame = frame
        return CommandResult.Verified(
            command = command,
            detail = "frame ${frame.width}x${frame.height} display=${frame.displayId}",
            screenshotPath = saveFrame(frame, "observe"),
            currentPackage = s.currentApp(),
            changeRatio = ratio,
            durationMs = System.currentTimeMillis() - start
        )
    }

    /**
     * Runs an input action, then judges it at state level only: did pixels change, or did
     * the resumed package change? If neither, the result is [CommandResult.Uncertain] —
     * NOT a failure, because plenty of legitimate actions produce no visible change.
     */
    private fun act(
        command: AutomationCommand,
        start: Long,
        action: (HeadlessSession) -> Unit
    ): CommandResult {
        val s = requireSession()
        val before = lastFrame ?: captureQuietly(s)
        val pkgBefore = s.currentApp()

        action(s) // throws InputInjectionException on execution-level failure

        Thread.sleep(postActionDelayMs)
        val after = captureQuietly(s)
        val pkgAfter = s.currentApp()
        lastFrame = after

        val ratio = if (before != null && after != null) {
            FrameDiff.compare(before.bitmap, after.bitmap).changeRatio
        } else null

        val pixelsChanged = ratio != null && ratio > changeThreshold
        val packageChanged = pkgBefore != pkgAfter
        val path = after?.let { saveFrame(it, "action") }

        return when {
            pixelsChanged || packageChanged -> CommandResult.Verified(
                command = command,
                detail = buildString {
                    append("state changed:")
                    if (pixelsChanged) append(" pixels=${"%.5f".format(ratio)}")
                    if (packageChanged) append(" package $pkgBefore -> $pkgAfter")
                },
                screenshotPath = path,
                currentPackage = pkgAfter,
                changeRatio = ratio,
                durationMs = System.currentTimeMillis() - start
            )

            ratio == null -> CommandResult.Uncertain(
                command = command,
                reason = "command executed but no frame was available to compare",
                screenshotPath = path,
                currentPackage = pkgAfter,
                changeRatio = null,
                durationMs = System.currentTimeMillis() - start
            )

            else -> CommandResult.Uncertain(
                command = command,
                reason = "command executed; no observable state change " +
                    "(pixels=${"%.5f".format(ratio)} <= threshold=$changeThreshold, package unchanged). " +
                    "Effect may be real but invisible, or the action may not have landed — " +
                    "semantic verification would be needed to distinguish.",
                screenshotPath = path,
                currentPackage = pkgAfter,
                changeRatio = ratio,
                durationMs = System.currentTimeMillis() - start
            )
        }
    }

    private fun captureQuietly(s: HeadlessSession): Screenshot? =
        try {
            s.screenshot()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "frame capture failed", e)
            null
        }

    private fun saveFrame(frame: Screenshot, label: String): String {
        if (!outputDir.exists()) outputDir.mkdirs()
        val file = File(outputDir, "frame_%03d_%s.png".format(frameCounter++, label))
        FileOutputStream(file).use { out ->
            frame.bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
        return file.absolutePath
    }

    fun shutdown() {
        try {
            session?.close()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "session close during shutdown failed", e)
        }
        session = null
    }
}
