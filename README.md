# Headless Android SDK

Automate other Android apps on a **hidden virtual display** while the user keeps using their
phone. The SDK launches an app on that display, reads its screen as a list of elements, and
clicks, types and scrolls through the accessibility system, with no keyboard and no
coordinate guessing. Every guard is built around one rule: **display 0, the user's real
screen, is never touched.**

```kotlin
// inside a coroutine
val runtime = HeadlessAutomation.start(context)
if (!runtime.isAuthorized()) runtime.requestAuthorization()

val session = runtime.createSession()           // a hidden display
session.launch("com.android.chrome")            // placement on that display is verified

val screen = session.compactUi()!!               // numbered, token-efficient element list
println(screen.toPromptText())                   // [5] url_bar (EditText) "google.com" [x=480, y=60]

val field = screen.elements.first { it.editable }
val typed = session.setTextElement(screen, field.index, "hello")   // no keyboard
check(typed.verified)                            // the field was read back holding "hello"

session.pressEnter()
val shot = session.screenshot()
session.close()
runtime.close()
```

## How it works

```
your app ──► SDK ──► Shizuku (shell identity) ──► hidden virtual display ──► target app
                 └─► accessibility agent (shell app_process, UiAutomation)
                       reads the element tree and performs node actions on ONE display
```

- **Hidden display:** a trusted virtual display created through the display service, owned
  by your app process.
- **Accessibility agent:** a small shell-identity process the SDK starts through Shizuku. It
  needs no accessibility toggle in Settings. It exposes a display's element tree and actions
  (click, set text, scroll, copy and paste, ...). It refuses display 0.
- **Input:** touch and key events are injected with the hidden display's id; text is set
  through the accessibility `SET_TEXT` action instead of a keyboard.

## Acting on the screen

`compactUi()` returns a snapshot whose element numbers are valid for that snapshot only. Act
on it with `clickElement`, `setTextElement`, `scrollElement`, or `act(ref, NodeAction)`.

Results are honest. `ActionOutcome.performed` means the app accepted the action;
`ActionOutcome.verified` means the effect was read back (for `setTextElement`, the field holds
exactly the text). A node that moved since the snapshot throws `AccessibilityAgentException`
("stale: ...") instead of acting on whatever is now at that spot. Confirming a higher-level
result (message sent, page loaded) is still the caller's job: read the screen again.

## Display 0 safety

- Launch, input, and the accessibility agent all refuse display 0 and displays that no longer
  exist, before anything runs.
- If an app lands on display 0 because of a launch, it is removed again.
- Closing a session removes only the session's own task; it never force-stops the user's own
  copy of the app.
- A detached watchdog removes the session's tasks within about a second if the app process
  dies (Android would otherwise drop them onto display 0).
- Hidden displays get no keyboard (IME policy HIDE), so focusing a field there cannot pop a
  keyboard on the real screen. A keyboard the user opens in their own app is ignored; only a
  keyboard serving the hidden display's window counts as a leak.

## Requirements

- Android 11+ (`minSdk 30`) and [Shizuku](https://shizuku.rikka.app/) installed and running.
  Verified on Android 14 only.
- In the host app's manifest: `<uses-permission android:name="moe.shizuku.manager.permission.API_V23" />`.
  Shizuku permission belongs to the app, so if the host app already has it, the SDK needs
  nothing more.
- Not published yet. Include the module: `implementation project(':sdk')`.

## Verification status

Verified on a Redmi/POCO phone (Android 14, MIUI/HyperOS), through the public API:

- Hidden display create and close, with no leftover display and nothing moved to display 0.
- Launching Chrome, Settings, Calculator and Notes on the hidden display, placement checked.
- Reading the element tree, accessibility clicks, scrolling, and read-back verified text entry.
- An LLM agent using only these calls completed Settings, Calculator and Wikipedia-in-Chrome
  tasks, with no false success claims and display 0 untouched.

Covered by unit tests but not proven on a device: the death watchdog, keyboard ownership
rules, and the refusal paths.

## Known limitations

- Typing sometimes needs a retry (the SDK refuses to report it as done until it reads back),
  and Enter occasionally does not navigate on the first press.
- Only one `UiAutomation` connection can exist at a time. Another tool that holds it
  (Tasker, Appium, `uiautomator`) will clash with the agent.
- MIUI can refuse to give a freshly installed or repeatedly killed app its Shizuku binder
  ("process is bad"). Opening the app normally or waiting clears it.
- The Shizuku UserService route (`AgentUserService`) does not start on the test device, so
  the SDK does not depend on it.
- Screen-off is not verified with the accessibility agent. Earlier testing found the hidden
  display stops producing frames while the phone's screen is off (screenshots fail; placement
  and input still worked). Whether the agent can still read the element tree then has not been
  tested, and a long run may be slowed or killed by battery limits.
- Screenshots under the "no keyboard" display setting have not been re-verified.
- If the app process dies, the app is briefly moved to display 0 before the watchdog removes it.
- Other devices and OEMs are untested.

## Modules and build

- `sdk/`: the library. Entry points: `HeadlessAutomation`, `HeadlessRuntime`, `HeadlessSession`.
- `example/`: a small app plus `ControlService`, an adb-driven control channel for testing.

```
./gradlew :sdk:testDebugUnitTest
./gradlew :sdk:assembleDebug :example:assembleDebug
```
