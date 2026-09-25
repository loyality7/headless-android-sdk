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

    /** Force-stops [packageName] and waits until its tasks are removed from the system. */
    fun stop(packageName: String): Boolean {
        val result = privilegeBackend.shell(arrayOf("am", "force-stop", packageName))
        // Wait up to 1.5 seconds for ActivityTaskManager to tear down all tasks
        repeat(15) {
            if (displayIdsHosting(packageName).isEmpty()) {
                HeadlessLog.event(packageName = packageName, op = "$OP.stop", success = true)
                return true
            }
            Thread.sleep(100)
        }
        HeadlessLog.event(packageName = packageName, op = "$OP.stop", success = result.isSuccess)
        return result.isSuccess
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
