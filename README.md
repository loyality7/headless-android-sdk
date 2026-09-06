# Headless Android SDK

A Kotlin SDK for creating and controlling an isolated, hidden secondary Android display —
launch an app onto it, inject touch/text/key input, capture screenshots — while the user's
physical/default display stays untouched and usable.

```kotlin
val runtime = HeadlessAutomation.start(context)
if (!runtime.isAuthorized()) runtime.requestAuthorization()

val session = runtime.createSession()
session.launch("com.android.chrome")
session.tap(540f, 270f)
session.type("RRR movie")
session.pressEnter()
val screenshot = session.screenshot()
session.close()
```

## Modules

- `sdk/` — the SDK itself. Public API surface: `HeadlessAutomation`, `HeadlessRuntime`,
  `HeadlessSession`, plus internal `display/`, `apps/`, `input/`, `capture/`, `perception/`,
  and `privilege/` packages. Consumers never touch Binder, `ImageReader`, `InputManager`,
  or Shizuku directly.
- `example/` — a minimal Android app demonstrating SDK usage end to end (Shizuku
  authorization, session creation, launching Chrome on the hidden display, input, and
  screenshot capture).

## Requirements

- Android 14 (API 34) device with [Shizuku](https://shizuku.rikka.app/) installed and running
  (the current `PrivilegeBackend` implementation). The SDK's privilege layer is abstracted
  behind an interface so other backends can be added later.
- Verified on a Xiaomi device (HyperOS / Android 14, API 34).

## Status

Core runtime is deterministic and device-verified: virtual display creation, app launch onto
that display, touch/text/key input injection, and frame capture all work end to end through
the public API. The perception layer (`perception/ScreenAnalyzer`) is an interface only —
no OCR/vision/Accessibility implementation ships yet; the runtime works on raw screenshots.

## Build

```
./gradlew :sdk:assembleDebug
./gradlew :example:assembleDebug
```
