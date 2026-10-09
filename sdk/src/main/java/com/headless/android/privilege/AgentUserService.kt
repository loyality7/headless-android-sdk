package com.headless.android.privilege

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.graphics.Rect
import android.os.HandlerThread
import android.util.Log
import android.util.SparseArray
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.json.JSONArray
import org.json.JSONObject

@SuppressLint("PrivateApi")
class AgentUserService : IAgentUserService.Stub() {

    private companion object {
        private const val TAG = "AgentUserService"
    }

    private val thread = HandlerThread("HeadlessUiAutomation")
    private var automation: UiAutomation? = null

    init {
        try {
            thread.start()
            val connClass = Class.forName("android.app.UiAutomationConnection")
            val conn = connClass.getDeclaredConstructor().newInstance()
            val iConnClass = Class.forName("android.app.IUiAutomationConnection")
            val constructor = UiAutomation::class.java.getDeclaredConstructor(
                android.os.Looper::class.java,
                iConnClass
            )
            constructor.isAccessible = true
            val auto = constructor.newInstance(thread.looper, conn) as UiAutomation
            try {
                UiAutomation::class.java.getMethod("connect").invoke(auto)
            } catch (_: NoSuchMethodException) {
                UiAutomation::class.java.getMethod("connect", Int::class.javaPrimitiveType).invoke(auto, 0)
            }

            val info = auto.serviceInfo ?: AccessibilityServiceInfo()
            info.flags = info.flags or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            auto.serviceInfo = info

            automation = auto
            Log.i(TAG, "UiAutomation connected successfully in UserService")
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to initialize UiAutomation", e)
        }
    }

    override fun getDisplayReport(): String {
        val auto = automation ?: return """{"status":"error","reason":"UiAutomation not initialized"}"""
        return try {
            val allDisplays: SparseArray<List<AccessibilityWindowInfo>> = auto.windowsOnAllDisplays
            val displayIds = mutableListOf<Int>()
            val counts = JSONObject()
            for (i in 0 until allDisplays.size()) {
                val dId = allDisplays.keyAt(i)
                displayIds.add(dId)
                val winList = allDisplays.valueAt(i)
                counts.put(dId.toString(), winList?.size ?: 0)
            }
            JSONObject().apply {
                put("status", "ok")
                put("connected", true)
                put("displayIds", JSONArray(displayIds))
                put("windowCounts", counts)
            }.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "getDisplayReport error", e)
            """{"status":"error","reason":"${e.javaClass.simpleName}: ${e.message}"}"""
        }
    }

    override fun getUiTreeJson(displayId: Int): String {
        val auto = automation ?: return JSONObject().put("error", "UiAutomation not initialized").toString()
        return try {
            val allWindows: SparseArray<List<AccessibilityWindowInfo>> = auto.windowsOnAllDisplays
            val windows = allWindows.get(displayId) ?: emptyList()
            val result = JSONObject().put("displayId", displayId)
            val windowArray = JSONArray()

            for (window in windows) {
                val root = window.root ?: continue
                try {
                    val winJson = JSONObject().apply {
                        put("windowId", window.id)
                        put("type", window.type)
                        put("layer", window.layer)
                        put("focused", window.isFocused)
                        put("active", window.isActive)
                        put("root", nodeToJson(root))
                    }
                    windowArray.put(winJson)
                } finally {
                    root.recycle()
                }
            }
            result.put("windows", windowArray)
            result.toString()
        } catch (e: Throwable) {
            Log.e(TAG, "getUiTreeJson error for display $displayId", e)
            JSONObject().put("displayId", displayId).put("error", "${e.javaClass.simpleName}: ${e.message}").toString()
        }
    }

    private fun nodeToJson(node: AccessibilityNodeInfo): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                children.put(nodeToJson(child))
            } finally {
                child.recycle()
            }
        }
        return JSONObject().apply {
            put("class", node.className?.toString() ?: "")
            put("package", node.packageName?.toString() ?: "")
            put("text", node.text?.toString() ?: "")
            put("contentDescription", node.contentDescription?.toString() ?: "")
            put("resourceId", node.viewIdResourceName ?: "")
            put("clickable", node.isClickable)
            put("longClickable", node.isLongClickable)
            put("focusable", node.isFocusable)
            put("focused", node.isFocused)
            put("enabled", node.isEnabled)
            put("editable", node.isEditable)
            put("scrollable", node.isScrollable)
            put("selected", node.isSelected)
            put("bounds", JSONArray().apply {
                put(bounds.left)
                put(bounds.top)
                put(bounds.right)
                put(bounds.bottom)
            })
            put("children", children)
        }
    }

    override fun destroy() {
        try {
            automation?.let { UiAutomation::class.java.getMethod("disconnect").invoke(it) }
        } catch (_: Throwable) {}
        thread.quitSafely()
        System.exit(0)
    }
}
