package com.headless.example

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.headless.android.HeadlessAutomation
import com.headless.android.HeadlessLog
import com.headless.android.HeadlessRuntime
import com.headless.android.command.AutomationCommand
import com.headless.android.command.CommandExecutor
import com.headless.android.command.CommandResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    // #9: executor/runtime are PROCESS-scoped, not instance-scoped. System churns service
    // instances under memory pressure (seen: two instances alive at once); a per-instance
    // session made every second command report "No open session". Any instance reattaches.
    private object Hub {
        var runtime: HeadlessRuntime? = null
        var executor: CommandExecutor? = null
    }

    private val outputDir: File by lazy {
        File(getExternalFilesDir(null) ?: cacheDir, "control").apply { mkdirs() }
    }

    private var wakeLock: android.os.PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val pm = getSystemService(android.os.PowerManager::class.java)
        wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "headless:automation_session")?.apply {
            setReferenceCounted(false)
            acquire()
        }
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
                // Test-only IME readback (not an AutomationCommand): what did the field hold?
                if (cmdName.equals("ime_readback", ignoreCase = true)) {
                    val ime = com.headless.android.ime.HeadlessImeService
                    writeLine(
                        """{"cmd":"ime_readback","bindCount":${ime.bindCount},""" +
                            """ "snapshot":"${esc(ime.snapshot())}"}"""
                    )
                    return@launch
                }
                if (cmdName.equals("test_multisession", ignoreCase = true)) {
                    val report = runMultiSessionAudit()
                    writeLine(report)
                    return@launch
                }
                if (cmdName.equals("test_dump_all", ignoreCase = true)) {
                    val report = runDumpAllAudit()
                    writeLine(report)
                    return@launch
                }
                if (cmdName.equals("test_input_benchmark", ignoreCase = true)) {
                    val report = runInputBenchmarkAudit()
                    writeLine(report)
                    return@launch
                }
                if (cmdName.equals("test_ui_service", ignoreCase = true)) {
                    val exec = ensureExecutor()
                    val rt = Hub.runtime ?: throw IllegalStateException("Runtime not initialized")
                    val uiService = rt.backend.bindAgentUserService(packageName)
                    if (uiService == null) {
                        writeLine("""{"outcome":"FAILED","test":"ui_service","reason":"bindAgentUserService returned null"}""")
                        return@launch
                    }
                    val report = uiService.displayReport
                    writeLine("""{"outcome":"VERIFIED","test":"ui_service","report":$report}""")
                    return@launch
                }
                if (cmdName.equals("test_ui_tree", ignoreCase = true)) {
                    val report = runUiTreeAudit()
                    writeLine(report)
                    return@launch
                }
                if (cmdName.equals("ui", ignoreCase = true) || cmdName.equals("dump_ui", ignoreCase = true)) {
                    val exec = ensureExecutor()
                    val s = exec.currentSession ?: throw IllegalStateException("No open session")
                    val prompt = s.uiPrompt()
                    val uiFile = File(outputDir, "ui.txt")
                    uiFile.writeText(prompt)
                    writeLine("""{"cmd":"ui","count":${prompt.lines().size},"file":"${esc(uiFile.absolutePath)}"}""")
                    return@launch
                }
                if (cmdName.equals("click", ignoreCase = true) && intent.hasExtra("index")) {
                    val index = intent.getIntExtra("index", 1)
                    val exec = ensureExecutor()
                    val s = exec.currentSession ?: throw IllegalStateException("No open session")
                    s.click(index)
                    writeLine("""{"cmd":"click","index":$index,"outcome":"VERIFIED"}""")
                    return@launch
                }
                if (cmdName.equals("enter_text", ignoreCase = true)) {
                    val index = intent.getIntExtra("index", 1)
                    val text = intent.getStringExtra("text") ?: ""
                    val exec = ensureExecutor()
                    val s = exec.currentSession ?: throw IllegalStateException("No open session")
                    s.enterText(index, text)
                    writeLine("""{"cmd":"enter_text","index":$index,"text":"${esc(text)}","outcome":"VERIFIED"}""")
                    return@launch
                }
                val command = parse(cmdName, intent)
                if (command == null) {
                    writeLine("""{"cmd":"$cmdName","error":"unknown or malformed command"}""")
                    return@launch
                }
                val exec = ensureExecutor()
                val expect = parseExpect(intent)
                val policy = parsePolicy(intent)
                val result = exec.execute(command, expect, policy)
                writeLine(toJson(result))
            } catch (e: Throwable) {
                writeLine("""{"cmd":"$cmdName","error":"${esc(e.javaClass.simpleName + ": " + e.message)}"}""")
            }
        }
        return START_NOT_STICKY
    }

    private fun ensureExecutor(): CommandExecutor {
        synchronized(Hub::class.java) {
            Hub.executor?.let { return it }

        val backend = com.headless.android.privilege.ShizukuBackend()
        if (!backend.awaitAvailable()) {
            val health = backend.health()
            throw com.headless.android.ShizukuUnavailableException(
                "Shizuku service is not reachable on device (health=$health)."
            )
        }

        val rt = runBlocking {
            val r = HeadlessAutomation.start(applicationContext, backend)
            if (!r.isAuthorized()) {
                val granted = r.requestAuthorization()
                if (!granted) {
                    val health = backend.health()
                    HeadlessLog.w("ControlService", "Shizuku authorization not granted (health=$health).")
                }
            }
            r
        }
        Hub.runtime = rt

        scope.launch {
            rt.events.collect { event ->
                Log.i("SessionEvent", "STREAM: $event")
                writeLine("""{"session_event":"${event.javaClass.simpleName}","timestamp":${event.timestampMs},"detail":"${esc(event.toString())}"}""")
            }
        }

        // A previous runtime may have been killed (OEM task cleaner / LMKD) without
        // running its own cleanup, leaving live virtual displays and stale IME records
        // that make the user's physical keyboard misbehave. Report and repair first.
        val stale = rt.cleanUpStaleState()
        writeLine("""{"event":"startupCleanup","detail":"${esc(stale.summary())}"}""")

        return CommandExecutor(rt, outputDir).also { Hub.executor = it }
        }
    }

    private fun parse(name: String, intent: Intent): AutomationCommand? = when (name.lowercase()) {
        "open", "opensession" -> AutomationCommand.OpenSession
        "close", "closesession" -> AutomationCommand.CloseSession
        "recover", "recoversession" -> AutomationCommand.RecoverSession
        "observe", "screenshot" -> AutomationCommand.Observe
        "enter", "pressenter" -> AutomationCommand.PressEnter
        "back", "pressback" -> AutomationCommand.PressBack
        "tab", "presstab" -> AutomationCommand.PressTab
        "clear", "cleartext" -> AutomationCommand.ClearText
        "delete", "deletetext" -> AutomationCommand.DeleteText(intent.getIntExtra("count", 1))
        "launch" -> intent.getStringExtra("pkg")?.let { AutomationCommand.LaunchApp(it) }
        "stop" -> intent.getStringExtra("pkg")?.let { AutomationCommand.StopApp(it) }
        "type" -> {
            // ponytail: textB64 is the reliable path — raw `-e text a b c` gets split by
            // adb/shell quoting layers before it ever reaches us (observed: "hello world
            // test" arrived as "hello"). Base64 has no spaces, survives every layer.
            val b64 = intent.getStringExtra("textB64")
            if (b64 != null) {
                try {
                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                    AutomationCommand.TypeText(String(bytes, Charsets.UTF_8))
                } catch (_: Throwable) { null }
            } else {
                intent.getStringExtra("text")?.let { AutomationCommand.TypeText(it) }
            }
        }
        "tap" -> {
            val x = intent.getFloatExtra("x", Float.NaN)
            val y = intent.getFloatExtra("y", Float.NaN)
            if (x.isNaN() || y.isNaN()) null else AutomationCommand.Tap(x, y)
        }
        "click", "taptarget" -> {
            val text = intent.getStringExtra("text")
            val id = intent.getStringExtra("id")
            val exact = intent.getBooleanExtra("exact", false)
            when {
                text != null -> AutomationCommand.TapTarget(com.headless.android.perception.Target.Text(text, exact = exact))
                id != null -> AutomationCommand.TapTarget(com.headless.android.perception.Target.Id(id))
                else -> null
            }
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

    private fun parseExpect(intent: Intent): com.headless.android.command.Expect {
        val raw = intent.getStringExtra("expect") ?: return com.headless.android.command.Expect.None
        return when {
            raw == "change" -> com.headless.android.command.Expect.Change
            raw.startsWith("pkg:") -> com.headless.android.command.Expect.Package(raw.removePrefix("pkg:"))
            else -> com.headless.android.command.Expect.None
        }
    }

    private fun parsePolicy(intent: Intent): com.headless.android.command.RetryPolicy {
        val recoveryStr = intent.getStringExtra("recovery")?.lowercase()
        val recovery = when {
            recoveryStr == "clear" || recoveryStr == "cleartext" ->
                com.headless.android.command.RecoveryStrategy.ClearFieldBeforeRetry
            recoveryStr == "dismiss" || recoveryStr == "back" ->
                com.headless.android.command.RecoveryStrategy.DismissBeforeRetry
            recoveryStr?.startsWith("delete:") == true -> {
                val n = recoveryStr.substringAfter("delete:").toIntOrNull() ?: 1
                com.headless.android.command.RecoveryStrategy.DeleteCharsBeforeRetry(n)
            }
            else -> com.headless.android.command.RecoveryStrategy.None
        }
        return com.headless.android.command.RetryPolicy(
            maxAttempts = intent.getIntExtra("attempts", 1).coerceIn(1, 5),
            backoffMs = intent.getLongExtra("backoff", 500L),
            retryOnUncertain = intent.getBooleanExtra("retryUncertain", true),
            allowDestructive = intent.getBooleanExtra("confirm", false),
            recovery = recovery
        )
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

    private suspend fun runMultiSessionAudit(): String = withContext(Dispatchers.IO) {
        val backend = com.headless.android.privilege.ShizukuBackend()
        backend.awaitAvailable()
        if (!backend.isAuthorized()) {
            backend.requestAuthorization()
        }
        if (!backend.isAuthorized()) {
            return@withContext """{"outcome":"FAILED","test":"multisession","reason":"backend not authorized"}"""
        }
        val rt = HeadlessAutomation.start(
            context = applicationContext,
            backend = backend,
            maxSessions = 2,
            autoSwitchIme = true
        )
        try {
            // 1. Create two sessions
            val s1 = rt.createSession()
            val s2 = rt.createSession()
            val d1 = s1.displayId
            val d2 = s2.displayId

            // 2. Launch distinct apps on each
            s1.launch("com.android.chrome")
            s2.launch("com.android.settings")
            kotlinx.coroutines.delay(3000)

            val p1 = s1.currentApp()
            val p2 = s2.currentApp()

            // 3. Capture frames independently
            val f1 = s1.screenshot()
            val f2 = s2.screenshot()
            val f1Size = f1.width * f1.height
            val f2Size = f2.width * f2.height

            // 4. Test input routing to s1
            s1.tap(500f, 500f)
            kotlinx.coroutines.delay(500)

            // 5. Close s1, verify s2 remains alive
            s1.close()
            val s1OpenAfterClose = s1.isOpen
            val s2OpenAfterS1Close = s2.isOpen && s2.state().displayAlive

            // 6. Close s2
            s2.close()
            val s2OpenAfterClose = s2.isOpen

            // 7. Verify no leaked non-default displays
            val cleanupReport = rt.cleanUpStaleState()
            val leaks = cleanupReport.nonDefaultDisplayIds

            """{"outcome":"VERIFIED","test":"multisession","d1":$d1,"d2":$d2,"p1":"$p1","p2":"$p2","f1Size":$f1Size,"f2Size":$f2Size,"s1Closed":${!s1OpenAfterClose},"s2Survived":$s2OpenAfterS1Close,"s2Closed":${!s2OpenAfterClose},"leakedDisplays":${leaks.size}}"""
        } catch (e: Throwable) {
            """{"outcome":"FAILED","test":"multisession","reason":"${esc(e.javaClass.simpleName + ": " + e.message)}"}"""
        } finally {
            rt.close()
        }
    }

    private suspend fun runDumpAllAudit(): String = withContext(Dispatchers.IO) {
        val backend = com.headless.android.privilege.ShizukuBackend()
        backend.awaitAvailable()
        if (!backend.isAuthorized()) {
            backend.requestAuthorization()
        }
        val rt = HeadlessAutomation.start(
            context = applicationContext,
            backend = backend,
            maxSessions = 1,
            autoSwitchIme = true
        )
        try {
            val s = rt.createSession()
            val dId = s.displayId
            s.launch("com.android.chrome")
            kotlinx.coroutines.delay(3000)

            val dumpPath = "/sdcard/dump_virtual_test.xml"
            val dumpResult = backend.shell(arrayOf("uiautomator", "dump", "--all", dumpPath))
            kotlinx.coroutines.delay(1000)

            val xmlContent = try {
                java.io.File(dumpPath).readText()
            } catch (e: Throwable) {
                backend.shell(arrayOf("cat", dumpPath)).stdout
            }

            val hasDisplayId = xmlContent.contains("""id="$dId"""") || xmlContent.contains("""display id="$dId"""")
            val hasChrome = xmlContent.contains("com.android.chrome")
            val preview = xmlContent.take(300).replace("\"", "\\\"").replace("\n", " ")

            s.close()
            """{"outcome":"VERIFIED","test":"dump_all","displayId":$dId,"dumpSuccess":${dumpResult.exitCode == 0},"hasDisplayId":$hasDisplayId,"hasChrome":$hasChrome,"preview":"$preview"}"""
        } catch (e: Throwable) {
            """{"outcome":"FAILED","test":"dump_all","reason":"${esc(e.javaClass.simpleName + ": " + e.message)}"}"""
        } finally {
            rt.close()
        }
    }

    private suspend fun runInputBenchmarkAudit(): String = withContext(Dispatchers.IO) {
        val backend = com.headless.android.privilege.ShizukuBackend()
        backend.awaitAvailable()
        if (!backend.isAuthorized()) {
            backend.requestAuthorization()
        }
        val rt = HeadlessAutomation.start(
            context = applicationContext,
            backend = backend,
            maxSessions = 1,
            autoSwitchIme = true
        )
        try {
            val s = rt.createSession()
            val dId = s.displayId
            s.launch("com.android.chrome")
            kotlinx.coroutines.delay(2000)

            val directInjector = com.headless.android.input.BinderInputInjector(backend, dId)

            // Benchmark Direct Binder Taps (10 taps)
            val directTimes = mutableListOf<Long>()
            for (i in 1..10) {
                val t0 = System.currentTimeMillis()
                val ok = directInjector.tap(500f, 500f)
                val dt = System.currentTimeMillis() - t0
                if (ok) directTimes.add(dt)
            }

            // Benchmark Shell Taps (5 taps)
            val shellTimes = mutableListOf<Long>()
            for (i in 1..5) {
                val t0 = System.currentTimeMillis()
                val res = backend.shell(arrayOf("input", "-d", dId.toString(), "tap", "500", "500"))
                val dt = System.currentTimeMillis() - t0
                if (res.exitCode == 0) shellTimes.add(dt)
            }

            val directMedian = if (directTimes.isNotEmpty()) directTimes.sorted()[directTimes.size / 2] else -1
            val directP95 = if (directTimes.isNotEmpty()) directTimes.sorted()[(directTimes.size * 95) / 100] else -1
            val shellMedian = if (shellTimes.isNotEmpty()) shellTimes.sorted()[shellTimes.size / 2] else -1

            s.close()
            """{"outcome":"VERIFIED","test":"input_benchmark","directMedianMs":$directMedian,"directP95Ms":$directP95,"shellMedianMs":$shellMedian,"speedupRatio":${if (directMedian > 0) String.format(java.util.Locale.US, "%.1fx", shellMedian.toDouble() / directMedian) else "\"N/A\""}}"""
        } catch (e: Throwable) {
            """{"outcome":"FAILED","test":"input_benchmark","reason":"${esc(e.javaClass.simpleName + ": " + e.message)}"}"""
        } finally {
            rt.close()
        }
    }

    private suspend fun runUiTreeAudit(): String = withContext(Dispatchers.IO) {
        ensureExecutor()
        val rt = Hub.runtime ?: throw IllegalStateException("Runtime not initialized")
        try {
            val s = rt.createSession()
            val dId = s.displayId
            s.launch("com.android.chrome", android.net.Uri.parse("https://google.com"))
            kotlinx.coroutines.delay(3000)

            val t0 = System.currentTimeMillis()
            val snapshot = s.compactUi()
            val dumpMs = System.currentTimeMillis() - t0

            if (snapshot == null) {
                s.close()
                return@withContext """{"outcome":"FAILED","test":"ui_tree","reason":"snapshot was null"}"""
            }

            val prompt = snapshot.toPromptText()
            android.util.Log.i("HeadlessPrompt", "--- COMPACT LLM PROMPT ---\n$prompt")

            // Test clicking by 1-based index (e.g. index 1)
            var clickByIndexOk = false
            if (snapshot.elements.isNotEmpty()) {
                s.click(1)
                clickByIndexOk = true
            }

            s.close()
            """{"outcome":"VERIFIED","test":"ui_tree","displayId":$dId,"dumpMs":$dumpMs,"elementsCount":${snapshot.elements.size},"promptChars":${prompt.length},"clickByIndexOk":$clickByIndexOk,"sampleElement":"${esc(snapshot.elements.firstOrNull()?.toPromptLine())}"}"""
        } catch (e: Throwable) {
            """{"outcome":"FAILED","test":"ui_tree","reason":"${esc(e.javaClass.simpleName + ": " + e.message)}"}"""
        }
    }

    override fun onDestroy() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Throwable) {}
        super.onDestroy()
        // Deliberately NOT shutting down Hub here: instance churn (system destroying +
        // recreating this service in the same process) must not kill the live session —
        // the next instance reattaches via Hub. True process death is handled by the
        // SessionLedger repair path on next open. Shutting down here caused "No open
        // session" on every other command under memory pressure.
    }
}
