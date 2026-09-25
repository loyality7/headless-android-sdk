package com.headless.android

/**
 * Resolves Binder transaction codes per device instead of trusting hardcoded magic numbers.
 *
 * History (#13): a fuzzy reflection lookup once silently resolved the WRONG field (50
 * instead of 1) and moved the caller's own task instead of launching the target — caught
 * only by diffing old logs. So this resolver does EXACT field-name lookup on the AIDL
 * Stub class and nothing else: one name, one field, no searching, no fuzzy matching.
 * Unknown device/build → loud log + verified fallback constant (every call site already
 * verifies behavior: displayId>=0, on-display placement, post-release absence).
 *
 * // ponytail: exact lookup + fallback only. No version table, no AIDL parsing.
 */
object BinderCodes {

    fun displayCreate(): Int = stubCode(
        "android.hardware.display.IDisplayManager\$Stub",
        "TRANSACTION_createVirtualDisplay", 21
    )

    fun displayRelease(): Int = stubCode(
        "android.hardware.display.IDisplayManager\$Stub",
        "TRANSACTION_releaseVirtualDisplay", 24
    )

    fun atmStartActivity(): Int = stubCode(
        "android.app.IActivityTaskManager\$Stub",
        "TRANSACTION_startActivity", 1
    )

    // Separated for JVM tests (Class.forName never resolves on unit-test classpath).
    internal fun stubCode(stubClassName: String, fieldName: String, fallback: Int): Int {
        return try {
            val field = Class.forName(stubClassName).getDeclaredField(fieldName)
            val code = field.getInt(null)
            if (code == fallback) {
                HeadlessLog.i("BinderCodes", "$fieldName=$code (matches fallback)")
            } else {
                HeadlessLog.w(
                    "BinderCodes",
                    "$fieldName resolved $code, fallback was $fallback — using resolved, verify behavior"
                )
            }
            code
        } catch (e: Throwable) {
            HeadlessLog.w(
                "BinderCodes",
                "$fieldName lookup failed (${e.javaClass.simpleName}), using fallback $fallback"
            )
            fallback
        }
    }
}
