package com.headless.android.state

import com.headless.android.privilege.BackendCapability
import com.headless.android.privilege.BackendInfo

/**
 * A point-in-time snapshot of everything the runtime knows about its own operating
 * conditions. Captured before and after actions so failures carry evidence of the
 * conditions they happened under, rather than just "it didn't work".
 */
data class DeviceState(
    val backend: BackendInfo,
    val backendAlive: Boolean,
    val backendCapabilities: Set<BackendCapability>,
    val capturedAtMillis: Long
)

/**
 * A point-in-time snapshot of one session's state.
 *
 * [displayAlive] is deliberately separate from [sessionOpen]: a session can be open
 * (not closed by the caller) while its underlying virtual display has been destroyed
 * by the platform. Conflating those two is how "the action ran fine" gets reported
 * for a display that no longer exists.
 */
data class SessionState(
    val sessionId: String,
    val displayId: Int,
    val sessionOpen: Boolean,
    val displayAlive: Boolean,
    val currentPackage: String?,
    val capturedAtMillis: Long
) {
    /** True if this session can currently be acted upon at all. */
    val usable: Boolean get() = sessionOpen && displayAlive

    fun summary(): String =
        "session=$sessionId display=$displayId open=$sessionOpen displayAlive=$displayAlive pkg=${currentPackage ?: "-"}"
}
