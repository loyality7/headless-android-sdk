package com.headless.example

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.headless.android.HeadlessAutomation
import com.headless.android.HeadlessSession
import com.headless.android.capture.Screenshot
import com.headless.android.observation.FrameDiff
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Step-0 primitive audit harness.
 *
 * Purpose: produce a ground-truth calibration table BEFORE the verification/transaction
 * engine is built, because that engine's timeouts, retry policies and stability thresholds
 * must be calibrated against measured primitive behavior rather than assumed behavior.
 *
 * For each (app x primitive) pair, across N trials, it records:
 *  - did the primitive's command report execution success (level-1 verification)
 *  - did device state actually change: frame pixels and/or resumed package (level-2)
 *  - how long it took
 *
 * It deliberately does NOT assert anything or retry. It measures. Output is a CSV written
 * to the app's external files dir.
 */
class AuditActivity : AppCompatActivity() {

    private lateinit var logView: TextView
    private val rows = mutableListOf<String>()

    private companion object {
        val TARGET_APPS = listOf(
            "com.android.chrome",      // known baseline (only previously-proven case)
            "com.miui.calculator",     // OEM system app, simple static UI
            "com.miui.notes",          // OEM app with native EditText (tests type() off-web)
            "com.github.android",      // third-party native, network-backed, likely login-gated
            "com.whatsapp"             // security-sensitive; probes secondary-display refusal
        )
        const val TRIALS = 3
        const val SETTLE_MS = 2500L
        const val POST_ACTION_MS = 1200L
        // Any change ratio above this counts as "the screen actually changed".
        const val CHANGE_THRESHOLD = 0.005f
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        logView = TextView(this).apply { setPadding(24, 24, 24, 24); textSize = 12f }
        scroll.addView(logView)
        setContentView(scroll)
        runAudit()
    }

    private fun log(msg: String) {
        android.util.Log.i("HeadlessAudit", msg)
        runOnUiThread {
            val cur = logView.text.toString()
            logView.text = if (cur.isEmpty()) msg else "$cur\n$msg"
        }
    }

    private fun runAudit() {
        lifecycleScope.launch {
            try {
                log("=== PRIMITIVE AUDIT START ===")
                val runtime = HeadlessAutomation.start(applicationContext)
                if (!runtime.isAuthorized() && !runtime.requestAuthorization()) {
                    log("ABORT: privilege backend not authorized")
                    return@launch
                }

                // Append-mode report: the process can be killed mid-run (MIUI's gesture
                // cleaner has done exactly that), so results are flushed after every app
                // rather than held in memory until the end.
                if (!reportFile().exists()) {
                    rows.add("app,primitive,trial,exec_ok,state_changed,change_ratio,resumed_pkg,duration_ms,error")
                    withContext(Dispatchers.IO) { flushReport() }
                }

                for (app in TARGET_APPS) {
                    for (trial in 1..TRIALS) {
                        log("--- $app trial $trial ---")
                        withContext(Dispatchers.IO) {
                            auditApp(runtime, app, trial)
                            flushReport()
                        }
                    }
                }

                val path = withContext(Dispatchers.IO) { flushReport() }
                log("=== AUDIT COMPLETE ===")
                log("report: $path")
            } catch (e: Throwable) {
                log("AUDIT FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    /** Runs one full trial for one app in its own session, so failures don't poison later apps. */
    private fun auditApp(runtime: com.headless.android.HeadlessRuntime, app: String, trial: Int) {
        var session: HeadlessSession? = null
        try {
            session = runtime.createSession()
            val s = session

            // --- launch ---
            measure(app, "launch", trial) {
                s.stopApp(app)
                Thread.sleep(500)
                s.launch(app)   // throws AppLaunchException if it can't be verified on-display
                Thread.sleep(SETTLE_MS)
                Outcome(execOk = true, resumedPkg = s.currentApp())
            }

            // If the app isn't actually here, remaining primitives are meaningless for it.
            if (!s.isAppOnDisplay(app)) {
                for (p in listOf("tap", "swipe", "type", "pressEnter", "pressBack", "screenshot")) {
                    rows.add("$app,$p,$trial,skipped,skipped,,,,app not on display")
                }
                log("$app: not on display, remaining primitives skipped")
                return
            }

            // --- screenshot (needed as the baseline for every state comparison) ---
            var baseline: Screenshot? = null
            measure(app, "screenshot", trial) {
                baseline = s.screenshot()
                Outcome(execOk = baseline != null, resumedPkg = null)
            }

            // --- tap (center of screen: least likely to hit something destructive) ---
            baseline = auditAction(s, app, "tap", trial, baseline) { s.tap(540f, 960f) }

            // --- swipe (never executed before this audit) ---
            // Deliberately a SHORT, MID-SCREEN, DOWNWARD swipe. A long upward swipe from
            // the lower screen area (540,1400 -> 540,700) was observed to be interpreted by
            // MIUI's gesture layer as a swipe-up-to-close and killed this very process
            // ("ProcessSceneCleaner: SwipeUpClean: kill procName=com.headless.example"),
            // aborting the audit mid-run. Injected input is not fully contained to the
            // virtual display on this OEM — see findings.
            baseline = auditAction(s, app, "swipe", trial, baseline) {
                s.swipe(540f, 900f, 540f, 1150f, 300L)
            }

            // --- type (no target field guaranteed; measures whether the command executes) ---
            baseline = auditAction(s, app, "type", trial, baseline) { s.type("headless audit") }

            // --- pressEnter ---
            baseline = auditAction(s, app, "pressEnter", trial, baseline) { s.pressEnter() }

            // --- pressBack (never executed before this audit) ---
            auditAction(s, app, "pressBack", trial, baseline) { s.pressBack() }

        } catch (e: Throwable) {
            rows.add("$app,launch,$trial,false,false,,,,${e.javaClass.simpleName}: ${sanitize(e.message)}")
            log("$app: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try { session?.stopApp(app) } catch (_: Throwable) {}
            try { session?.close() } catch (_: Throwable) {}
        }
    }

    /**
     * Executes one primitive, then measures state change against [baseline].
     * Returns the post-action frame to serve as the next baseline.
     */
    private fun auditAction(
        s: HeadlessSession,
        app: String,
        primitive: String,
        trial: Int,
        baseline: Screenshot?,
        action: () -> Unit
    ): Screenshot? {
        var after: Screenshot? = null
        measure(app, primitive, trial) {
            action()
            Thread.sleep(POST_ACTION_MS)
            after = try { s.screenshot() } catch (_: Throwable) { null }
            val ratio = if (baseline != null && after != null) {
                FrameDiff.compare(baseline.bitmap, after!!.bitmap).changeRatio
            } else null
            Outcome(
                execOk = true,
                changeRatio = ratio,
                stateChanged = ratio != null && ratio > CHANGE_THRESHOLD,
                resumedPkg = s.currentApp()
            )
        }
        return after ?: baseline
    }

    private class Outcome(
        val execOk: Boolean,
        val changeRatio: Float? = null,
        val stateChanged: Boolean? = null,
        val resumedPkg: String? = null
    )

    private fun measure(app: String, primitive: String, trial: Int, block: () -> Outcome) {
        val start = System.currentTimeMillis()
        try {
            val outcome = block()
            val dur = System.currentTimeMillis() - start
            rows.add(
                "$app,$primitive,$trial,${outcome.execOk}," +
                    "${outcome.stateChanged ?: ""}," +
                    "${outcome.changeRatio?.let { String.format("%.5f", it) } ?: ""}," +
                    "${outcome.resumedPkg ?: ""},$dur,"
            )
            log("  $primitive: exec=${outcome.execOk} changed=${outcome.stateChanged} ratio=${outcome.changeRatio} ${dur}ms")
        } catch (e: Throwable) {
            val dur = System.currentTimeMillis() - start
            rows.add("$app,$primitive,$trial,false,,,,$dur,${e.javaClass.simpleName}: ${sanitize(e.message)}")
            log("  $primitive: FAILED ${e.javaClass.simpleName}: ${e.message} (${dur}ms)")
        }
    }

    private fun sanitize(s: String?): String = (s ?: "").replace(",", ";").replace("\n", " ").take(160)

    private fun reportFile(): File {
        val dir = getExternalFilesDir(null) ?: cacheDir
        return File(dir, "primitive_audit.csv")
    }

    /** Appends any rows accumulated since the last flush, then clears the buffer. */
    private fun flushReport(): String {
        val file = reportFile()
        if (rows.isNotEmpty()) {
            val text = rows.joinToString("\n")
            if (file.exists() && file.length() > 0) {
                file.appendText("\n$text")
            } else {
                file.writeText(text)
            }
            rows.clear()
        }
        return file.absolutePath
    }
}
