package com.headless.android.perception

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XmlUiHierarchyParserTest {

    private val sampleXml = """
        <?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
        <hierarchy rotation="0">
          <node index="0" text="" resource-id="com.android.chrome:id/main_layout" class="android.widget.FrameLayout" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,1920]">
            <node index="0" text="" resource-id="com.android.chrome:id/toolbar" class="android.view.ViewGroup" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="false" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[0,0][1080,120]">
              <node index="0" text="google.com" resource-id="com.android.chrome:id/url_bar" class="android.widget.EditText" package="com.android.chrome" content-desc="Search or type URL" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="true" password="false" selected="true" bounds="[160,20][800,100]" />
              <node index="1" text="" resource-id="com.android.chrome:id/tab_button" class="android.widget.Button" package="com.android.chrome" content-desc="Open tabs" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[820,20][920,100]" />
            </node>
            <node index="1" text="" resource-id="com.android.chrome:id/content_view" class="android.webkit.WebView" package="com.android.chrome" content-desc="" checkable="false" checked="false" clickable="false" enabled="true" focusable="true" focused="true" scrollable="true" long-clickable="false" password="false" selected="false" bounds="[0,120][1080,1920]">
              <node index="0" text="Search with Google" resource-id="tsf" class="android.widget.Button" package="com.android.chrome" content-desc="Submit Query" checkable="false" checked="false" clickable="true" enabled="true" focusable="true" focused="false" scrollable="false" long-clickable="false" password="false" selected="false" bounds="[200,500][600,600]" />
            </node>
          </node>
        </hierarchy>
    """.trimIndent()

    @Test
    fun `parse bounds correctly without regex overhead`() {
        val bounds = XmlUiHierarchyParser.parseBounds("[160,20][800,100]")
        assertEquals(160, bounds.left)
        assertEquals(20, bounds.top)
        assertEquals(800, bounds.right)
        assertEquals(100, bounds.bottom)
        assertEquals(480f, bounds.centerX, 0.001f)
        assertEquals(60f, bounds.centerY, 0.001f)
        assertEquals(640, bounds.width)
        assertEquals(80, bounds.height)
    }

    @Test
    fun `parse bounds handles malformed gracefully`() {
        assertEquals(ElementBounds(0, 0, 0, 0), XmlUiHierarchyParser.parseBounds(null))
        assertEquals(ElementBounds(0, 0, 0, 0), XmlUiHierarchyParser.parseBounds(""))
        assertEquals(ElementBounds(0, 0, 0, 0), XmlUiHierarchyParser.parseBounds("malformed"))
    }

    @Test
    fun `parse hierarchy xml into structured UiNode tree`() {
        val roots = XmlUiHierarchyParser.parse(sampleXml)
        assertEquals(1, roots.size)

        val root = roots[0]
        assertEquals("com.android.chrome", root.packageName)
        assertEquals(ElementBounds(0, 0, 1080, 1920), root.bounds)
        assertEquals(2, root.children.size)

        // Find URL bar by resource-id
        val urlBar = root.findById("url_bar")
        assertNotNull(urlBar)
        assertEquals("google.com", urlBar!!.text)
        assertEquals("com.android.chrome:id/url_bar", urlBar.resourceId)
        assertEquals(480f, urlBar.centerX, 0.001f)
        assertEquals(60f, urlBar.centerY, 0.001f)
        assertTrue(urlBar.clickable)

        // Find by text
        val searchBtn = root.findByText("Search with Google")
        assertNotNull(searchBtn)
        assertEquals("tsf", searchBtn!!.resourceId)
        assertEquals(400f, searchBtn.centerX, 0.001f)
        assertEquals(550f, searchBtn.centerY, 0.001f)

        // Find by content description
        val tabBtn = root.findByDesc("Open tabs")
        assertNotNull(tabBtn)
        assertEquals("com.android.chrome:id/tab_button", tabBtn!!.resourceId)
    }

    @Test
    fun `toScreenElements flattens semantically meaningful nodes`() {
        val roots = XmlUiHierarchyParser.parse(sampleXml)
        val elements = roots[0].toScreenElements()

        // Should include url_bar, tab_button, search button
        assertTrue(elements.any { it.id == "com.android.chrome:id/url_bar" && it.text == "google.com" && it.editable })
        assertTrue(elements.any { it.id == "com.android.chrome:id/tab_button" && it.clickable })
        assertTrue(elements.any { it.id == "tsf" && it.text == "Search with Google" })
    }

    @Test
    fun `empty or blank xml returns empty list`() {
        assertTrue(XmlUiHierarchyParser.parse("").isEmpty())
        assertTrue(XmlUiHierarchyParser.parse("   ").isEmpty())
    }
}
