package com.headless.android.perception

import android.accessibilityservice.AccessibilityServiceInfo
import android.annotation.SuppressLint
import android.app.UiAutomation
import android.os.HandlerThread
import android.util.SparseArray
import android.view.accessibility.AccessibilityWindowInfo

object UiDumper {

    @SuppressLint("PrivateApi")
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val thread = HandlerThread("HeadlessUiAutomation")
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

            println("CONNECTED")
            // Allow accessibility service connection to stabilize and receive window state
            Thread.sleep(800)

            val targetDisplayId = args.firstOrNull()?.toIntOrNull() ?: -1

            val displays: SparseArray<List<AccessibilityWindowInfo>> = auto.windowsOnAllDisplays
            println("DISPLAY_COUNT=${displays.size()}")
            for (i in 0 until displays.size()) {
                val dId = displays.keyAt(i)
                val windowList = displays.valueAt(i) ?: emptyList()
                println("DISPLAY=$dId WINDOWS=${windowList.size}")
                if (targetDisplayId == -1 || targetDisplayId == dId) {
                    for ((wIdx, win) in windowList.withIndex()) {
                        val root = win.root
                        println("  WINDOW[$wIdx]: id=${win.id} title=${win.title} type=${win.type} hasRoot=${root != null}")
                        if (root != null) {
                            dumpNode(root, depth = 2)
                        }
                    }
                }
            }

            try {
                UiAutomation::class.java.getMethod("disconnect").invoke(auto)
            } catch (_: Throwable) {}
            thread.quitSafely()
        } catch (e: Throwable) {
            println("ERROR: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun dumpNode(node: android.view.accessibility.AccessibilityNodeInfo, depth: Int) {
        val indent = "  ".repeat(depth)
        val rect = android.graphics.Rect()
        node.getBoundsInScreen(rect)
        val text = node.text?.toString()?.replace("\n", " ") ?: ""
        val desc = node.contentDescription?.toString()?.replace("\n", " ") ?: ""
        val id = node.viewIdResourceName ?: ""
        val cls = node.className?.toString()?.substringAfterLast('.') ?: ""
        val clickable = node.isClickable

        val parts = mutableListOf<String>()
        parts.add("[$cls]")
        if (text.isNotEmpty()) parts.add("text=\"$text\"")
        if (desc.isNotEmpty()) parts.add("desc=\"$desc\"")
        if (id.isNotEmpty()) parts.add("id=\"$id\"")
        parts.add("bounds=(${rect.left},${rect.top},${rect.right},${rect.bottom})")
        if (clickable) parts.add("clickable")

        println("$indent${parts.joinToString(" ")}")

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            dumpNode(child, depth + 1)
        }
    }
}
