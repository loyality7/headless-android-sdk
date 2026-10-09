package com.headless.android.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiTreeCompressorTest {

    private val sampleXml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="com.android.chrome:id/main" class="android.widget.FrameLayout" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,1920]">
            <node index="0" text="" resource-id="com.android.chrome:id/toolbar" class="android.view.ViewGroup" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,120]">
              <node index="0" text="google.com" resource-id="com.android.chrome:id/url_bar" class="android.widget.EditText" package="com.android.chrome" content-desc="Search URL" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="true" password="false" selected="true" bounds="[160,20][800,100]" />
              <node index="1" text="" resource-id="com.android.chrome:id/tab_button" class="android.widget.Button" package="com.android.chrome" content-desc="Open tabs" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[820,20][920,100]" />
            </node>
            <node index="1" text="" resource-id="com.android.chrome:id/content_view" class="android.webkit.WebView" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="true" scrollable="true" long-clickable="false" password="false" selected="false" bounds="[0,120][1080,1920]">
              <node index="0" text="Search with Google" resource-id="tsf" class="android.widget.Button" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[200,500][600,600]" />
              <node index="1" text="Trending topics" resource-id="" class="android.widget.TextView" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[40,650][400,700]" />
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun `compress removes layout clutter and creates indexed prompt`() {
        val root = XmlUiHierarchyParser.parse(sampleXml).first()
        val snapshot = UiTreeCompressor.compress(root)

        assertEquals("com.android.chrome", snapshot.packageName)
        // 5: the scrollable WebView container is listed so an agent can scroll it.
        assertEquals(5, snapshot.elements.size)

        // Element 1: URL bar
        val el1 = snapshot.findByIndex(1)
        assertNotNull(el1)
        assertEquals("google.com", el1!!.text)
        assertEquals(480f, el1.centerX, 0.001f)
        assertEquals(60f, el1.centerY, 0.001f)
        assertTrue(el1.editable)

        // Check LLM prompt formatting
        val prompt = snapshot.toPromptText()
        assertTrue(prompt.contains("[1] url_bar (EditText) \"google.com\" [x=480, y=60]"))
        assertTrue(prompt.contains("[2] tab_button (Clickable) \"Open tabs\" [x=870, y=60]"))
        assertTrue(prompt.contains("[3] content_view (Scrollable) focused [x=540, y=1020]"))
        assertTrue(prompt.contains("[4] tsf (Clickable) \"Search with Google\" [x=400, y=550]"))
        assertTrue(prompt.contains("[5] (TextView) \"Trending topics\" [x=220, y=675]"))

        // Check JSON output
        val json = snapshot.toJson()
        assertTrue(json.contains("\"package\":\"com.android.chrome\""))
        assertTrue(json.contains("\"idx\":1"))
        assertTrue(json.contains("\"text\":\"google.com\""))
    }
}
