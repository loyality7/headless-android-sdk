package com.headless.android.privilege

import android.os.IBinder

/**
 * Abstraction over how the runtime obtains elevated Android privileges
 * (trusted display creation, cross-display activity launch, input injection
 * into a display other than the caller's own).
 *
 * The SDK core must never reference Shizuku, root, or any other concrete
 * mechanism directly — only this interface. [ShizukuBackend] is the first
 * implementation; a future [RootBackend] or `StandardAndroidBackend` (for
 * capabilities that don't need elevation at all) can be added without
 * touching any call site.
 */
interface PrivilegeBackend {

    /** True if the backend's privilege source is installed/reachable at all. */
    fun isAvailable(): Boolean

    /** True if this backend currently holds a granted, usable privilege. */
    fun isAuthorized(): Boolean

    /**
     * Requests authorization if not already granted. Suspends until the
     * user/system responds. Returns true if authorized afterward.
     */
    suspend fun requestAuthorization(): Boolean

    /** Obtains an [IBinder] for the named system service, wrapped so calls run with this backend's privilege. */
    fun getSystemServiceBinder(serviceName: String): IBinder

    /** Runs a shell command with this backend's privilege and waits for it to exit. Returns the exit code. */
    fun runShellCommand(command: Array<String>): Int

    /** Runs a shell command with this backend's privilege and returns its captured stdout. */
    fun captureShellOutput(command: Array<String>): String

    /** Releases any held resources (listeners, connections). Safe to call multiple times. */
    fun close()
}
