package com.headless.android.apps

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import com.headless.android.AppLaunchException
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Launches an Android application's default activity onto a specific (virtual) display,
 * via a direct `IActivityTaskManager.startActivity` Binder transaction with
 * `ActivityOptions.launchDisplayId` set — the exact technique proven working on-device
 * by the POC (`am start --display` was tried as a "simpler" alternative first; it failed
 * because `--activity-new-task` isn't a valid flag on this API level, confirming that
 * shell path was never actually the one exercised by the POC — only the raw transact was).
 */
class AppLauncher(private val privilegeBackend: PrivilegeBackend) {

    companion object {
        private const val OP = "AppLauncher"
        private const val VERIFY_ATTEMPTS = 6
        private const val VERIFY_DELAY_MS = 500L
        private const val ATM_INTERFACE_TOKEN = "android.app.IActivityTaskManager"
        private const val CALLING_PACKAGE = "com.android.shell"

        // Ground-truth verified on-device (Xiaomi 22041219PI, Android 14 / API 34): reflection
        // lookup of TRANSACTION_startActivity is unreliable — it can silently resolve to the
        // WRONG field (observed: 50, which moves the CALLER's own task instead of launching the
        // target) depending on calling process/classloader. The actually-correct transaction
        // code for IActivityTaskManager.startActivity on this AIDL build is 1, confirmed by the
        // POC's own logged DIRECT_ATM_TRANSACTION_CODE=1 / DIRECT_ATM_RESULT=0 / MATCH=true run.
        private const val TRANSACTION_START_ACTIVITY = 1
    }

    /**
     * Launches [packageName]'s default/main activity onto [displayId]. Throws
     * [AppLaunchException] if the package can't be resolved or the platform refuses to
     * place it on that display.
     */
    fun launch(packageName: String, displayId: Int) {
        val component = resolveMainComponent(packageName)
            ?: throw AppLaunchException(packageName, "Could not resolve a launchable activity")

        val intent = Intent(Intent.ACTION_MAIN).apply {
            setClassName(component.substringBefore("/"), resolveClassName(component))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        try {
            transactStartActivity(intent, displayId)
        } catch (e: Throwable) {
            HeadlessLog.event(displayId = displayId, packageName = packageName, op = OP, success = false)
            throw AppLaunchException(packageName, "startActivity transaction failed: ${e.message}", e)
        }

        var lastSeenOn: Set<Int> = emptySet()
        repeat(VERIFY_ATTEMPTS) {
            lastSeenOn = displayIdsHosting(packageName)
            if (lastSeenOn.contains(displayId)) {
                HeadlessLog.event(displayId = displayId, packageName = packageName, op = OP, success = true)
                return
            }
            Thread.sleep(VERIFY_DELAY_MS)
        }

        HeadlessLog.event(displayId = displayId, packageName = packageName, op = OP, success = false)

        // Distinguish "didn't start" from "started on the WRONG display". The latter is a
        // safety-critical case: the app may be on the user's physical display, where any
        // subsequent input injection would hit the real screen.
        val elsewhere = lastSeenOn - displayId
        val detail = when {
            elsewhere.isEmpty() ->
                "not observed on any display within ${VERIFY_ATTEMPTS * VERIFY_DELAY_MS}ms"
            else ->
                "PLACED ON THE WRONG DISPLAY(S) ${elsewhere.sorted()} instead of $displayId — " +
                    "the platform redirected this app (display 0 = the user's physical screen). " +
                    "Refusing to report success; do not inject input for this session."
        }
        throw AppLaunchException(packageName, detail)
    }

    private fun resolveClassName(component: String): String {
        val className = component.substringAfter("/")
        // `cmd package resolve-activity` can print a shorthand ".ClassName"; expand it.
        return if (className.startsWith(".")) component.substringBefore("/") + className else className
    }

    private fun resolveMainComponent(packageName: String): String? {
        val output = privilegeBackend.shell(
            arrayOf("cmd", "package", "resolve-activity", "--brief", packageName)
        ).stdout
        val lastLine = output.lineSequence().map { it.trim() }.lastOrNull { it.isNotEmpty() }
            ?: return null
        return if (lastLine.contains("/")) lastLine else null
    }

    private fun transactStartActivity(intent: Intent, displayId: Int) {
        val atmBinder = privilegeBackend.getSystemServiceBinder("activity_task")

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(ATM_INTERFACE_TOKEN)
            data.writeStrongBinder(null)               // caller: IApplicationThread
            data.writeString(CALLING_PACKAGE)           // callingPackage
            data.writeString(null)                      // callingFeatureId
            data.writeTypedObject(intent, 0)             // intent
            data.writeString(null)                       // resolvedType
            data.writeStrongBinder(null)                 // resultTo
            data.writeString(null)                        // resultWho
            data.writeInt(0)                               // requestCode
            data.writeInt(0)                               // flags
            data.writeTypedObject(null as android.os.Parcelable?, 0) // profilerInfo
            val options = ActivityOptions.makeBasic().apply { launchDisplayId = displayId }
            data.writeTypedObject(options.toBundle(), 0)

            val transacted = atmBinder.transact(TRANSACTION_START_ACTIVITY, data, reply, 0)
            if (!transacted) throw AppLaunchException("", "Binder transact returned false")
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** Force-stops [packageName]. Returns true if the stop command completed cleanly. */
    fun stop(packageName: String): Boolean {
        val result = privilegeBackend.shell(arrayOf("am", "force-stop", packageName))
        HeadlessLog.event(packageName = packageName, op = "$OP.stop", success = result.isSuccess)
        return result.isSuccess
    }

    /**
     * Returns the package name of the top resumed activity on [displayId], or null if that
     * display has no resumed activity. Used for state-level verification — "which app is
     * actually in front on our display right now".
     */
    fun currentPackageOnDisplay(displayId: Int): String? {
        val output = privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        var currentDisplayId = -1
        for (rawLine in output.lineSequence()) {
            val line = rawLine.trim()
            if (line.contains("Display #")) {
                currentDisplayId = line.substringAfter("Display #")
                    .substringBefore(" ").substringBefore("(").trim().toIntOrNull() ?: currentDisplayId
            }
            if (currentDisplayId == displayId && line.startsWith("ResumedActivity:")) {
                // e.g. "ResumedActivity: ActivityRecord{hash u0 com.pkg/.Activity t123}"
                val record = line.substringAfter("ActivityRecord{", "").trim()
                if (record.isEmpty()) continue
                val componentToken = record.split(" ").firstOrNull { it.contains("/") } ?: continue
                return componentToken.substringBefore("/")
            }
        }
        return null
    }

    /** True if [packageName] has a task on [displayId]. */
    fun isOnDisplay(packageName: String, displayId: Int): Boolean =
        displayIdsHosting(packageName).contains(displayId)

    /**
     * Every display ID that currently hosts a task for [packageName].
     *
     * Returning the full set rather than a boolean is deliberate: knowing the app is on
     * *some other* display is the difference between "launch pending" and "the platform
     * put this app on the user's physical screen", and callers must be able to tell those
     * apart. A boolean check hid exactly that case and caused input to be injected while
     * the target app was actually on display 0.
     */
    fun displayIdsHosting(packageName: String): Set<Int> {
        val output = privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        val hosting = mutableSetOf<Int>()
        var section = -1

        for (rawLine in output.lineSequence()) {
            val header = parseDisplaySectionHeader(rawLine)
            if (header != null) {
                section = header
                continue
            }
            if (section >= 0 && rawLine.contains(packageName) && rawLine.contains("Task{")) {
                hosting.add(section)
            }
        }
        return hosting
    }

    /**
     * Parses a `dumpsys activity activities` display *section header*, e.g.
     * `Display #159 (activities from top to bottom):`
     *
     * Must match only true section headers. The previous implementation used
     * `line.contains("Display #")`, which also matched incidental references to
     * "Display #0" inside task/activity detail lines; that silently reassigned the
     * current-section id mid-section, so a display-0 task could be attributed to the
     * target display and a launch verified as successful when the app was never there.
     */
    private fun parseDisplaySectionHeader(rawLine: String): Int? {
        val line = rawLine.trimEnd()
        // Section headers start at column 0 (no leading whitespace) in AMS output.
        if (line != rawLine.trimStart()) return null
        val match = Regex("""^Display #(\d+)\b""").find(line) ?: return null
        return match.groupValues[1].toIntOrNull()
    }
}
