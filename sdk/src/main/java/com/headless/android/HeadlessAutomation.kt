package com.headless.android

import android.content.Context
import com.headless.android.privilege.PrivilegeBackend
import com.headless.android.privilege.ShizukuBackend

/**
 * SDK entry point. This is the only class a consumer needs to import to get started:
 *
 * ```
 * val runtime = HeadlessAutomation.start(context)
 * if (!runtime.isAuthorized()) runtime.requestAuthorization()
 * val session = runtime.createSession()
 * session.launch("com.android.chrome")
 * ```
 */
object HeadlessAutomation {

    /**
     * Starts the runtime with the given (or default on-device [ShizukuBackend]).
     */
    fun start(
        context: Context,
        backend: PrivilegeBackend = ShizukuBackend(),
        maxSessions: Int = 1,
        autoSwitchIme: Boolean = false
    ): HeadlessRuntime {
        val pkg = context.packageName
        return HeadlessRuntime(
            backend,
            ledgerDir = java.io.File(context.filesDir, "headless"),
            headlessImeId = "$pkg/com.headless.android.ime.HeadlessImeService",
            maxSessions = maxSessions,
            autoSwitchIme = autoSwitchIme,
            apkPath = context.applicationInfo.sourceDir
        )
    }
}
