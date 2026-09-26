package com.headless.android.command

import com.headless.android.HeadlessLog
import com.headless.android.HeadlessRuntime
import com.headless.android.HeadlessSession
import com.headless.android.capture.Screenshot
import com.headless.android.observation.FrameDiff
import com.headless.android.observation.WaitOutcome
import kotlinx.coroutines.runBlocking
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
    /** Max wait for an action to produce any visible change before giving up on it. */
    private val changeTimeoutMs: Long = 3_000L,
    /** Max wait for the screen to settle after it started changing. */
    private val stableTimeoutMs: Long = 5_000L,
    /** Settle time used where a condition wait does not apply (launch/stop/session ops). */
    private val postActionDelayMs: Long = 1200L
) {
    private companion object {
        const val OP = "CommandExecutor"
    }

    private var session: HeadlessSession? = null
    private var lastFrame: Screenshot? = null
    private var frameCounter = 0

    // #32: one transaction at a time. ControlService dispatches intents concurrently on
    // Dispatchers.IO — interleaved input injection + shared lastFrame is never safe.
    // Blocking under this lock serializes callers; slowness is the point, not a bug.
    private val execLock = Any()

    fun execute(command: AutomationCommand): CommandResult =
        execute(command, Expect.None, RetryPolicy(maxAttempts = 1))

    /** Retry loop with expect verification. Single-shot when maxAttempts=1. */
    fun execute(
        command: AutomationCommand,
        expect: Expect = Expect.None,
        policy: RetryPolicy = RetryPolicy()
    ): CommandResult = synchronized(execLock) {
        var last: CommandResult? = null
        // ponytail: deny-before-execute, no audit log / no two-step handshake yet.
        if (RetryPolicy.isDestructive(command) && !policy.allowDestructive) {
            return CommandResult.Failed(
                command = command,
                reason = "DangerousActionGuard: $command destroys text and needs allowDestructive=true. Pass confirm explicitly.",
                screenshotPath = null,
                currentPackage = null,
                durationMs = 0L
            )
        }
        for (attempt in 1..policy.maxAttempts) {
            val result = executeOnce(command)
            last = result
            if (!Transaction.shouldRetry(result, attempt, policy, expect)) {
                if (attempt > 1) HeadlessLog.d(OP, "$command settled attempt=$attempt/${policy.maxAttempts} -> $result")
                return result
            }
            HeadlessLog.d(OP, "$command attempt=$attempt/${policy.maxAttempts} -> $result, retrying")

            Transaction.recoveryCommand(policy.recovery)?.let { recCmd ->
                HeadlessLog.i(OP, "Executing recovery before retry attempt ${attempt + 1}: $recCmd")
                try {
                    executeOnce(recCmd)
                } catch (e: Throwable) {
                    HeadlessLog.w(OP, "Recovery command $recCmd threw", e)
                }
            }

            if (policy.backoffMs > 0) Thread.sleep(policy.backoffMs * attempt)
        }
        return last!!
    }

    fun executeOnce(command: AutomationCommand): CommandResult {
        val start = System.currentTimeMillis()
        return try {
            when (command) {
                AutomationCommand.OpenSession -> openSession(command, start)
                AutomationCommand.CloseSession -> closeSession(command, start)
                is AutomationCommand.LaunchApp -> launchApp(command, start)
                is AutomationCommand.StopApp -> stopApp(command, start)
                AutomationCommand.Observe -> observe(command, start)
                is AutomationCommand.Tap -> act(command, start) { it.tap(command.x, command.y) }
                is AutomationCommand.TapTarget -> act(command, start) {
                    kotlinx.coroutines.runBlocking { it.tap(command.target) }
                }
                is AutomationCommand.Swipe -> act(command, start) {
                    it.swipe(command.x1, command.y1, command.x2, command.y2, command.durationMs)
                }
                is AutomationCommand.TypeText -> act(command, start) { it.type(command.text) }
                AutomationCommand.PressEnter -> act(command, start) { it.pressEnter() }
                AutomationCommand.PressBack -> act(command, start) { it.pressBack() }
                AutomationCommand.PressTab -> act(command, start) { it.pressTab() }
                is AutomationCommand.DeleteText -> act(command, start) { it.deleteText(command.count) }
                AutomationCommand.ClearText -> act(command, start) { it.clearText() }
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
            ?: throw IllegalStateException(
                "No open session in THIS client process — issue OpenSession first. " +
                    "If a session was open before, this process restarted and orphaned it; " +
                    "OpenSession reaps orphans automatically (#9)."
            )

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

        // Wait on an actual condition instead of sleeping a fixed interval. A fixed sleep
        // is wrong in both directions: the audit measured legitimate UI reactions arriving
        // anywhere from ~1.3s to ~2.4s, so a short sleep judges the action too early while
        // a long one makes every action pay worst-case cost. Waiting for "changed, then
        // settled" returns as soon as the UI is actually done reacting, and reports
        // honestly when nothing ever happened.
        val waitOutcome = runBlocking {
            s.waitForChangeThenStable(
                changeTimeoutMs = changeTimeoutMs,
                stableTimeoutMs = stableTimeoutMs
            )
        }

        val after = captureQuietly(s)
        val pkgAfter = s.currentApp()
        lastFrame = after
        HeadlessLog.d(
            OP,
            "post-action wait: met=${waitOutcome.met} waited=${
                when (waitOutcome) {
                    is WaitOutcome.Met -> waitOutcome.waitedMillis
                    is WaitOutcome.TimedOut -> waitOutcome.waitedMillis
                }
            }ms"
        )

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
