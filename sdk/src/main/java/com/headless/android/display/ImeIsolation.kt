package com.headless.android.display

import android.os.Parcel
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Controls whether a display may show an input method (soft keyboard).
 *
 * ## Why this exists
 *
 * Android runs a single IME, and a secondary display's default policy is
 * `DISPLAY_IME_POLICY_FALLBACK_DISPLAY`: when an app on that display takes text focus, the
 * keyboard is shown on the **default display**. Observed on-device as `mCurTokenDisplayId=0`
 * while automating an app on a hidden display — typing into the hidden app made the
 * keyboard appear on the user's physical screen. That breaks the core promise that
 * automation is invisible to the user.
 *
 * `DISPLAY_IME_POLICY_HIDE` prevents an IME connection for the display entirely.
 *
 * ## Why this may fail, by design
 *
 * `WindowManagerService.setDisplayImePolicy` enforces two independent gates (verified in
 * AOSP android14-release source):
 *
 *  1. `checkCallingPermission(INTERNAL_SYSTEM_WINDOW)` → SecurityException if absent.
 *     Shell (`com.android.shell`) was observed holding this permission `granted=true` on
 *     the test device, so a Shizuku-backed call can plausibly pass — but this is a
 *     signature|module permission and must not be assumed on other devices/ROMs.
 *  2. `if (!displayContent.isTrusted()) throw SecurityException(...)` → the display must be
 *     trusted. We create displays with `VIRTUAL_DISPLAY_FLAG_TRUSTED`, but setting the flag
 *     and the platform honouring it are different claims.
 *
 * Both gates are therefore probed at runtime and reported, never presumed.
 */
class ImeIsolation(private val privilegeBackend: PrivilegeBackend) {

    companion object {
        private const val OP = "ImeIsolation"
        private const val INTERFACE_TOKEN = "android.view.IWindowManager"

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
        val canSetHide: Boolean,
        val policyAfterSet: Int?,
        val failureReason: String?
    ) {
        /** True only if the display provably cannot show an IME anywhere. */
        val isolated: Boolean get() = policyAfterSet == POLICY_HIDE

        fun summary(): String = buildString {
            append("display=$displayId")
            append(" query=$canQuery")
            append(" current=${currentPolicy?.let { policyName(it) } ?: "unknown"}")
            append(" setHide=$canSetHide")
            append(" after=${policyAfterSet?.let { policyName(it) } ?: "unknown"}")
            failureReason?.let { append(" reason=$it") }
        }
    }

    /**
     * Transaction codes are resolved by name from `IWindowManager$Stub` rather than
     * hardcoded. Hardcoding a guessed code previously caused a silent wrong-method call
     * (an activity-launch transaction that moved the caller's own task instead), so this
     * path fails loudly if the code cannot be resolved.
     */
    private fun transactionCode(methodName: String): Int {
        val stub = Class.forName("android.view.IWindowManager\$Stub")
        val field = stub.declaredFields.firstOrNull { it.name == "TRANSACTION_$methodName" }
            ?: throw NoSuchFieldException(
                "TRANSACTION_$methodName not found on IWindowManager\$Stub — cannot call it safely"
            )
        field.isAccessible = true
        return field.getInt(null)
    }

    /**
     * Discovers the `getDisplayImePolicy` transaction code by probing candidates with the
     * **read-only** getter and accepting only a code whose reply parses as a valid policy
     * value for a known-good display.
     *
     * Why probe at all: `TRANSACTION_*` constants are not reflectively visible on this
     * build, and AIDL codes are assigned by declaration order — which OEM forks can shift.
     * Why probe with the *getter*: an incorrect code invoked blind can perform a completely
     * different privileged operation. That already happened once in this project (a guessed
     * activity-launch code silently relocated the caller's own task), so discovery must use
     * an operation that cannot mutate state, and the write is only attempted once the
     * read has confirmed the offset.
     *
     * Display 0's policy is used as the oracle: it must exist and must report a valid policy.
     */
    fun discoverGetPolicyCode(searchFrom: Int = 90, searchTo: Int = 130): Int? {
        val binder = privilegeBackend.getSystemServiceBinder("window")
        for (code in searchFrom..searchTo) {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(INTERFACE_TOKEN)
                data.writeInt(0) // display 0 — always exists
                val ok = binder.transact(code, data, reply, 0)
                if (!ok) continue
                reply.readException()
                val value = reply.readInt()
                // A valid reply is exactly one int in {LOCAL, FALLBACK_DISPLAY, HIDE} and
                // nothing further in the parcel.
                // Additional discrimination: a probe hit must ALSO behave like the real
                // getter — display 0 reports a valid policy AND a nonexistent display id
                // must not return a valid policy. A naive "reply is 0..2" check found
                // code 60 on-device, which was a different method returning 0 (its write
                // counterpart silently did nothing), so the value check alone is unsafe.
                if (value in POLICY_LOCAL..POLICY_HIDE && reply.dataAvail() == 0 &&
                    !respondsSameForBogusDisplay(binder, code, value)
                ) {
                    HeadlessLog.i(OP, "getDisplayImePolicy appears to be transaction code $code (display 0 → ${policyName(value)})")
                    return code
                }
            } catch (e: Throwable) {
                // Wrong code: mismatched interface/args throw. Expected during probing.
            } finally {
                data.recycle()
                reply.recycle()
            }
        }
        HeadlessLog.w(OP, "could not discover getDisplayImePolicy transaction code in $searchFrom..$searchTo")
        return null
    }

    /**
     * True if [code] returns the same value for a nonexistent display id as it did for
     * display 0 — a sign the method ignores its argument and is therefore not the getter.
     */
    private fun respondsSameForBogusDisplay(binder: android.os.IBinder, code: Int, display0Value: Int): Boolean {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(999_999) // no such display
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt() == display0Value
        } catch (e: Throwable) {
            false // threw for a bogus display — consistent with a real per-display getter
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    @Volatile
    private var discoveredGetCode: Int? = null

    /** Resolves the getter code, preferring reflection and falling back to probing. */
    private fun getPolicyCode(): Int {
        discoveredGetCode?.let { return it }
        val code = try {
            transactionCode("getDisplayImePolicy")
        } catch (e: Throwable) {
            discoverGetPolicyCode()
                ?: throw NoSuchFieldException("cannot resolve getDisplayImePolicy transaction code")
        }
        discoveredGetCode = code
        return code
    }

    /**
     * Setter code. In IWindowManager.aidl, setDisplayImePolicy is declared immediately
     * after getDisplayImePolicy, so its transaction code is the getter code + 1. Derived
     * rather than guessed, and only used after the getter code has been *confirmed* by a
     * successful read — and every write is followed by a read-back to prove it took effect.
     */
    private fun setPolicyCode(): Int = getPolicyCode() + 1

    fun getPolicy(displayId: Int): Int {
        val binder = privilegeBackend.getSystemServiceBinder("window")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            binder.transact(getPolicyCode(), data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    fun setPolicy(displayId: Int, policy: Int) {
        val binder = privilegeBackend.getSystemServiceBinder("window")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeInt(displayId)
            data.writeInt(policy)
            binder.transact(setPolicyCode(), data, reply, 0)
            reply.readException() // surfaces the SecurityException from either WMS gate
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Probes what's actually possible for [displayId] and attempts to isolate it.
     *
     * Never throws: the point is to produce a truthful capability report, including the
     * exact reason isolation was refused, so callers can decide whether to proceed with a
     * different strategy (avoid the IME entirely) or refuse to run.
     */
    fun isolate(displayId: Int): Report {
        var canQuery = false
        var current: Int? = null
        var canSet = false
        var after: Int? = null
        var reason: String? = null

        try {
            current = getPolicy(displayId)
            canQuery = true
            HeadlessLog.i(OP, "display $displayId current IME policy: ${policyName(current)}")
        } catch (e: Throwable) {
            reason = "getDisplayImePolicy failed: ${e.javaClass.simpleName}: ${e.message}"
            HeadlessLog.w(OP, "cannot query IME policy for display $displayId", e)
        }

        try {
            setPolicy(displayId, POLICY_HIDE)
            canSet = true
        } catch (e: Throwable) {
            reason = "setDisplayImePolicy(HIDE) failed: ${e.javaClass.simpleName}: ${e.message}"
            HeadlessLog.w(OP, "cannot set IME policy HIDE for display $displayId", e)
        }

        if (canQuery) {
            after = try {
                getPolicy(displayId)
            } catch (e: Throwable) {
                null
            }
        }

        val report = Report(displayId, canQuery, current, canSet, after, reason)
        HeadlessLog.event(displayId = displayId, op = "$OP.isolate", success = report.isolated)
        HeadlessLog.i(OP, report.summary())
        return report
    }
}
