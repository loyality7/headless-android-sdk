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
        private const val FLAG_SUPPORTS_TOUCH = 1 shl 6   // DisplayManager.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
        private const val FLAG_TRUSTED = 1 shl 10          // DisplayManager.VIRTUAL_DISPLAY_FLAG_TRUSTED

        private const val INTERFACE_TOKEN = "android.hardware.display.IDisplayManager"
        private const val TRANSACTION_CREATE_VIRTUAL_DISPLAY = 21
        private const val CALLING_PACKAGE = "com.android.shell"
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

            val flags = FLAG_TRUSTED or FLAG_SUPPORTS_TOUCH
            val config = VirtualDisplayConfig.Builder(name, width, height, densityDpi)
                .setFlags(flags)
                .setSurface(imageReader.surface)
                .build()

            val displayId = transactCreateVirtualDisplay(displayBinder, config)

            if (displayId < 0) {
                throw DisplayCreationException("createVirtualDisplay returned invalid id=$displayId")
            }

            HeadlessLog.event(displayId = displayId, op = OP, success = true)
            return HeadlessDisplay(displayId, width, height, densityDpi, imageReader, virtualDisplay = null)
        } catch (e: DisplayCreationException) {
            imageReader.close()
            throw e
        } catch (e: Throwable) {
            imageReader.close()
            HeadlessLog.event(op = OP, success = false)
            throw DisplayCreationException("Failed to create trusted virtual display: ${e.message}", e)
        }
    }

    private fun transactCreateVirtualDisplay(displayBinder: android.os.IBinder, config: VirtualDisplayConfig): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(INTERFACE_TOKEN)
            data.writeTypedObject(config, 0)

            // Minimal IVirtualDisplayCallback stub — we don't need lifecycle callbacks,
            // just a valid binder for the transaction to accept.
            val callbackBinder = object : android.os.Binder(), android.os.IInterface {
                init { attachInterface(this, "android.hardware.display.IVirtualDisplayCallback") }
                override fun asBinder(): android.os.IBinder = this
            }
            data.writeStrongBinder(callbackBinder)
            data.writeStrongBinder(null) // IMediaProjection
            data.writeString(CALLING_PACKAGE)

            displayBinder.transact(TRANSACTION_CREATE_VIRTUAL_DISPLAY, data, reply, 0)
            reply.readException()
            return reply.readInt()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
