package com.headless.android.agent

import com.headless.android.perception.AgentTreeParser
import com.headless.android.perception.UiHierarchyExtractor
import com.headless.android.perception.UiTreeCompressor
import com.headless.android.state.DisplayGuard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AccessibilityAgentProtocolTest {

    // Shape produced by UiAgentMain.tree(): a Chrome window with a URL bar and a scrollable page.
    private val treeJson = """
    {"id":3,"ok":true,"display":4,"windows":[
      {"id":271,"title":"Chrome","type":1,"layer":0,"active":true,"focused":true,"pkg":"com.android.chrome",
       "root":{"ref":"271/#aa","cls":"android.widget.FrameLayout","pkg":"com.android.chrome","b":[0,0,1080,1920],"kids":[
         {"ref":"271/0#bb","cls":"android.widget.EditText","pkg":"com.android.chrome","rid":"com.android.chrome:id/url_bar",
          "text":"nanomuse.cn","hint":"Search or type URL","ed":true,"ck":true,"fc":true,"b":[160,0,784,112]},
         {"ref":"271/1#cc","cls":"android.widget.ImageButton","pkg":"com.android.chrome","desc":"Open the homepage","ck":true,"dis":true,"b":[0,0,96,112]},
         {"ref":"271/2#dd","cls":"android.view.View","pkg":"com.android.chrome","sc":true,"b":[0,114,1080,1920]}
       ]}}
    ]}
    """.trimIndent()

    @Test
    fun `agent tree parses into windows and nodes with refs and flags`() {
        val windows = AgentTreeParser.windows(JSONObject(treeJson))
        assertEquals(1, windows.size)
        val root = windows[0].root
        assertEquals("com.android.chrome", windows[0].packageName)
        val url = root.findById("url_bar")!!
        assertEquals("271/0#bb", url.ref)
        assertTrue(url.editable)
        assertEquals("Search or type URL", url.hint)
        assertEquals(160, url.bounds.left)
        assertFalse(root.findByDesc("homepage")!!.enabled)
    }

    @Test
    fun `window choice prefers the target package then the active window`() {
        val chrome = AgentTreeParser.windows(JSONObject(treeJson))[0]
        val other = chrome.copy(id = 9, packageName = "com.other", active = false)
        assertEquals(271, UiHierarchyExtractor.pickWindow(listOf(other, chrome), "com.android.chrome")!!.id)
        assertEquals(271, UiHierarchyExtractor.pickWindow(listOf(other, chrome), null)!!.id)
        assertNull(UiHierarchyExtractor.pickWindow(emptyList(), "x"))
    }

    @Test
    fun `compact snapshot carries refs, editable state, hints and scrollable containers`() {
        val root = AgentTreeParser.windows(JSONObject(treeJson))[0].root
        val snapshot = UiTreeCompressor.compress(root)

        val url = snapshot.elements.first { it.resourceId?.endsWith("url_bar") == true }
        assertTrue(url.editable)
        assertEquals("271/0#bb", url.ref)
        assertTrue(snapshot.elements.any { it.scrollable })

        val prompt = snapshot.toPromptText()
        assertTrue(prompt, prompt.contains("(EditText)"))
        assertTrue(prompt, prompt.contains("(Scrollable)"))
        assertTrue(prompt, prompt.contains("disabled"))
    }

    @Test
    fun `every action name round trips`() {
        for (action in NodeAction.values()) assertEquals(action, NodeAction.fromWire(action.wire))
        assertNull(NodeAction.fromWire("nope"))
    }

    @Test
    fun `display guard script removes exactly the given tasks and is anchored on the display id`() {
        val script = DisplayGuard.script(4, listOf(2518, 2519), "/data/local/tmp/flag")
        assertTrue(script, script.contains("am stack remove 2518; am stack remove 2519"))
        assertTrue(script, script.contains("mDisplayId=4([^0-9]|\$)"))
        assertTrue(script, script.startsWith("while [ -f /data/local/tmp/flag ]"))
        // Stops without removing anything once the flag is gone: removal is inside the
        // display-vanished branch only.
        assertEquals(1, Regex("am stack remove 2518").findAll(script).count())
    }

    @Test
    fun `display guard refuses display 0`() {
        try {
            DisplayGuard.script(0, listOf(1), "/x")
            fail("expected refusal")
        } catch (_: IllegalArgumentException) {
        }
        assertNotNull(DisplayGuard.flagPath(4))
    }
}
