package com.headless.android.privilege

import android.os.IBinder
import com.headless.android.HeadlessLog
import com.headless.android.PermissionDeniedException
import com.headless.android.ShizukuUnavailableException
import kotlinx.coroutines.suspendCancellableCoroutine
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import kotlin.coroutines.resume

/**
 * [PrivilegeBackend] backed by Shizuku (shell UID 2000). This is the only
 * class in the runtime allowed to reference `rikka.shizuku.*` directly.
 */
class ShizukuBackend : PrivilegeBackend {

    companion object {
        private const val OP = "ShizukuBackend"
        private const val REQUEST_CODE = 8341 // arbitrary, scoped to this backend
    }

    override val name: String = "shizuku"

    override fun isAvailable(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "isAvailable check failed", e)
            false
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
        if (!isAvailable()) throw ShizukuUnavailableException()
        if (isAuthorized()) return true

        return suspendCancellableCoroutine { cont ->
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
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    override fun getSystemServiceBinder(serviceName: String): IBinder {
        if (!isAuthorized()) throw PermissionDeniedException("Shizuku permission not granted")
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
        if (!isAuthorized()) throw PermissionDeniedException("Shizuku permission not granted")
        val newProcess = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java
        ).apply { isAccessible = true }

        return newProcess.invoke(null, command, null, null) as Process
    }

    override fun close() {
        // Shizuku's binder/listener lifecycle is process-wide; nothing owned per-backend to release yet.
    }
}
