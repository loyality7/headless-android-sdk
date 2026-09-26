package com.headless.android.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionCheckpointTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `serialize and deserialize preserves all checkpoint fields`() {
        val checkpoint = SessionCheckpoint(
            sessionId = "sess-abc-123",
            displayId = 15,
            targetPackage = "com.android.chrome",
            lastVerifiedState = "com.android.chrome|search_page",
            lastCompletedAction = "TypeText(hello world)",
            pendingAction = "PressEnter",
            timestampMs = 1790408000000L
        )

        val serialized = checkpoint.serialize()
        val restored = SessionCheckpoint.deserialize(serialized)

        assertNotNull(restored)
        assertEquals(checkpoint.sessionId, restored?.sessionId)
        assertEquals(checkpoint.displayId, restored?.displayId)
        assertEquals(checkpoint.targetPackage, restored?.targetPackage)
        assertEquals(checkpoint.lastVerifiedState, restored?.lastVerifiedState)
        assertEquals(checkpoint.lastCompletedAction, restored?.lastCompletedAction)
        assertEquals(checkpoint.pendingAction, restored?.pendingAction)
        assertEquals(checkpoint.timestampMs, restored?.timestampMs)
    }

    @Test
    fun `checkpoint store persists to disk across instances and clears cleanly`() {
        val dir = tempFolder.newFolder("checkpoints")
        val store1 = SessionCheckpointStore(dir)

        assertFalse(store1.hasCheckpoint())
        assertNull(store1.load())

        val checkpoint = SessionCheckpoint(
            sessionId = "sess-disk",
            displayId = 3,
            targetPackage = "org.mozilla.firefox",
            lastVerifiedState = "org.mozilla.firefox",
            lastCompletedAction = "LaunchApp(org.mozilla.firefox)",
            pendingAction = null
        )

        store1.save(checkpoint)
        assertTrue(store1.hasCheckpoint())

        // Read through a second store instance pointing to same directory (simulating new process)
        val store2 = SessionCheckpointStore(dir)
        val loaded = store2.load()

        assertNotNull(loaded)
        assertEquals("sess-disk", loaded?.sessionId)
        assertEquals("org.mozilla.firefox", loaded?.targetPackage)

        store2.clear()
        assertFalse(store2.hasCheckpoint())
        assertNull(store2.load())
    }

    @Test
    fun `malformed or empty checkpoint file loads as null safely`() {
        val dir = tempFolder.newFolder("corrupted")
        val store = SessionCheckpointStore(dir)
        java.io.File(dir, "session_checkpoint.txt").writeText("invalid|truncated")

        assertNull(store.load())
    }
}
