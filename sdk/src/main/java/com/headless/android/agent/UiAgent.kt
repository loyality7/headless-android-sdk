package com.headless.android.agent

import com.headless.android.AccessibilityAgentException
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.Closeable
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Client for [UiAgentMain]: starts it as a shell-UID `app_process` through the privilege
 * backend and exchanges one JSON line per request/response over its pipes.
 *
 * One agent process serves every session; display ids are passed per request. Requests are
 * serialized. A timeout kills the process so the next call starts a clean one rather than
 * reading a stale reply.
 */
class UiAgent(
    private val backend: PrivilegeBackend,
    private val apkPath: String
) : Closeable {

    companion object {
        private const val OP = "UiAgent"
        private const val START_TIMEOUT_MS = 15_000L
        const val DEFAULT_TIMEOUT_MS = 10_000L
    }

    private val lock = Any()
    private var process: Process? = null
    private var writer: BufferedWriter? = null
    private val lines = LinkedBlockingQueue<String>()
    private val stderrTail = ConcurrentLinkedDeque<String>()
    private var nextId = 1

    /** Sends [op] for [display] and returns the agent's response body. Throws on `ok=false`. */
    fun call(
        op: String,
        display: Int? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        fill: JSONObject.() -> Unit = {}
    ): JSONObject = synchronized(lock) {
        ensureStarted()
        val id = nextId++
        val request = JSONObject().put("id", id).put("op", op)
        if (display != null) request.put("display", display)
        request.fill()

        try {
            writer!!.write(request.toString())
            writer!!.newLine()
            writer!!.flush()
        } catch (e: Throwable) {
            kill()
            throw AccessibilityAgentException("could not write to accessibility agent: ${e.message}", e)
        }

        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            val line = if (remaining > 0) lines.poll(remaining, TimeUnit.MILLISECONDS) else null
            if (line == null) {
                val reason = diagnose("no reply to '$op' within ${timeoutMs}ms")
                kill()
                throw AccessibilityAgentException(reason)
            }
            val response = try { JSONObject(line) } catch (_: Throwable) { continue }
            if (response.optInt("id", -1) != id) continue
            if (!response.optBoolean("ok")) {
                val message = response.optString("error", "unknown agent error")
                throw AccessibilityAgentException(if (response.optBoolean("stale")) "stale: $message" else message)
            }
            return@synchronized response
        }
        @Suppress("UNREACHABLE_CODE")
        error("unreachable")
    }

    fun isRunning(): Boolean = synchronized(lock) { isAlive(process) }

    private fun ensureStarted() {
        if (isAlive(process)) return
        kill()
        lines.clear()
        stderrTail.clear()

        val command = arrayOf(
            "sh", "-c",
            "CLASSPATH='$apkPath' exec app_process /system/bin --nice-name=headless_ui_agent " +
                "com.headless.android.agent.UiAgentMain"
        )
        val p = backend.spawn(command)
            ?: throw AccessibilityAgentException("privilege backend '${backend.name}' cannot spawn long-lived processes")
        process = p
        writer = p.outputStream.bufferedWriter()
        daemon("UiAgent-out") { p.inputStream.bufferedReader().forEachLine { lines.offer(it) } }
        daemon("UiAgent-err") {
            p.errorStream.bufferedReader().forEachLine {
                stderrTail.addLast(it)
                while (stderrTail.size > 20) stderrTail.pollFirst()
            }
        }

        val first = lines.poll(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val ready = first?.let { runCatching { JSONObject(it).optBoolean("ready") }.getOrDefault(false) } == true
        if (!ready) {
            val reason = diagnose("accessibility agent did not become ready")
            kill()
            throw AccessibilityAgentException(reason)
        }
        HeadlessLog.i(OP, "accessibility agent started: $first")
    }

    /**
     * Liveness without Process.isAlive(): Shizuku's remote process throws
     * IllegalArgumentException("process hasn't exited") from exitValue() while it is running,
     * and the default isAlive() only expects IllegalThreadStateException.
     */
    private fun isAlive(p: Process?): Boolean {
        if (p == null) return false
        return try {
            p.exitValue()
            false
        } catch (_: IllegalThreadStateException) {
            true
        } catch (_: IllegalArgumentException) {
            true
        }
    }

    private fun diagnose(prefix: String): String {
        val alive = isAlive(process)
        val tail = stderrTail.joinToString(" | ").take(500)
        return "$prefix (agentAlive=$alive${if (tail.isNotEmpty()) ", stderr=$tail" else ""})"
    }

    private fun kill() {
        try { process?.destroy() } catch (_: Throwable) {}
        process = null
        writer = null
    }

    private fun daemon(name: String, body: () -> Unit) {
        Thread({ try { body() } catch (_: Throwable) {} }, name).apply { isDaemon = true; start() }
    }

    override fun close() {
        synchronized(lock) {
            try {
                if (isAlive(process)) {
                    writer?.write("""{"id":0,"op":"quit"}""")
                    writer?.newLine()
                    writer?.flush()
                }
            } catch (_: Throwable) {}
            kill()
        }
    }
}
