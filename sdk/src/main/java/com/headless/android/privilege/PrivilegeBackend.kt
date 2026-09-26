package com.headless.android.privilege

import android.os.IBinder

/**
 * Result of a privileged shell invocation.
 *
 * Both [exitCode] and [stderr] matter for execution-level verification: Android shell
 * commands can exit non-zero, *and* can print an exception to stderr while still exiting
 * zero. Checking only one of the two is how an invalid command flag went unnoticed
 * during earlier development, so the contract exposes both.
 */
data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String
) {
    /** Command ran and reported no failure on either channel. */
    val isSuccess: Boolean get() = exitCode == 0 && !looksLikeError

    /** True if stderr carries an exception/error signature even though the exit code may be 0. */
    val looksLikeError: Boolean
        get() = stderr.contains("Exception") ||
            stderr.contains("Error:") ||
            stderr.contains("error:") ||
            stderr.contains("Failure")

    /** Short single-line summary suitable for failure evidence. */
    fun summary(): String {
        val detail = (stderr.ifBlank { stdout }).trim().replace("\n", " ").take(200)
        return "exit=$exitCode${if (detail.isNotEmpty()) " · $detail" else ""}"
    }
}

/** A discrete privileged capability the runtime may require. */
enum class BackendCapability {
    /** Can create a virtual display carrying the TRUSTED flag. */
    TRUSTED_DISPLAY,

    /** Can launch an activity onto a display the caller does not own. */
    CROSS_DISPLAY_LAUNCH,

    /** Can inject input events targeting a specific display. */
    INPUT_INJECTION,

    /** Can run arbitrary shell commands. */
    SHELL,

    /** Can obtain privileged IBinder handles to system services. */
    SYSTEM_SERVICE_BINDER
}

/** Diagnostic identity of a backend, for logs and failure evidence. */
data class BackendInfo(
    val name: String,
    val uid: Int?,
    val selinuxContext: String?,
    val version: String?
)

/**
 * Abstraction over how the runtime obtains elevated Android privileges
 * (trusted display creation, cross-display activity launch, input injection
 * into a display other than the caller's own).
 *
 * The runtime must never reference Shizuku, root, ADB, or any other concrete
 * mechanism directly — only this interface. [ShizukuBackend] is the first
 * implementation; a root backend, an ADB-tcp backend, or a plain-Android backend
 * (for capabilities needing no elevation) can be added without touching any call site.
 *
 * Implementations must be safe to call from any thread.
 */
interface PrivilegeBackend {

    /** Stable identifier for this backend implementation, e.g. "shizuku". */
    val name: String

    /** True if the backend's privilege source is installed/reachable at all. */
    fun isAvailable(): Boolean

    /** True if this backend currently holds a granted, usable privilege. */
    fun isAuthorized(): Boolean

    /**
     * True if the backend is still usable *right now* — available, authorized, and its
     * transport (binder/connection/process) has not died. Sessions poll this to detect
     * mid-run privilege loss rather than discovering it via a failed action.
     */
    fun isAlive(): Boolean

    /**
     * Capabilities this backend claims to provide. The runtime checks these up front so
     * it can fail with a clear "backend cannot do X" rather than attempting an operation
     * that will fail obscurely deep inside a Binder transaction.
     *
     * A claim here is a declaration of intent, not a guarantee the platform will permit
     * the operation on a given device — actions still verify their own outcomes.
     */
    fun capabilities(): Set<BackendCapability>

    /** Diagnostic identity for logging and failure evidence. */
    fun info(): BackendInfo

    /**
     * Requests authorization if not already granted. Suspends until the
     * user/system responds. Returns true if authorized afterward.
     */
    suspend fun requestAuthorization(): Boolean

    /** Obtains an [IBinder] for the named system service, wrapped so calls run with this backend's privilege. */
    fun getSystemServiceBinder(serviceName: String): IBinder

    /**
     * Runs a shell command with this backend's privilege, waits for it to exit, and
     * returns exit code plus both output streams.
     */
    fun shell(command: Array<String>): ShellResult

    /** Releases any held resources (listeners, connections). Safe to call multiple times. */
    fun close()

    /** Registers a callback invoked if the privilege transport dies. */
    fun addOnDeadListener(listener: () -> Unit) {}

    /** Unregisters a callback. */
    fun removeOnDeadListener(listener: () -> Unit) {}
}
