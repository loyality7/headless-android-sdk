package com.headless.example

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.headless.android.HeadlessAutomation
import com.headless.android.SessionEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Service-Manager and Status Dashboard.
 *
 * NOTE (#20): Headless Android SDK automation must be service-only and never rely on an
 * Activity to host automation sessions. This Activity exists solely as an optional manual
 * dashboard/diagnostics tool and does NOT auto-execute on launch, preventing Display 0 hijacking.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView
    private lateinit var btnStartService: Button
    private lateinit var btnStopService: Button
    private lateinit var btnRunDemo: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }

        val titleView = TextView(this).apply {
            text = "Headless Android SDK"
            textSize = 20f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        root.addView(titleView)

        val subtitleView = TextView(this).apply {
            text = "Service-Only Runtime Dashboard (Display 0 Isolation Guard Active)"
            textSize = 13f
            setPadding(0, 4, 0, 24)
        }
        root.addView(subtitleView)

        statusView = TextView(this).apply {
            text = "Status: Checking environment..."
            textSize = 14f
            setPadding(24, 24, 24, 24)
            setBackgroundColor(0xFFEEEEEE.toInt())
        }
        root.addView(statusView)

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 24, 0, 24)
        }

        btnStartService = Button(this).apply {
            text = "Start Service"
            setOnClickListener { startControlService() }
        }
        buttonBar.addView(btnStartService)

        btnStopService = Button(this).apply {
            text = "Stop Service"
            setOnClickListener { stopControlService() }
        }
        buttonBar.addView(btnStopService)

        btnRunDemo = Button(this).apply {
            text = "Run Demo"
            setOnClickListener { runManualDemo() }
        }
        buttonBar.addView(btnRunDemo)

        root.addView(buttonBar)

        val scroll = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }
        logView = TextView(this).apply {
            setPadding(16, 16, 16, 16)
            textSize = 12f
            setBackgroundColor(0xFFF8F9FA.toInt())
        }
        scroll.addView(logView)
        root.addView(scroll)

        setContentView(root)

        updateStatus()
        log("Dashboard initialized. Automation is service-driven via ControlService.")
        log("Use adb to issue commands directly without touching Display 0:")
        log("  adb shell am start-foreground-service -n com.headless.example/.ControlService -e cmd open")
    }

    private fun startControlService() {
        val intent = Intent(this, ControlService::class.java).apply {
            putExtra("cmd", "open")
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        log("Dispatched open command to ControlService.")
        updateStatus()
    }

    private fun stopControlService() {
        val intent = Intent(this, ControlService::class.java).apply {
            putExtra("cmd", "close")
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        log("Dispatched close command to ControlService.")
        updateStatus()
    }

    private fun updateStatus() {
        lifecycleScope.launch {
            val backend = com.headless.android.privilege.ShizukuBackend()
            val available = backend.isAvailable()
            val authorized = backend.isAuthorized()
            statusView.text = "Privilege Backend (Shizuku):\n" +
                "  Available: $available | Authorized: $authorized\n" +
                "Runtime Policy: Service-Only (No Display 0 intrusion)"
        }
    }

    private fun log(message: String) {
        android.util.Log.i("HeadlessExample", message)
        runOnUiThread {
            val current = logView.text.toString()
            logView.text = if (current.isEmpty()) message else "$current\n$message"
        }
    }

    /**
     * Optional manual demo run, executed only upon explicit button click.
     * Never called automatically in onCreate (#20).
     */
    private fun runManualDemo() {
        btnRunDemo.isEnabled = false
        lifecycleScope.launch {
            try {
                log("--- Starting Manual Headless Demo ---")
                val runtime = HeadlessAutomation.start(applicationContext)

                if (!runtime.isAuthorized()) {
                    log("Requesting privilege authorization...")
                    val granted = runtime.requestAuthorization()
                    if (!granted) {
                        log("Cannot continue without privilege authorization.")
                        btnRunDemo.isEnabled = true
                        return@launch
                    }
                }

                // Listen to live events
                val eventsJob = launch {
                    runtime.events.collect { event ->
                        log("[EVENT] ${event.javaClass.simpleName}: $event")
                    }
                }

                log("Creating session (hidden virtual display)...")
                val session = withContext(Dispatchers.IO) { runtime.createSession() }
                log("Session created: id=${session.id} displayId=${session.displayId}")

                log("Launching com.android.chrome onto Display ${session.displayId}...")
                withContext(Dispatchers.IO) {
                    session.stopApp("com.android.chrome")
                    session.launch("com.android.chrome", Uri.parse("https://google.com"))
                }
                log("Chrome launched on display ${session.displayId}")

                kotlinx.coroutines.delay(2000)

                log("Probing Display 0 status...")
                val preStatus = withContext(Dispatchers.IO) { session.checkDisplayZero() }
                log("Display 0 status: ${preStatus.detail}")

                log("Tapping omnibox and typing query on hidden display...")
                withContext(Dispatchers.IO) {
                    session.tap(540f, 270f)
                    session.tap(540f, 65f)
                    Thread.sleep(1000)
                    session.type("RRR movie")
                    session.pressEnter()
                }

                kotlinx.coroutines.delay(3000)

                log("Capturing screenshot from virtual display...")
                val screenshot = withContext(Dispatchers.IO) { session.screenshot() }
                log("Captured screenshot: ${screenshot.width}x${screenshot.height}")

                val savedPath = withContext(Dispatchers.IO) { saveScreenshot(screenshot.bitmap) }
                log("Saved frame to: $savedPath")

                log("Closing session...")
                withContext(Dispatchers.IO) { session.close() }
                eventsJob.cancel()
                log("--- Demo Completed Successfully ---")
            } catch (e: Throwable) {
                log("Demo error: ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                btnRunDemo.isEnabled = true
                updateStatus()
            }
        }
    }

    private fun saveScreenshot(bitmap: Bitmap): String {
        val dir = getExternalFilesDir(null) ?: cacheDir
        val file = File(dir, "manual_demo_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }
}
