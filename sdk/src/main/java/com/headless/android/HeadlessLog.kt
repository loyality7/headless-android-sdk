package com.headless.android

import android.util.Log

/**
 * Internal SDK logger. Consumers never touch raw logcat tags directly;
 * [verbose] gates the high-frequency diagnostic path so normal production
 * runs stay quiet while example/dev builds can opt in.
 */
object HeadlessLog {
    private const val TAG = "HeadlessAndroid"

    @Volatile
    var verbose: Boolean = false

    fun d(op: String, message: String) {
        if (verbose) {
            try { Log.d(TAG, "[$op] $message") } catch (_: Throwable) { println("[$TAG][$op] $message") }
        }
    }

    fun i(op: String, message: String) {
        try { Log.i(TAG, "[$op] $message") } catch (_: Throwable) { println("[$TAG][$op] $message") }
    }

    fun w(op: String, message: String, cause: Throwable? = null) {
        try { Log.w(TAG, "[$op] $message", cause) } catch (_: Throwable) { println("[$TAG][$op] $message: $cause") }
    }

    fun e(op: String, message: String, cause: Throwable? = null) {
        try { Log.e(TAG, "[$op] $message", cause) } catch (_: Throwable) { System.err.println("[$TAG][$op] $message: $cause") }
    }

    /** Structured event line: session id, display id, package, operation, duration, outcome. */
    fun event(
        sessionId: String? = null,
        displayId: Int? = null,
        packageName: String? = null,
        op: String,
        durationMs: Long? = null,
        success: Boolean
    ) {
        val parts = buildList {
            sessionId?.let { add("session=$it") }
            displayId?.let { add("display=$it") }
            packageName?.let { add("pkg=$it") }
            add("op=$op")
            durationMs?.let { add("durationMs=$it") }
            add("success=$success")
        }
        Log.i(TAG, parts.joinToString(" "))
    }
}
