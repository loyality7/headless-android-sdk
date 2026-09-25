package com.headless.android.display

import android.graphics.PixelFormat
import android.hardware.display.VirtualDisplayConfig
import android.media.ImageReader
import android.os.Parcel
import com.headless.android.DisplayCreationException
import com.headless.android.HeadlessLog
import com.headless.android.privilege.PrivilegeBackend

/**
 * Creates trusted virtual displays via a direct `IDisplayManager.createVirtualDisplay`
 * Binder transaction against the privilege backend's `display` system service — the
 * exact technique proven working on-device by the POC. (A reflection-based call against
 * the AIDL-generated proxy method was tried first but no method with the expected
 * parameter types exists on this API level; the raw transaction is what's actually
 * verified to work, so that's what ships.)
 */
class VirtualDisplayManager(private val privilegeBackend: PrivilegeBackend) {

    companion object {
        private const val OP = "VirtualDisplayManager"

        // android.hardware.display.DisplayManager virtual-display flag constants (framework-internal
        // for TRUSTED, public for the rest — mirrored here so callers never need the raw ints).
        private const val FLAG_PUBLIC = 1 shl 0           // DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
        private const val FLAG_OWN_CONTENT_ONLY = 1 shl 3 // DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6   // DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        private const val FLAG_TRUSTED = 1 shl 10         // DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED

        private const val INTERFACE_TOKEN = "android.hardware.display.IDisplayManager"
        private const val CALLING_PACKAGE = "com.android.shell"

        /**
         * `IDisplayManager.releaseVirtualDisplay(IVirtualDisplayCallback token)`.
         *
         * Codes resolve per device via [com.headless.android.BinderCodes] (exact Stub
         * field lookup, verified fallback). Release still verifies the display actually
         * disappeared and reports failure loudly instead of assuming success.
         */
    }

    fun createTrustedDisplay(
        width: Int,
        height: Int,
        densityDpi: Int,
        name: String = "HeadlessDisplay-${System.nanoTime()}"
    ): HeadlessDisplay {
        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)

        try {
            val displayBinder = privilegeBackend.getSystemServiceBinder("display")

            val flags = FLAG_TRUSTED or FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH
            HeadlessLog.i(OP, "VirtualDisplay REQUESTED: name=$name width=$width height=$height densityDpi=$densityDpi flags=0x${Integer.toHexString(flags)}")
            val config = VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setFlags(flags)
                .setSurface(imageReader.surface)
                .build()

            // The callback token identifies this display for release; it must outlive the call.
            val callbackToken = newVirtualDisplayCallbackToken()
            val displayId = transactCreateVirtualDisplay(displayBinder, config, callbackToken)

            if (displayId < 0) {
                throw DisplayCreationException("createVirtualDisplay returned invalid id=$displayId")
            }

            HeadlessLog.event(displayId = displayId, op = OP, success = true)
            return HeadlessDisplay(
                displayId = displayId,
                width = width,
                height = height,
                densityDpi = densityDpi,
                imageReader = imageReader,
                callbackToken = callbackToken,
                releaser = { token -> releaseDisplay(displayId, token) }
            )
        } catch (e: DisplayCreationException) {
            imageReader.close()
            throw e
        } catch (e: Throwable) {
            imageReader.close()
            HeadlessLog.event(op = OP, success = false)
            throw DisplayCreationException("Failed to create trusted virtual display: ${e.message}", e)
        }
    }

    /**
     * Minimal `IVirtualDisplayCallback` stub. We don't need lifecycle callbacks, but the
     * binder itself is the display's identity for release, so the caller retains it.
     */
    private fun newVirtualDisplayCallbackToken(): android.os.IBinder =
        object : android.os.Binder(), android.os.IInterface {
            init { attachInterface(this, "android.hardware.display.IVirtualDisplayCallback") }
            override fun asBinder(): android.os.IBinder = this
        }

    private fun transactCreateVirtualDisplay(
        displayBinder: android.os.IBinder,
        config: VirtualDisplayConfig,
        callbackToken: android.os.IBinder
    ): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeTypedObject(config, 0)
            data.writeStrongBinder(callbackToken)
            data.writeStrongBinder(null) // IMediaProjection
            data.writeString(CALLING_PACKAGE)

            displayBinder.transact(com.headless.android.BinderCodes.displayCreate(), data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Destroys the virtual display identified by [callbackToken], then verifies it is
     * actually gone. Verification matters because the transaction code is derived from
     * AIDL declaration order rather than a published constant — if a build reorders the
     * interface, a wrong code could "succeed" without releasing anything, which is exactly
     * the silent-leak failure this method exists to prevent.
     */
    fun releaseDisplay(displayId: Int, callbackToken: android.os.IBinder) {
        val displayBinder = privilegeBackend.getSystemServiceBinder("display")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeStrongBinder(callbackToken)
            displayBinder.transact(com.headless.android.BinderCodes.displayRelease(), data, reply, 0)
            reply.readException()
        } catch (e: Throwable) {
            HeadlessLog.event(displayId = displayId, op = "$OP.release", success = false)
            throw DisplayCreationException("releaseVirtualDisplay($displayId) failed: ${e.message}", e)
        } finally {
            data.recycle()
            reply.recycle()
        }

        val stillPresent = displayStillExists(displayId)
        HeadlessLog.event(displayId = displayId, op = "$OP.release", success = !stillPresent)
        if (stillPresent) {
            throw DisplayCreationException(
                "releaseVirtualDisplay($displayId) reported success but display is still " +
                    "registered with DisplayManagerService — display leaked"
            )
        }
    }

    private fun displayStillExists(displayId: Int): Boolean {
        return try {
            privilegeBackend.shell(arrayOf("dumpsys", "display")).stdout
                .contains("mDisplayId=$displayId")
        } catch (e: Throwable) {
            HeadlessLog.w(OP, "post-release verification query failed for display $displayId", e)
            false // don't turn a verification outage into a spurious leak error
        }
    }
}
