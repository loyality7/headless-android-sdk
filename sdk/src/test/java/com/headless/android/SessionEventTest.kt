package com.headless.android

import com.headless.android.command.AutomationCommand
import com.headless.android.command.CommandResult
import com.headless.android.state.SessionState
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionEventTest {

    @Test
    fun `event hierarchy preserves base properties and timestamps`() {
        val now = System.currentTimeMillis()
        val event = SessionEvent.DisplayCreated(
            sessionId = "sess-123",
            displayId = 42,
            width = 1080,
            height = 1920,
            densityDpi = 320,
            timestampMs = now
        )

        assertEquals("sess-123", event.sessionId)
        assertEquals(42, event.displayId)
        assertEquals(now, event.timestampMs)
        assertEquals(1080, event.width)
        assertEquals(1920, event.height)
        assertEquals(320, event.densityDpi)
    }

    @Test
    fun `action completed event carries duration and outcome`() {
        val event = SessionEvent.ActionCompleted(
            sessionId = "sess-1",
            action = "type",
            displayId = 5,
            success = true,
            durationMs = 45L,
            outcome = "success"
        )

        assertTrue(event.success)
        assertEquals("type", event.action)
        assertEquals(45L, event.durationMs)
        assertEquals("success", event.outcome)
    }

    @Test
    fun `app crashed event carries failure reason`() {
        val event = SessionEvent.AppCrashed(
            sessionId = "sess-1",
            packageName = "org.chromium.chrome",
            displayId = 2,
            reason = "Process killed by LMKD"
        )

        assertEquals("org.chromium.chrome", event.packageName)
        assertEquals(2, event.displayId)
        assertEquals("Process killed by LMKD", event.reason)
    }

    @Test
    fun `contamination alert captures display zero details`() {
        val event = SessionEvent.ContaminationAlert(
            sessionId = "sess-test",
            detail = "IME window active on Display 0",
            displayZeroPackage = "com.google.android.inputmethod.latin"
        )

        assertEquals("sess-test", event.sessionId)
        assertEquals("com.google.android.inputmethod.latin", event.displayZeroPackage)
        assertTrue(event.detail.contains("Display 0"))
    }

    @Test
    fun `command executed event contains command and result`() {
        val cmd = AutomationCommand.PressEnter
        val res = CommandResult.Verified(
            command = cmd,
            detail = "enter pressed",
            screenshotPath = null,
            currentPackage = "com.android.chrome",
            changeRatio = 0.02f,
            durationMs = 120L
        )
        val event = SessionEvent.CommandExecuted(
            sessionId = "sess-exec",
            command = cmd,
            result = res
        )

        assertEquals("sess-exec", event.sessionId)
        assertEquals(cmd, event.command)
        assertEquals(res, event.result)
    }

    @Test
    fun `backend lost event reports transport failure`() {
        val event = SessionEvent.BackendLost(
            sessionId = "sess-bk",
            backendName = "Shizuku",
            reason = "Binder process died"
        )

        assertEquals("Shizuku", event.backendName)
        assertEquals("Binder process died", event.reason)
    }

    @Test
    fun `state changed event records session snapshot`() {
        val state = SessionState(
            sessionId = "sess-st",
            displayId = 3,
            sessionOpen = true,
            displayAlive = true,
            currentPackage = "com.example.app",
            capturedAtMillis = 1000L
        )
        val event = SessionEvent.StateChanged(
            sessionId = "sess-st",
            state = state
        )

        assertEquals("sess-st", event.sessionId)
        assertEquals(state, event.state)
        assertTrue(event.state.displayAlive)
    }

    @Test
    fun `shared flow event stream buffers replay and drops oldest without blocking`() = runBlocking {
        val flow = MutableSharedFlow<SessionEvent>(
            replay = 3,
            extraBufferCapacity = 2,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        for (i in 1..10) {
            val emitted = flow.tryEmit(
                SessionEvent.ActionStarted(
                    sessionId = "sess-flow",
                    action = "action_$i",
                    displayId = 1
                )
            )
            assertTrue("tryEmit must never block or return false with DROP_OLDEST", emitted)
        }

        // With replay = 3 and 10 items emitted, the latest 3 should be received on subscription
        val replayed = flow.asSharedFlow().take(3).toList()
        assertEquals(3, replayed.size)
        assertEquals("action_8", (replayed[0] as SessionEvent.ActionStarted).action)
        assertEquals("action_9", (replayed[1] as SessionEvent.ActionStarted).action)
        assertEquals("action_10", (replayed[2] as SessionEvent.ActionStarted).action)
    }
}
