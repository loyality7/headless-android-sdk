package com.headless.android

/** Base type for all SDK-surfaced failures. Internal Android exceptions are wrapped in [cause]. */
sealed class HeadlessException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ShizukuUnavailableException(message: String = "Shizuku is not running or not reachable") :
    HeadlessException(message)

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
