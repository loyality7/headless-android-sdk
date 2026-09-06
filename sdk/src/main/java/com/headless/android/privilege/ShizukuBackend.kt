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
 * class in the SDK allowed to reference `rikka.shizuku.*` directly.
 */
class ShizukuBackend : PrivilegeBackend {

    companion object {
        private const val OP = "ShizukuBackend"
        private const val REQUEST_CODE = 8341 // arbitrary, scoped to this backend
    }

    override fun isAvailable(): Boolean {
        return try {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "isAvailable check failed", e)
            false
        }
    }

    override fun isAuthorized(): Boolean {
        if (!isAvailable()) return false
        return try {
            Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
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

    override fun runShellCommand(command: Array<String>): Int {
        return startProcess(command).waitFor()
    }

    override fun captureShellOutput(command: Array<String>): String {
        val process = startProcess(command)
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        process.waitFor()
        return if (stderr.isBlank()) stdout else "$stdout\n$stderr"
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
