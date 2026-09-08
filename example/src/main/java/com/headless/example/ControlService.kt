package com.headless.example

import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.headless.android.HeadlessAutomation
import com.headless.android.HeadlessRuntime
import com.headless.android.command.AutomationCommand
import com.headless.android.command.CommandExecutor
import com.headless.android.command.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * adb-driven control channel: an external decision-maker (a model, a script, a human)
 * issues one [AutomationCommand] per `am startservice` invocation and reads the result
 * from a JSON-lines file plus saved frames.
 *
 * This is an *adapter*, deliberately outside the SDK: it translates one specific wire
 * format (Android intent extras) into the runtime's canonical command schema. The SDK
 * itself knows nothing about intents, adb, or who is driving.
 *
 * Usage:
 *   adb shell am startservice -n com.headless.example/.ControlService \
 *       -e cmd tap --ef x 540 --ef y 270
 *
 * Results append to  <externalFilesDir>/control/results.jsonl
 * Frames land in     <externalFilesDir>/control/frame_NNN_<label>.png
 */
class ControlService : Service() {

    private companion object {
        const val CHANNEL_ID = "headless_automation"
        const val NOTIFICATION_ID = 4711
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var runtime: HeadlessRuntime? = null
    private var executor: CommandExecutor? = null

    private val outputDir: File by lazy {
        File(getExternalFilesDir(null) ?: cacheDir, "control").apply { mkdirs() }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // MUST become a real foreground service, not merely be *started* as one.
        // Observed failure: started via `am start-foreground-service` but without calling
        // startForeground(), Android dropped this process to the cached bucket as soon as a
        // command finished and reclaimed it ("Process com.headless.example has died: cch+5 CEM"),
        // destroying the session and its virtual display between commands. An agent session
        // must outlive individual commands, so the process has to hold foreground priority.
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): android.app.Notification {
        val mgr = getSystemService(android.app.NotificationManager::class.java)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            mgr.createNotificationChannel(
                android.app.NotificationChannel(
                    CHANNEL_ID,
                    "Headless automation session",
                    android.app.NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        return android.app.Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Headless automation active")
            .setContentText("Holding a hidden display session")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val cmdName = intent?.getStringExtra("cmd")
        if (cmdName == null) {
            writeLine("""{"error":"missing 'cmd' extra"}""")
            return START_NOT_STICKY
        }

        scope.launch {
            try {
                val command = parse(cmdName, intent)
                if (command == null) {
                    writeLine("""{"cmd":"$cmdName","error":"unknown or malformed command"}""")
                    return@launch
                }
                val exec = ensureExecutor()
                val result = exec.execute(command)
                writeLine(toJson(result))
            } catch (e: Throwable) {
                writeLine("""{"cmd":"$cmdName","error":"${esc(e.javaClass.simpleName + ": " + e.message)}"}""")
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureExecutor(): CommandExecutor {
        executor?.let { return it }

        // Shizuku's binder arrives asynchronously via its ContentProvider after process
        // start, so a cold-started service must wait for it rather than treating an
        // immediate negative ping as "Shizuku isn't installed".
        val backend = com.headless.android.privilege.ShizukuBackend()
        if (!backend.awaitAvailable()) {
            throw com.headless.android.ShizukuUnavailableException(
                "Shizuku binder did not arrive within timeout (server may be stopped, " +
                    "or this app's authorization was revoked by a reinstall)"
            )
        }

        val rt = runBlocking {
            val r = HeadlessAutomation.start(applicationContext, backend)
            if (!r.isAuthorized()) r.requestAuthorization()
            r
        }
        runtime = rt

        // A previous runtime may have been killed (OEM task cleaner / LMKD) without
        // running its own cleanup, leaving live virtual displays and stale IME records
        // that make the user's physical keyboard misbehave. Report and repair first.
        val stale = rt.cleanUpStaleState()
        writeLine("""{"event":"startupCleanup","detail":"${esc(stale.summary())}"}""")

        return CommandExecutor(rt, outputDir).also { executor = it }
    }

    private fun parse(name: String, intent: Intent): AutomationCommand? = when (name.lowercase()) {
        "open", "opensession" -> AutomationCommand.OpenSession
        "close", "closesession" -> AutomationCommand.CloseSession
        "observe", "screenshot" -> AutomationCommand.Observe
        "enter", "pressenter" -> AutomationCommand.PressEnter
        "back", "pressback" -> AutomationCommand.PressBack
        "launch" -> intent.getStringExtra("pkg")?.let { AutomationCommand.LaunchApp(it) }
        "stop" -> intent.getStringExtra("pkg")?.let { AutomationCommand.StopApp(it) }
        "type" -> intent.getStringExtra("text")?.let { AutomationCommand.TypeText(it) }
        "tap" -> {
            val x = intent.getFloatExtra("x", Float.NaN)
            val y = intent.getFloatExtra("y", Float.NaN)
            if (x.isNaN() || y.isNaN()) null else AutomationCommand.Tap(x, y)
        }
        "swipe" -> {
            val x1 = intent.getFloatExtra("x1", Float.NaN)
            val y1 = intent.getFloatExtra("y1", Float.NaN)
            val x2 = intent.getFloatExtra("x2", Float.NaN)
            val y2 = intent.getFloatExtra("y2", Float.NaN)
            val dur = intent.getLongExtra("ms", 300L)
            if (x1.isNaN() || y1.isNaN() || x2.isNaN() || y2.isNaN()) null
            else AutomationCommand.Swipe(x1, y1, x2, y2, dur)
        }
        else -> null
    }

    /** Hand-rolled JSON: the result shape is tiny and fixed, and this avoids a dependency. */
    private fun toJson(r: CommandResult): String = when (r) {
        is CommandResult.Verified -> """
            {"outcome":"VERIFIED","cmd":"${esc(r.command.toString())}","detail":"${esc(r.detail)}",
            "screenshot":"${esc(r.screenshotPath)}","package":"${esc(r.currentPackage)}",
            "changeRatio":${r.changeRatio ?: "null"},"durationMs":${r.durationMs}}
        """.trimIndent().replace("\n", " ")

        is CommandResult.Failed -> """
            {"outcome":"FAILED","cmd":"${esc(r.command.toString())}","reason":"${esc(r.reason)}",
            "screenshot":"${esc(r.screenshotPath)}","package":"${esc(r.currentPackage)}",
            "durationMs":${r.durationMs}}
        """.trimIndent().replace("\n", " ")

        is CommandResult.Uncertain -> """
            {"outcome":"UNCERTAIN","cmd":"${esc(r.command.toString())}","reason":"${esc(r.reason)}",
            "screenshot":"${esc(r.screenshotPath)}","package":"${esc(r.currentPackage)}",
            "changeRatio":${r.changeRatio ?: "null"},"durationMs":${r.durationMs}}
        """.trimIndent().replace("\n", " ")
    }

    private fun esc(s: String?): String =
        (s ?: "").replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")

    private fun writeLine(line: String) {
        val f = File(outputDir, "results.jsonl")
        if (f.exists()) f.appendText("\n$line") else f.writeText(line)
        android.util.Log.i("HeadlessControl", line)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { executor?.shutdown() } catch (_: Throwable) {}
        try { runtime?.close() } catch (_: Throwable) {}
    }
}
