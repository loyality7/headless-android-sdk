package com.headless.android.privilege

import android.os.IBinder
import com.headless.android.HeadlessLog
import com.headless.android.PermissionDeniedException
import com.headless.android.ShizukuUnavailableException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Health status of Shizuku connection and permission.
 */
enum class ShizukuHealth {
    /** Fully connected with permission granted. */
    READY,
    /** Shizuku server binder is not present / server not started. */
    BINDER_NOT_CONNECTED,
    /** App was reinstalled or user revoked permission. */
    PERMISSION_REVOKED,
    /** Stale binder handle received but dead. */
    BINDER_DEAD
}

/**
 * [PrivilegeBackend] backed by Shizuku (shell UID 2000). This is the only
 * class in the runtime allowed to reference `rikka.shizuku.*` directly.
 */
class ShizukuBackend : PrivilegeBackend {

    companion object {
        private const val OP = "ShizukuBackend"
        private const val REQUEST_CODE = 8341 // arbitrary, scoped to this backend
        const val SHIZUKU_PERMISSION = "moe.shizuku.manager.permission.API_V23"

        fun grantPermissionCommand(packageName: String): String =
            "adb shell pm grant $packageName $SHIZUKU_PERMISSION"
    }

    override val name: String = "shizuku"

    @Volatile
    private var binderAlive: Boolean = false

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        binderAlive = true
        HeadlessLog.i(OP, "Shizuku binder received from service")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        binderAlive = false
        HeadlessLog.w(OP, "Shizuku binder died / service killed")
    }

    init {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            binderAlive = Shizuku.pingBinder()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "Failed to register Shizuku lifecycle listeners", e)
        }
    }

    override fun isAvailable(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "isAvailable check failed", e)
            false
        }
    }

    /**
     * Comprehensive health evaluation for debugging broken dev loops.
     */
    fun health(): ShizukuHealth {
        val ping = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        if (!ping) {
            return if (!isAvailable()) ShizukuHealth.BINDER_NOT_CONNECTED else ShizukuHealth.BINDER_DEAD
        }
        return if (isAuthorized()) {
            ShizukuHealth.READY
        } else {
            ShizukuHealth.PERMISSION_REVOKED
        }
    }

    /**
     * Waits up to [timeoutMs] for Shizuku's binder to arrive, then reports availability.
     *
     * Shizuku delivers its binder asynchronously through its ContentProvider after process
     * start, so an immediate [isAvailable] check in a cold-started component returns false
     * even when the Shizuku server is running fine. Observed failure: a freshly started
     * foreground service reported "Shizuku is not running or not reachable" while
     * `shizuku_server` was demonstrably alive. Callers that run early must wait rather
     * than conclude the backend is missing.
     */
    fun awaitAvailable(timeoutMs: Long = 5_000L, pollIntervalMs: Long = 150L): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (isAvailable()) return true
            try {
                Thread.sleep(pollIntervalMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return isAvailable()
            }
        }
        return isAvailable()
    }

    override fun isAuthorized(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
    }

    override fun isAlive(): Boolean = isAvailable() && isAuthorized()

    override fun capabilities(): Set<BackendCapability> = setOf(
        BackendCapability.TRUSTED_DISPLAY,
        BackendCapability.CROSS_DISPLAY_LAUNCH,
        BackendCapability.INPUT_INJECTION,
        BackendCapability.SHELL,
        BackendCapability.SYSTEM_SERVICE_BINDER
    )

    override fun info(): BackendInfo {
        var uid: Int? = null
        var selinux: String? = null
        var version: String? = null
        try {
            if (isAvailable()) {
                uid = Shizuku.getUid()
                selinux = Shizuku.getSELinuxContext()
                version = Shizuku.getVersion().toString()
            }
        } catch (e: Throwable) {
            HeadlessLog.d(OP, "info() partially unavailable: ${e.message}")
        }
        return BackendInfo(name = name, uid = uid, selinuxContext = selinux, version = version)
    }

    override suspend fun requestAuthorization(): Boolean {
        return requestAuthorization(10_000L)
    }

    /**
     * Requests authorization with timeout protection so a missing user response or
     * dead binder never hangs the automation dev loop indefinitely.
     */
    suspend fun requestAuthorization(timeoutMs: Long): Boolean {
        if (!isAvailable()) {
            awaitAvailable(3000L)
            if (!isAvailable()) throw ShizukuUnavailableException("Shizuku server is not running or reachable")
        }
        if (isAuthorized()) return true

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : Shizuku.OnRequestPermissionResultListener {
                    override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                        if (requestCode != REQUEST_CODE) return
                        Shizuku.removeRequestPermissionResultListener(this)
                        val granted = grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED
                        HeadlessLog.i(OP, "requestAuthorization result granted=$granted")
                        if (cont.isActive) cont.resume(granted)
                    }
                }
                Shizuku.addRequestPermissionResultListener(listener)
                cont.invokeOnCancellation { Shizuku.removeRequestPermissionResultListener(listener) }
                try {
                    Shizuku.requestPermission(REQUEST_CODE)
                } catch (e: Throwable) {
                    Shizuku.removeRequestPermissionResultListener(listener)
                    if (cont.isActive) cont.resumeWithException(e)
                }
            }
        } ?: run {
            HeadlessLog.w(OP, "requestAuthorization timed out after ${timeoutMs}ms (reinstall or dialog unhandled)")
            false
        }
    }

    override fun getSystemServiceBinder(serviceName: String): IBinder {
        if (!isAuthorized()) {
            val h = health()
            throw PermissionDeniedException(
                "Shizuku permission not granted (health=$h). If app was reinstalled, grant permission via: adb shell pm grant <package> $SHIZUKU_PERMISSION"
            )
        }
        val raw = SystemServiceHelper.getSystemService(serviceName)
            ?: throw ShizukuUnavailableException("System service '$serviceName' unavailable via Shizuku")
        return ShizukuBinderWrapper(raw)
    }

    override fun shell(command: Array<String>): ShellResult {
        val process = startProcess(command)
        // Read both streams before waiting: a command that fills a pipe buffer while we
        // block in waitFor() would deadlock.
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exit = process.waitFor()
        return ShellResult(exitCode = exit, stdout = stdout, stderr = stderr)
    }

    private fun startProcess(command: Array<String>): Process {
        if (!isAuthorized()) {
            val h = health()
            throw PermissionDeniedException(
                "Shizuku permission not granted (health=$h). If app was reinstalled, grant permission via: adb shell pm grant <package> $SHIZUKU_PERMISSION"
            )
        }
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        return newProcess.invoke(null, command, null, null) as Process
    }

    override fun close() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (_: Throwable) {}
    }
}
