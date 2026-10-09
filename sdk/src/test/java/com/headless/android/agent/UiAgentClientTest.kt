package com.headless.android.agent

import com.headless.android.AccessibilityAgentException
import com.headless.android.privilege.BackendCapability
import com.headless.android.privilege.BackendInfo
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.privilege.ShellResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class UiAgentClientTest {

    /**
     * Stands in for the agent process. Like Shizuku's remote process it throws
     * IllegalArgumentException("process hasn't exited") from exitValue() while running —
     * the behavior that broke the first version of the client on the second call.
     */
    private class FakeAgentProcess(private val replyFor: (JSONObject) -> JSONObject) : Process() {
        private val replies = PipedOutputStream()
        private val stdout = PipedInputStream(replies, 1 shl 16)
        private val pending = ByteArrayOutputStream()
        @Volatile var destroyed = false

        init { send(JSONObject().put("ready", true).put("uid", 2000)) }

        private fun send(json: JSONObject) {
            replies.write((json.toString() + "\n").toByteArray())
            replies.flush()
        }

        private val stdin = object : OutputStream() {
            override fun write(b: Int) {
                if (b == 10) {
                    val line = pending.toString(Charsets.UTF_8)
                    pending.reset()
                    send(replyFor(JSONObject(line)))
                } else {
                    pending.write(b)
                }
            }
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = stdout
        override fun getErrorStream(): InputStream = java.io.ByteArrayInputStream(ByteArray(0))
        override fun waitFor(): Int = 0
        override fun exitValue(): Int {
            if (!destroyed) throw IllegalArgumentException("process hasn't exited")
            return 0
        }
        override fun destroy() { destroyed = true }
    }

    private class FakeBackend(val process: Process?) : PrivilegeBackend {
        var spawns = 0
        override val name = "Fake"
        override fun isAvailable() = true
        override fun isAuthorized() = true
        override suspend fun requestAuthorization() = true
        override fun isAlive() = true
        override fun capabilities(): Set<BackendCapability> = emptySet()
        override fun info() = BackendInfo("Fake", 2000, null, null)
        override fun getSystemServiceBinder(serviceName: String): android.os.IBinder = throw UnsupportedOperationException()
        override fun shell(command: Array<String>) = ShellResult(0, "", "")
        override fun close() {}
        override fun spawn(command: Array<String>): Process? {
            spawns++
            return process
        }
    }

    private fun ok(req: JSONObject) = JSONObject().put("id", req.getInt("id")).put("ok", true).put("op", req.getString("op"))

    @Test
    fun `consecutive calls reuse one running agent process`() {
        val process = FakeAgentProcess { ok(it) }
        val backend = FakeBackend(process)
        val agent = UiAgent(backend, "/data/app/x/base.apk")

        assertEquals("ping", agent.call("ping").getString("op"))
        assertEquals("tree", agent.call("tree", 4).getString("op"))
        assertEquals("act", agent.call("act", 4) { put("ref", "1/#a") }.getString("op"))

        assertEquals("the agent must be started once, not per call", 1, backend.spawns)
        assertTrue(agent.isRunning())
        agent.close()
        assertTrue(process.destroyed)
    }

    @Test
    fun `request carries the display and fields`() {
        var seen: JSONObject? = null
        val agent = UiAgent(FakeBackend(FakeAgentProcess { seen = it; ok(it) }), "/x.apk")
        agent.call("act", 7) { put("ref", "9/0#ff"); put("action", "click") }

        assertEquals(7, seen!!.getInt("display"))
        assertEquals("9/0#ff", seen!!.getString("ref"))
        assertEquals("click", seen!!.getString("action"))
    }

    @Test
    fun `agent errors are surfaced, stale ones marked`() {
        val agent = UiAgent(
            FakeBackend(FakeAgentProcess {
                JSONObject().put("id", it.getInt("id")).put("ok", false).put("stale", true).put("error", "window 3 is gone")
            }),
            "/x.apk"
        )
        try {
            agent.call("act", 4)
            fail("expected an exception")
        } catch (e: AccessibilityAgentException) {
            assertTrue(e.message!!, e.message!!.startsWith("stale:"))
        }
    }

    @Test
    fun `backend that cannot spawn gives a clear error`() {
        try {
            UiAgent(FakeBackend(null), "/x.apk").call("ping")
            fail("expected an exception")
        } catch (e: AccessibilityAgentException) {
            assertTrue(e.message!!, e.message!!.contains("cannot spawn"))
        }
    }
}
