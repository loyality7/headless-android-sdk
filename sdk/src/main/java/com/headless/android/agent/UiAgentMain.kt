package com.headless.android.agent

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Rect
import android.os.Bundle
import android.os.HandlerThread
import android.os.Looper
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Accessibility agent. Runs as a shell-UID `app_process` (started by the SDK through the
 * privilege backend) because only the shell identity may open a [UiAutomation] connection.
 * Talks JSON lines over stdin/stdout: one request per line, one response per line.
 *
 * Why this exists instead of `uiautomator dump`: that tool waits for the whole UI to go
 * idle and fails ("could not get idle state", exit code 0) on any screen with an ongoing
 * animation. This reads the live accessibility tree directly and performs actions on
 * nodes, so text entry and clicks need no keyboard and no pixel coordinates.
 *
 * Safety rule: every operation takes an explicit display id and REFUSES display 0 — the
 * user's physical screen. Display 0 windows are never read, enumerated, or acted on.
 *
 * Node references are `<windowId>/<childPath>#<fingerprint>`. An action re-resolves the
 * path and compares the fingerprint, so a node that moved or vanished is reported as
 * stale instead of acting on whatever now sits at that position.
 */
object UiAgentMain {

    private const val MAX_NODES = 4000
    private const val MAX_DEPTH = 60

    private lateinit var automation: UiAutomation

    private class Stale(message: String) : Exception(message)

    @SuppressLint("PrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            connect()
        } catch (e: Throwable) {
            System.err.println("AGENT_INIT_FAILED ${e.javaClass.simpleName}: ${e.message}")
            System.exit(2)
        }
        emit(JSONObject().put("ready", true).put("uid", android.os.Process.myUid()))

        val reader = BufferedReader(InputStreamReader(System.`in`))
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isBlank()) continue
            val response = handle(line)
            emit(response)
            if (response.optBoolean("quit")) break
        }
        try {
            UiAutomation::class.java.getMethod("disconnect").invoke(automation)
        } catch (_: Throwable) {}
        System.exit(0)
    }

    @SuppressLint("PrivateApi")
    private fun connect() {
        val thread = HandlerThread("HeadlessUiAgent").apply { start() }
        val conn = Class.forName("android.app.UiAutomationConnection").getDeclaredConstructor().newInstance()
        val iConn = Class.forName("android.app.IUiAutomationConnection")
        val ctor = UiAutomation::class.java.getDeclaredConstructor(Looper::class.java, iConn)
        ctor.isAccessible = true
        val auto = ctor.newInstance(thread.looper, conn) as UiAutomation
        // FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES (1): a plain connect() switches the user's
        // own accessibility services (TalkBack etc.) off while we are connected.
        try {
            UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType).invoke(auto, 1)
        } catch (_: NoSuchMethodException) {
            UiAutomation::class.java.getMethod("connect").invoke(auto)
        }
        val info = auto.serviceInfo ?: AccessibilityServiceInfo()
        info.flags = info.flags or
            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
            AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
            AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
        auto.serviceInfo = info
        automation = auto
        connectedAt = System.currentTimeMillis()
    }

    private fun emit(json: JSONObject) {
        System.out.println(json.toString())
        System.out.flush()
    }

    private fun handle(line: String): JSONObject {
        var id = -1
        return try {
            val req = JSONObject(line)
            id = req.optInt("id", -1)
            val body = when (val op = req.getString("op")) {
                "ping" -> JSONObject().put("pong", true)
                "quit" -> JSONObject().put("quit", true)
                "displays" -> displays()
                "tree" -> tree(displayOf(req))
                "focused" -> focused(displayOf(req))
                "act" -> act(req)
                else -> throw IllegalArgumentException("unknown op '$op'")
            }
            body.put("id", id).put("ok", true)
        } catch (e: Stale) {
            JSONObject().put("id", id).put("ok", false).put("stale", true).put("error", e.message)
        } catch (e: Throwable) {
            JSONObject().put("id", id).put("ok", false).put("error", "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun displayOf(req: JSONObject): Int {
        val display = req.optInt("display", -1)
        require(display > 0) { "refusing display $display: display 0 is the user's physical screen" }
        return display
    }

    @Volatile
    private var connectedAt = 0L

    private fun windows(display: Int): List<AccessibilityWindowInfo> {
        // Window info arrives asynchronously after connect(); an empty answer in the first
        // seconds means "not received yet", not "nothing there".
        var attempts = 0
        while (true) {
            val all: SparseArray<List<AccessibilityWindowInfo>> = automation.windowsOnAllDisplays
            val found = all.get(display)
            if (!found.isNullOrEmpty() || attempts >= 15 || System.currentTimeMillis() - connectedAt > 6000) {
                return found ?: emptyList()
            }
            attempts++
            Thread.sleep(200)
        }
    }

    /** First visible node matching every field present in [sel] (rid, text, desc, cls, exact). */
    private fun findBySelector(display: Int, sel: JSONObject): AccessibilityNodeInfo {
        val rid = sel.optString("rid")
        val text = sel.optString("text")
        val desc = sel.optString("desc")
        val cls = sel.optString("cls")
        val exact = sel.optBoolean("exact")
        require(rid.isNotEmpty() || text.isNotEmpty() || desc.isNotEmpty() || cls.isNotEmpty()) { "empty selector" }

        fun eq(have: CharSequence?, want: String): Boolean {
            val h = have?.toString() ?: return false
            return if (exact) h.equals(want, true) else h.contains(want, true)
        }
        fun matches(n: AccessibilityNodeInfo): Boolean =
            n.isVisibleToUser &&
                (rid.isEmpty() || (n.viewIdResourceName?.endsWith(rid) == true)) &&
                (text.isEmpty() || eq(n.text, text)) &&
                (desc.isEmpty() || eq(n.contentDescription, desc)) &&
                (cls.isEmpty() || (n.className?.toString()?.endsWith(cls) == true))

        fun search(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
            if (matches(n)) return n
            for (i in 0 until n.childCount) {
                val child = n.getChild(i) ?: continue
                search(child)?.let { return it }
            }
            return null
        }
        for (w in windows(display)) {
            val root = w.root ?: continue
            search(root)?.let { return it }
        }
        throw IllegalStateException("no visible node on display $display matches $sel")
    }

    /** Ids of non-default displays that have windows. Display 0 is never reported. */
    private fun displays(): JSONObject {
        val all: SparseArray<List<AccessibilityWindowInfo>> = automation.windowsOnAllDisplays
        val out = JSONArray()
        for (i in 0 until all.size()) {
            val id = all.keyAt(i)
            if (id > 0) out.put(JSONObject().put("display", id).put("windows", all.valueAt(i)?.size ?: 0))
        }
        return JSONObject().put("displays", out)
    }

    private fun tree(display: Int): JSONObject {
        val wins = JSONArray()
        for (w in windows(display)) {
            val root = w.root ?: continue
            val budget = intArrayOf(MAX_NODES)
            val rootJson = nodeJson(root, w.id, "", 0, budget) ?: continue
            wins.put(
                JSONObject()
                    .put("id", w.id)
                    .put("title", w.title?.toString() ?: "")
                    .put("type", w.type)
                    .put("layer", w.layer)
                    .put("active", w.isActive)
                    .put("focused", w.isFocused)
                    .put("pkg", root.packageName?.toString() ?: "")
                    .put("root", rootJson)
            )
        }
        return JSONObject().put("display", display).put("windows", wins)
    }

    private fun focused(display: Int): JSONObject {
        for (w in windows(display)) {
            val root = w.root ?: continue
            val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: continue
            val path = pathOf(root, node)
            val json = nodeJson(node, w.id, path, 0, intArrayOf(1), withChildren = false)
            return JSONObject().put("node", json)
        }
        return JSONObject().put("node", JSONObject.NULL)
    }

    private fun pathOf(root: AccessibilityNodeInfo, target: AccessibilityNodeInfo): String {
        fun search(node: AccessibilityNodeInfo, path: String): String? {
            if (node == target) return path
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val p = search(child, if (path.isEmpty()) "$i" else "$path.$i")
                if (p != null) return p
            }
            return null
        }
        return search(root, "") ?: ""
    }

    private fun fingerprint(node: AccessibilityNodeInfo): String {
        val b = Rect().also { node.getBoundsInScreen(it) }
        val raw = "${node.className}|${node.viewIdResourceName}|${node.packageName}|${b.left},${b.top},${b.right},${b.bottom}"
        return Integer.toHexString(raw.hashCode())
    }

    private fun nodeJson(
        node: AccessibilityNodeInfo,
        windowId: Int,
        path: String,
        depth: Int,
        budget: IntArray,
        withChildren: Boolean = true
    ): JSONObject? {
        if (!node.isVisibleToUser || budget[0] <= 0) return null
        budget[0]--
        val b = Rect().also { node.getBoundsInScreen(it) }
        val json = JSONObject()
            .put("ref", "$windowId/$path#${fingerprint(node)}")
            .put("cls", node.className?.toString() ?: "")
            .put("pkg", node.packageName?.toString() ?: "")
            .put("b", JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom))
        node.text?.let { json.put("text", it.toString()) }
        node.contentDescription?.let { json.put("desc", it.toString()) }
        node.viewIdResourceName?.let { json.put("rid", it) }
        node.hintText?.let { json.put("hint", it.toString()) }
        if (node.isClickable) json.put("ck", true)
        if (node.isLongClickable) json.put("lc", true)
        if (node.isFocusable) json.put("fc", true)
        if (node.isFocused) json.put("fd", true)
        if (node.isEditable) json.put("ed", true)
        if (node.isScrollable) json.put("sc", true)
        if (node.isSelected) json.put("sl", true)
        if (node.isCheckable) json.put("cb", true)
        if (node.isChecked) json.put("ch", true)
        if (node.isPassword) json.put("pw", true)
        if (!node.isEnabled) json.put("dis", true)
        if (withChildren && depth < MAX_DEPTH) {
            val kids = JSONArray()
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                val childPath = if (path.isEmpty()) "$i" else "$path.$i"
                nodeJson(child, windowId, childPath, depth + 1, budget)?.let { kids.put(it) }
            }
            if (kids.length() > 0) json.put("kids", kids)
        }
        return json
    }

    private fun resolve(display: Int, ref: String): AccessibilityNodeInfo {
        val fp = ref.substringAfter('#', "")
        val loc = ref.substringBefore('#')
        val windowId = loc.substringBefore('/').toIntOrNull() ?: throw IllegalArgumentException("bad ref '$ref'")
        val path = loc.substringAfter('/', "")
        val window = windows(display).firstOrNull { it.id == windowId }
            ?: throw Stale("window $windowId is no longer on display $display")
        var node = window.root ?: throw Stale("window $windowId has no root")
        if (path.isNotEmpty()) {
            for (part in path.split('.')) {
                val index = part.toIntOrNull() ?: throw IllegalArgumentException("bad ref '$ref'")
                node = node.getChild(index) ?: throw Stale("no child $index along $path")
            }
        }
        if (fp.isNotEmpty() && fingerprint(node) != fp) {
            throw Stale("node at $loc changed (fingerprint ${fingerprint(node)} != $fp)")
        }
        return node
    }

    private fun actionId(name: String): Int = when (name) {
        "click" -> AccessibilityNodeInfo.ACTION_CLICK
        "long_click" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
        "focus" -> AccessibilityNodeInfo.ACTION_FOCUS
        "clear_focus" -> AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
        "select" -> AccessibilityNodeInfo.ACTION_SELECT
        "clear_selection" -> AccessibilityNodeInfo.ACTION_CLEAR_SELECTION
        "a11y_focus" -> AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
        "clear_a11y_focus" -> AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
        "scroll_forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        "scroll_backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        "copy" -> AccessibilityNodeInfo.ACTION_COPY
        "paste" -> AccessibilityNodeInfo.ACTION_PASTE
        "cut" -> AccessibilityNodeInfo.ACTION_CUT
        "set_selection" -> AccessibilityNodeInfo.ACTION_SET_SELECTION
        "set_text" -> AccessibilityNodeInfo.ACTION_SET_TEXT
        "expand" -> AccessibilityNodeInfo.ACTION_EXPAND
        "collapse" -> AccessibilityNodeInfo.ACTION_COLLAPSE
        "dismiss" -> AccessibilityNodeInfo.ACTION_DISMISS
        "show_on_screen" -> AccessibilityAction.ACTION_SHOW_ON_SCREEN.id
        "scroll_up" -> AccessibilityAction.ACTION_SCROLL_UP.id
        "scroll_down" -> AccessibilityAction.ACTION_SCROLL_DOWN.id
        "scroll_left" -> AccessibilityAction.ACTION_SCROLL_LEFT.id
        "scroll_right" -> AccessibilityAction.ACTION_SCROLL_RIGHT.id
        "context_click" -> AccessibilityAction.ACTION_CONTEXT_CLICK.id
        "set_progress" -> AccessibilityAction.ACTION_SET_PROGRESS.id
        else -> throw IllegalArgumentException("unknown action '$name'")
    }

    private fun act(req: JSONObject): JSONObject {
        val display = displayOf(req)
        val name = req.getString("action")
        val node = if (req.has("ref")) resolve(display, req.getString("ref"))
        else findBySelector(display, req.getJSONObject("sel"))
        val args = Bundle()
        val a = req.optJSONObject("args")
        if (a != null) {
            a.optString("text", null)?.let {
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, it)
            }
            if (a.has("start")) args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, a.getInt("start"))
            if (a.has("end")) args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, a.getInt("end"))
            if (a.has("progress")) {
                args.putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, a.getDouble("progress").toFloat())
            }
        }
        val performed = node.performAction(actionId(name), args)
        // Report what the node holds afterwards so callers can verify the effect instead of
        // trusting "performed": the app handles the action asynchronously, so give it a
        // moment and re-read the node.
        Thread.sleep(150)
        node.refresh()
        val after = JSONObject()
            .put("text", node.text?.toString() ?: "")
            .put("focused", node.isFocused)
            .put("checked", node.isChecked)
            .put("selected", node.isSelected)
        return JSONObject().put("performed", performed).put("action", name).put("after", after)
    }
}
