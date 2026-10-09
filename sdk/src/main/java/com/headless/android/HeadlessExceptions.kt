package com.headless.android

/** Base type for all SDK-surfaced failures. Internal Android exceptions are wrapped in [cause]. */
sealed class HeadlessException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ShizukuUnavailableException(
    message: String = "Shizuku binder not delivered to this process — server may run fine " +
        "while system_server refuses OUR provider bind (\"process is bad\" after force-stop/reinstall). " +
        "Fix is full uninstall+reinstall (new UID) or reboot, not restarting the server."
) : HeadlessException(message)

class PermissionDeniedException(message: String = "Privilege backend permission was denied") :
    HeadlessException(message)

class DisplayCreationException(message: String, cause: Throwable? = null) :
    HeadlessException(message, cause)

class AppLaunchException(val packageName: String, message: String, cause: Throwable? = null) :
    HeadlessException("Failed to launch '$packageName': $message", cause)

class InputInjectionException(message: String, cause: Throwable? = null) :
    HeadlessException(message, cause)

class FrameCaptureException(message: String, cause: Throwable? = null) :
    HeadlessException(message, cause)

class SessionClosedException(message: String = "Session is already closed") :
    HeadlessException(message)

class DisplayIsolationViolationException(
    val reason: String,
    val displayId: Int,
    val action: String,
    message: String = "Display isolation violation on display $displayId during '$action': $reason",
    cause: Throwable? = null
) : HeadlessException(message, cause)

class TargetNotFoundException(
    val targetDescription: String,
    message: String = "Target not found on display: $targetDescription"
) : HeadlessException(message)

class TargetStalenessException(
    val targetDescription: String,
    val ageMs: Long,
    val maxAgeMs: Long,
    message: String = "Target '$targetDescription' is stale (age=${ageMs}ms > max=${maxAgeMs}ms). Screen layout may have shifted."
) : HeadlessException(message)


/** The accessibility agent process failed to start, died, timed out, or refused a request. */
class AccessibilityAgentException(message: String, cause: Throwable? = null) :
    HeadlessException(message, cause)
