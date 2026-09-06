package com.headless.example

import android.graphics.Bitmap
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.headless.android.HeadlessAutomation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Demonstrates the Headless Android SDK exactly as a third-party app would consume it.
 * No Binder, display, ImageReader, InputManager, or Shizuku code appears here — only
 * calls into [HeadlessAutomation] / [com.headless.android.HeadlessSession].
 */
class MainActivity : AppCompatActivity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this)
        logView = TextView(this).apply {
            setPadding(32, 32, 32, 32)
            textSize = 14f
        }
        scrollView.addView(logView)
        setContentView(scrollView)

        runDemo()
    }

    private fun log(message: String) {
        runOnUiThread {
            val current = logView.text.toString()
            logView.text = if (current.isEmpty()) message else "$current\n$message"
        }
    }

    private fun runDemo() {
        lifecycleScope.launch {
            try {
                log("Starting Headless Android SDK...")
                val runtime = HeadlessAutomation.start(applicationContext)

                log("Shizuku authorized: ${runtime.isAuthorized()}")
                if (!runtime.isAuthorized()) {
                    log("Requesting authorization...")
                    val granted = runtime.requestAuthorization()
                    log("Authorization granted: $granted")
                    if (!granted) {
                        log("Cannot continue without privilege authorization.")
                        return@launch
                    }
                }

                log("Creating session (this creates the hidden virtual display)...")
                val session = withContext(Dispatchers.IO) { runtime.createSession() }
                log("Session created: id=${session.id} displayId=${session.displayId}")

                log("Launching com.android.chrome onto the hidden display...")
                withContext(Dispatchers.IO) { session.launch("com.android.chrome") }
                log("Chrome launched and verified on display ${session.displayId}")

                log("Tapping the search field...")
                withContext(Dispatchers.IO) {
                    session.tap(540f, 270f)
                    session.tap(540f, 65f)
                }

                log("Typing \"RRR movie\"...")
                withContext(Dispatchers.IO) { session.type("RRR movie") }

                log("Pressing Enter...")
                withContext(Dispatchers.IO) { session.pressEnter() }

                log("Waiting for results to render...")
                kotlinx.coroutines.delay(3000)

                log("Capturing screenshot...")
                val screenshot = withContext(Dispatchers.IO) { session.screenshot() }
                log("Screenshot captured: ${screenshot.width}x${screenshot.height} displayId=${screenshot.displayId}")

                val savedPath = withContext(Dispatchers.IO) { saveScreenshot(screenshot.bitmap) }
                log("Screenshot saved: $savedPath")

                log("Closing session...")
                session.close()

                log("SUCCESS: end-to-end demo completed.")
            } catch (e: Exception) {
                log("FAILED: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    private fun saveScreenshot(bitmap: Bitmap): String {
        val dir = getExternalFilesDir(null) ?: cacheDir
        val file = File(dir, "headless_screenshot_${System.currentTimeMillis()}.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        return file.absolutePath
    }
}
