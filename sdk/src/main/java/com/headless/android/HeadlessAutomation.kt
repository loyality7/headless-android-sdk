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
     * Starts the runtime with the given (or default) [PrivilegeBackend]. [context] is
     * accepted for API symmetry with future backends that need it; the current
     * [ShizukuBackend] doesn't require it.
     */
    fun start(context: Context, backend: PrivilegeBackend = ShizukuBackend()): HeadlessRuntime {
        return HeadlessRuntime(backend)
    }
}
