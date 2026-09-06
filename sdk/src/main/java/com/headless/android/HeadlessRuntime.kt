package com.headless.android

import com.headless.android.privilege.PrivilegeBackend

/**
 * Runtime lifecycle owner. Holds the privilege backend and mints [HeadlessSession]s.
 * Obtain one via [HeadlessAutomation.start], not directly.
 */
class HeadlessRuntime internal constructor(private val privilegeBackend: PrivilegeBackend) {

    companion object {
        const val DEFAULT_WIDTH = 1080
        const val DEFAULT_HEIGHT = 1920
        const val DEFAULT_DENSITY_DPI = 320
    }

    @Volatile
    private var closed = false

    /** True if the privilege backend is currently authorized to create sessions. */
    fun isAuthorized(): Boolean = privilegeBackend.isAuthorized()

    /** Requests privilege authorization (e.g. the Shizuku permission prompt) if not already granted. */
    suspend fun requestAuthorization(): Boolean = privilegeBackend.requestAuthorization()

    /** Creates a new isolated headless session with its own hidden virtual display. */
    fun createSession(
        width: Int = DEFAULT_WIDTH,
        height: Int = DEFAULT_HEIGHT,
        densityDpi: Int = DEFAULT_DENSITY_DPI
    ): HeadlessSession {
        check(!closed) { "HeadlessRuntime is closed" }
        if (!privilegeBackend.isAuthorized()) {
            throw PermissionDeniedException("Privilege backend is not authorized; call requestAuthorization() first")
        }
        return HeadlessSession(privilegeBackend, width, height, densityDpi)
    }

    /** Releases the privilege backend. Existing sessions are unaffected but new ones can't be created. */
    fun close() {
        if (closed) return
        closed = true
        privilegeBackend.close()
    }
}
