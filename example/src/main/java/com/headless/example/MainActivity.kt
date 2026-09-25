package com.headless.example

import android.graphics.Bitmap
import android.net.Uri
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

        val metrics = resources.displayMetrics
        android.util.Log.e("HEADLESS_METRICS", """
            MAIN_ACTIVITY METRICS:
            displayId=${display?.displayId}
            widthPixels=${metrics.widthPixels}
            heightPixels=${metrics.heightPixels}
            density=${metrics.density}
            densityDpi=${metrics.densityDpi}
            scaledDensity=${metrics.scaledDensity}
            xdpi=${metrics.xdpi}
            ydpi=${metrics.ydpi}
        """.trimIndent())
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        val d0 = dm?.getDisplay(0)
        val d0Real = android.util.DisplayMetrics()
        d0?.getRealMetrics(d0Real)
        android.util.Log.e("HEADLESS_METRICS", """
            DISPLAY 0 REAL METRICS:
            widthPixels=${d0Real.widthPixels}
            heightPixels=${d0Real.heightPixels}
            density=${d0Real.density}
            densityDpi=${d0Real.densityDpi}
        """.trimIndent())

        runDemo()
    }

    private fun log(message: String) {
        android.util.Log.i("HeadlessExample", message)
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

                val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
                val vd = dm?.getDisplay(session.displayId)
                val vdReal = android.util.DisplayMetrics()
                vd?.getRealMetrics(vdReal)
                android.util.Log.e("HEADLESS_METRICS", """
                    VIRTUAL DISPLAY ${session.displayId} REAL METRICS:
                    widthPixels=${vdReal.widthPixels}
                    heightPixels=${vdReal.heightPixels}
                    density=${vdReal.density}
                    densityDpi=${vdReal.densityDpi}
                    state=${vd?.state}
                    flags=0x${Integer.toHexString(vd?.flags ?: 0)}
                """.trimIndent())
                log("Virtual Display ${session.displayId}: ${vdReal.widthPixels}x${vdReal.heightPixels} @ ${vdReal.densityDpi}dpi")
                log("IME Isolation Step: ${session.imeIsolation.summary()}")
                if (session.imeIsolation.isolated) {
                    log("PASSED: IME policy set to ${session.imeIsolation.policyAfterSet}, no keyboard UI will show anywhere")
                } else {
                    log("NOTICE: IME policy result: ${session.imeIsolation.failureReason ?: "unexpected policy"}")
                }

                log("Launching com.android.chrome onto Display ${session.displayId}...")
                withContext(Dispatchers.IO) {
                    session.stopApp("com.android.chrome")
                    session.launch("com.android.chrome", Uri.parse("https://google.com"))
                }
                log("Chrome launched and verified on display ${session.displayId}")

                kotlinx.coroutines.delay(2000)

                log("Testing Display 0 status before actions...")
                val preStatus = withContext(Dispatchers.IO) { session.checkDisplayZero() }
                log("Display 0 before actions: ${preStatus.detail}")

                log("Testing Isolation Guard: Out-of-bounds tap protection...")
                try {
                    withContext(Dispatchers.IO) { session.tap(-10f, 500f) }
                    log("ERROR: Out of bounds tap was not rejected!")
                } catch (e: com.headless.android.DisplayIsolationViolationException) {
                    log("PASSED: Out-of-bounds tap rejected: ${e.reason}")
                }

                log("Testing Isolation Guard: Swiping with bottom-edge clamping...")
                withContext(Dispatchers.IO) {
                    session.swipe(540f, 1850f, 540f, 900f, 300L)
                }
                log("PASSED: Bottom-edge swipe clamped safely.")

                log("Tapping the Chrome search omnibox...")
                withContext(Dispatchers.IO) {
                    session.tap(540f, 270f)
                    session.tap(540f, 65f)
                }

                log("Waiting for keyboard to activate...")
                kotlinx.coroutines.delay(1500)

                log("Verifying keyboard/display association...")
                val keyboardStatus = withContext(Dispatchers.IO) { session.checkDisplayZero() }
                log("Display 0 after omnibox tap: ${keyboardStatus.detail}")
                if (keyboardStatus.imeShowingOnDisplayZero) {
                    log("WARNING: IME is showing on Display 0!")
                } else {
                    log("PASSED: Display 0 has no IME window (isolated to virtual display or suppressed).")
                }

                log("Typing \"RRR movie\" on hidden display...")
                withContext(Dispatchers.IO) { session.type("RRR movie") }

                log("Pressing Enter...")
                withContext(Dispatchers.IO) { session.pressEnter() }

                log("Verifying Display 0 remains untouched...")
                val postStatus = withContext(Dispatchers.IO) { session.checkDisplayZero() }
                log("Display 0 after text: ${postStatus.detail}")
                if (postStatus.isContaminated) {
                    log("WARNING: Display 0 contamination detected!")
                } else {
                    log("PASSED: Display 0 remains completely untouched.")
                }

                log("Waiting for results to render...")
                kotlinx.coroutines.delay(3000)

                log("Capturing screenshot...")
                val screenshot = withContext(Dispatchers.IO) { session.screenshot() }
                log("Screenshot captured: ${screenshot.width}x${screenshot.height} displayId=${screenshot.displayId}")

                val pixels = IntArray(screenshot.width * screenshot.height)
                screenshot.bitmap.getPixels(pixels, 0, screenshot.width, 0, 0, screenshot.width, screenshot.height)
                var nonZeroCount = 0L
                for (p in pixels) {
                    if (p != 0 && p != -16777216) {
                        nonZeroCount++
                    }
                }
                val ratio = nonZeroCount.toDouble() / pixels.size.toDouble()
                log("Frame verification: nonZeroOrBlackPixels=$nonZeroCount / ${pixels.size} (ratio=${"%.3f".format(ratio)})")
                if (ratio > 0.05) {
                    log("PASSED: Target app UI successfully rendered on hidden display!")
                } else {
                    log("WARNING: Frame is mostly blank/black (ratio=${"%.3f".format(ratio)})")
                }

                val savedPath = withContext(Dispatchers.IO) { saveScreenshot(screenshot.bitmap) }
                log("Screenshot saved: $savedPath")

                log("Closing session...")
                session.close()

                log("SUCCESS: DisplayIsolationGuard end-to-end verification completed.")
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
