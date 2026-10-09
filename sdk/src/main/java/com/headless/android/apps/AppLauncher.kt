package com.headless.android.apps

import android.app.ActivityOptions
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import com.headless.android.AppLaunchException
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.state.ActivityDumpParser

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

        // [com.headless.android.BinderCodes] resolves the startActivity code per device via
        // exact-name Stub lookup only (no fuzzy search — fuzzy once resolved the WRONG field
        // 50, moving the caller's own task). Fallback 1 = POC-verified value on this build,
        // and launch placement is verified on-display below.
    }

    /**
     * Launches [packageName]'s default/main activity onto [displayId]. Throws
     * [AppLaunchException] if the package can't be resolved or the platform refuses to
     * place it on that display.
     */
    fun launch(packageName: String, displayId: Int, uri: Uri? = null) {
        // Fail closed BEFORE any binder call: if the target display is display 0 or is not
        // alive, the platform would fall back to display 0 and the app would open on the
        // user's physical screen.
        if (displayId <= 0) {
            throw AppLaunchException(packageName, "Refusing to launch on display $displayId: display 0 is the user's physical screen")
        }
        if (!isDisplayAlive(displayId)) {
            throw AppLaunchException(packageName, "Refusing to launch: display $displayId does not exist (launching would fall back to display 0)")
        }
        val wasOnDisplayZero = displayIdsHosting(packageName).contains(0)

        val component = resolveMainComponent(packageName)
            ?: throw AppLaunchException(packageName, "Could not resolve a launchable activity")

        val action = if (uri != null) Intent.ACTION_VIEW else Intent.ACTION_MAIN
        val intent = Intent(action).apply {
            if (uri != null) data = uri
            setClassName(component.substringBefore("/"), resolveClassName(component))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
        }

        try {
            transactStartActivity(packageName, intent, displayId)
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
        // The app landed on the user's screen because of this launch: take it off again
        // rather than leaving it there. Skipped when the user already had it open on
        // display 0 — then we cannot tell which task is ours and must not kill theirs.
        if (0 in elsewhere && !wasOnDisplayZero) {
            removeFromDisplay(packageName, 0)
        }
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

    private fun transactStartActivity(packageName: String, intent: Intent, displayId: Int) {
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

            val transacted = atmBinder.transact(
                com.headless.android.BinderCodes.atmStartActivity(), data, reply, 0
            )
            if (!transacted) throw AppLaunchException(packageName, "Binder transact returned false")
            reply.readException()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Closes [packageName]'s tasks on [displayId] only, leaving the same app on any other
     * display (the user's own copy on display 0) untouched. A global `am force-stop` here
     * killed the user's real app.
     *
     * Falls back to force-stop only when the app has no presence on display 0, so nothing
     * of the user's can be lost. Returns true once the app is gone from [displayId].
     */
    fun closeOnDisplay(packageName: String, displayId: Int): Boolean {
        removeFromDisplay(packageName, displayId)
        repeat(15) {
            if (!displayIdsHosting(packageName).contains(displayId)) {
                HeadlessLog.event(displayId = displayId, packageName = packageName, op = "$OP.close", success = true)
                return true
            }
            Thread.sleep(100)
        }
        if (!displayIdsHosting(packageName).contains(0)) {
            privilegeBackend.shell(arrayOf("am", "force-stop", packageName))
            Thread.sleep(300)
        }
        val gone = !displayIdsHosting(packageName).contains(displayId)
        HeadlessLog.event(displayId = displayId, packageName = packageName, op = "$OP.close", success = gone)
        return gone
    }

    /**
     * Cleanup for an orphaned session app whose display is gone. Force-stops it only if it
     * is NOT on display 0 — if it is there we cannot prove it is ours rather than the
     * user's own instance, so it is left alone.
     */
    fun reapOrphan(packageName: String): Boolean {
        if (displayIdsHosting(packageName).contains(0)) {
            HeadlessLog.w(OP, "orphan $packageName is on display 0; leaving it (may be the user's own)")
            return false
        }
        privilegeBackend.shell(arrayOf("am", "force-stop", packageName))
        return true
    }

    /** Root task ids [packageName] currently has on [displayId] (what the display guard removes). */
    fun taskIdsOnDisplay(packageName: String, displayId: Int): List<Int> {
        val dump = privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        return ActivityDumpParser.rootTaskIdsOnDisplay(dump, displayId, packageName)
    }

    private fun removeFromDisplay(packageName: String, displayId: Int) {
        val dump = privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        for (taskId in ActivityDumpParser.rootTaskIdsOnDisplay(dump, displayId, packageName)) {
            privilegeBackend.shell(arrayOf("am", "stack", "remove", taskId.toString()))
        }
    }

    private fun isDisplayAlive(displayId: Int): Boolean {
        val out = privilegeBackend.shell(arrayOf("dumpsys", "display")).stdout
        return Regex("""mDisplayId=$displayId\b""").containsMatchIn(out)
    }

    /**
     * Package name of the top resumed activity on [displayId], or null.
     *
     * Delegates to [ActivityDumpParser] — the single tested parser. Three separate
     * hand-rolled variants of this logic previously produced wrong answers (see that
     * class's docs), so there must be exactly one implementation.
     */
    fun currentPackageOnDisplay(displayId: Int): String? {
        val output = privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        return ActivityDumpParser.foregroundPackageOnDisplay(output, displayId)
    }

    /** True if [packageName] currently has a task on [displayId]. */
    fun isOnDisplay(packageName: String, displayId: Int): Boolean =
        displayIdsHosting(packageName).contains(displayId)

    /**
     * Every display ID that currently hosts a task for [packageName].
     *
     * Returning the full set rather than a boolean is deliberate: knowing the app is on
     * *some other* display is the difference between "launch pending" and "the platform
     * put this app on the user's physical screen", and callers must be able to tell those
     * apart. A boolean check hid exactly that case.
     */
    fun displayIdsHosting(packageName: String): Set<Int> {
        val output = privilegeBackend.shell(arrayOf("dumpsys", "activity", "activities")).stdout
        return ActivityDumpParser.displayIdsHosting(output, packageName)
    }
}
