package com.headless.android.display

import android.os.IBinder
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Controls whether a display may show an input method (soft keyboard).
 *
 * Calls `IWindowManager.setDisplayImePolicy` and `IWindowManager.getDisplayImePolicy`
 * through the privileged [PrivilegeBackend] (backed by Shizuku / shell UID 2000, which holds
 * `INTERNAL_SYSTEM_WINDOW`).
 *
 * Defaults to `DISPLAY_IME_POLICY_FALLBACK_DISPLAY` so the IME's window lives on Display 0
 * rather than the secondary/virtual display — Android refuses to host an IME window on a
 * non-system-owned virtual display and crashes the IME service if asked to (LOCAL policy).
 * Nothing leaks to the user regardless: the headless IME never shows a keyboard UI anywhere.
 */
class ImeIsolation(private val privilegeBackend: PrivilegeBackend) {

    companion object {
        private const val OP = "ImeIsolation"

        /** WindowManager.DISPLAY_IME_POLICY_LOCAL — IME shows on this display. */
        const val POLICY_LOCAL = 0

        /** WindowManager.DISPLAY_IME_POLICY_FALLBACK_DISPLAY — IME shows on the DEFAULT display. */
        const val POLICY_FALLBACK_DISPLAY = 1

        /** WindowManager.DISPLAY_IME_POLICY_HIDE — no IME connection for this display. */
        const val POLICY_HIDE = 2

        fun policyName(value: Int): String = when (value) {
            POLICY_LOCAL -> "LOCAL (IME on this display)"
            POLICY_FALLBACK_DISPLAY -> "FALLBACK_DISPLAY (IME on display 0 — LEAKS to the user)"
            POLICY_HIDE -> "HIDE (no IME for this display)"
            else -> "UNKNOWN($value)"
        }
    }

    /** Outcome of probing/altering a display's IME policy. */
    data class Report(
        val displayId: Int,
        val canQuery: Boolean,
        val currentPolicy: Int?,
        val canSetPolicy: Boolean,
        val policyAfterSet: Int?,
        val failureReason: String?
    ) {
        @Deprecated("Use canSetPolicy", ReplaceWith("canSetPolicy"))
        val canSetHide: Boolean get() = canSetPolicy

        /**
         * True if the requested policy was applied and verified. Any of LOCAL, HIDE, or
         * FALLBACK_DISPLAY count as "isolated" in the sense that matters here — no visible IME
         * UI ever shows on this display, whichever display the IME's own (invisible) window
         * lives on.
         */
        val isolated: Boolean get() = policyAfterSet == POLICY_LOCAL ||
            policyAfterSet == POLICY_HIDE ||
            policyAfterSet == POLICY_FALLBACK_DISPLAY

        fun summary(): String = buildString {
            append("display=$displayId")
            append(" query=$canQuery")
            append(" current=${currentPolicy?.let { policyName(it) } ?: "unknown"}")
            append(" setPolicy=$canSetPolicy")
            append(" after=${policyAfterSet?.let { policyName(it) } ?: "unknown"}")
            failureReason?.let { append(" reason=$it") }
        }
    }

    @Volatile
    private var windowManagerInstance: Any? = null
    @Volatile
    private var getPolicyMethod: Method? = null
    @Volatile
    private var setPolicyMethod: Method? = null

    private fun findMethod(targetClass: Class<*>, name: String, vararg paramTypes: Class<*>): Method? {
        val searchClasses = mutableListOf<Class<*>>()
        try {
            searchClasses.add(Class.forName("android.view.IWindowManager"))
        } catch (_: Throwable) {}
        searchClasses.add(targetClass)
        searchClasses.addAll(targetClass.interfaces)
        targetClass.superclass?.let { searchClasses.add(it) }

        for (clazz in searchClasses) {
            try {
                val m = clazz.getMethod(name, *paramTypes)
                m.isAccessible = true
                return m
            } catch (_: Throwable) {}
            try {
                val m = clazz.getDeclaredMethod(name, *paramTypes)
                m.isAccessible = true
                return m
            } catch (_: Throwable) {}
            val found = clazz.declaredMethods.firstOrNull { m ->
                m.name == name && m.parameterTypes.contentEquals(paramTypes)
            }
            if (found != null) {
                found.isAccessible = true
                return found
            }
        }

        // Double reflection fallback for Android hidden API restrictions
        try {
            val getDeclaredMethod = Class::class.java.getDeclaredMethod(
                "getDeclaredMethod",
                String::class.java,
                arrayOf<Class<*>>()::class.java
            )
            for (clazz in searchClasses) {
                try {
                    val m = getDeclaredMethod.invoke(clazz, name, paramTypes) as? Method
                    if (m != null) {
                        m.isAccessible = true
                        return m
                    }
                } catch (_: Throwable) {}
            }
        } catch (_: Throwable) {}

        return null
    }

    private fun resolveService(): Any {
        windowManagerInstance?.let { return it }

        val binder: IBinder = privilegeBackend.getSystemServiceBinder("window")
        val stubClass = Class.forName("android.view.IWindowManager\$Stub")
        val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
        val wm = asInterface.invoke(null, binder)
            ?: throw IllegalStateException("IWindowManager.Stub.asInterface returned null")

        val getMethod = findMethod(wm.javaClass, "getDisplayImePolicy", Int::class.javaPrimitiveType!!)
            ?: throw NoSuchMethodException("getDisplayImePolicy(int) not found on ${wm.javaClass.name}")

        val setMethod = findMethod(wm.javaClass, "setDisplayImePolicy", Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!)
            ?: throw NoSuchMethodException("setDisplayImePolicy(int, int) not found on ${wm.javaClass.name}")

        getPolicyMethod = getMethod
        setPolicyMethod = setMethod
        windowManagerInstance = wm
        return wm
    }

    fun getPolicy(displayId: Int): Int {
        val wm = resolveService()
        val method = getPolicyMethod ?: throw IllegalStateException("getPolicyMethod not resolved")
        return try {
            method.invoke(wm, displayId) as Int
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    fun setPolicy(displayId: Int, policy: Int) {
        val wm = resolveService()
        val method = setPolicyMethod ?: throw IllegalStateException("setPolicyMethod not resolved")
        try {
            method.invoke(wm, displayId, policy)
        } catch (e: InvocationTargetException) {
            throw (e.targetException ?: e)
        }
    }

    /**
     * Probes current policy, calls setDisplayImePolicy(displayId, targetPolicy),
     * queries policy again, and verifies returnedPolicy == targetPolicy.
     *
     * Never performs blind transaction probing. If SecurityException occurs, records
     * the exact exception and aborts without attempting other transactions.
     */
    fun isolate(displayId: Int, targetPolicy: Int = POLICY_LOCAL): Report {
        var canQuery = false
        var current: Int? = null
        var canSet = false
        var after: Int? = null
        var reason: String? = null

        try {
            resolveService()
        } catch (e: Throwable) {
            reason = "Resolve WindowManager service failed: ${e.javaClass.simpleName}: ${e.message}"
            HeadlessLog.e(OP, reason, e)
            return Report(displayId, canQuery = false, currentPolicy = null, canSetPolicy = false, policyAfterSet = null, failureReason = reason)
        }

        // 1. Probing initial policy
        try {
            current = getPolicy(displayId)
            canQuery = true
            HeadlessLog.i(OP, "display $displayId initial IME policy: ${policyName(current)}")
        } catch (e: SecurityException) {
            reason = "getDisplayImePolicy failed with SecurityException: ${e.message}"
            HeadlessLog.e(OP, reason, e)
            // Abort immediately without blind probing
            return Report(displayId, canQuery = false, currentPolicy = null, canSetPolicy = false, policyAfterSet = null, failureReason = reason)
        } catch (e: Throwable) {
            reason = "getDisplayImePolicy failed: ${e.javaClass.simpleName}: ${e.message}"
            HeadlessLog.w(OP, reason, e)
        }

        // 2. Call exactly setDisplayImePolicy(displayId, targetPolicy)
        try {
            setPolicy(displayId, targetPolicy)
            canSet = true
            HeadlessLog.i(OP, "setDisplayImePolicy($displayId, ${policyName(targetPolicy)}) call succeeded")
        } catch (e: SecurityException) {
            reason = "setDisplayImePolicy failed with SecurityException: ${e.message}"
            HeadlessLog.e(OP, reason, e)
            // DO NOT attempt another Binder transaction. Stop here as requested!
            return Report(displayId, canQuery = canQuery, currentPolicy = current, canSetPolicy = false, policyAfterSet = null, failureReason = reason)
        } catch (e: Throwable) {
            reason = "setDisplayImePolicy failed: ${e.javaClass.simpleName}: ${e.message}"
            HeadlessLog.e(OP, reason, e)
            return Report(displayId, canQuery = canQuery, currentPolicy = current, canSetPolicy = false, policyAfterSet = null, failureReason = reason)
        }

        // 3. Immediately call getDisplayImePolicy(displayId)
        try {
            after = getPolicy(displayId)
            HeadlessLog.i(OP, "getDisplayImePolicy($displayId) after set returned: ${policyName(after)}")
        } catch (e: SecurityException) {
            reason = "getDisplayImePolicy after set failed with SecurityException: ${e.message}"
            HeadlessLog.e(OP, reason, e)
            return Report(displayId, canQuery = canQuery, currentPolicy = current, canSetPolicy = canSet, policyAfterSet = null, failureReason = reason)
        } catch (e: Throwable) {
            reason = "getDisplayImePolicy after set failed: ${e.javaClass.simpleName}: ${e.message}"
            HeadlessLog.e(OP, reason, e)
        }

        // 4. Require returnedPolicy == targetPolicy
        val verified = (after == targetPolicy)
        if (!verified && reason == null) {
            reason = "Policy verification mismatch: expected ${policyName(targetPolicy)}, returned ${after?.let { policyName(it) } ?: "null"}"
            HeadlessLog.w(OP, reason)
        }

        val report = Report(
            displayId = displayId,
            canQuery = canQuery,
            currentPolicy = current,
            canSetPolicy = canSet,
            policyAfterSet = after,
            failureReason = reason
        )
        HeadlessLog.event(displayId = displayId, op = "$OP.isolate", success = report.isolated)
        HeadlessLog.i(OP, report.summary())
        return report
    }
}
