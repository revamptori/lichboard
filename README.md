# Lichboard

**Your keyboard. On your terms.**

A real, native Android 11+ keyboard with a persistent draggable overlay button, an independent floating keyboard, and a normal Android input method. Kotlin, Android SDK, Jetpack Compose settings; no WebView, root, hidden APIs, network permission, analytics, or account.

## Install

The verified, installable APK is published at **[`releases/Lichboard-1.0.0.apk`](releases/Lichboard-1.0.0.apk)** after the build, JVM tests, Lint, and Android 11 / Android 15 device tests pass. [`verification/latest.json`](verification/latest.json) records the tested source commit, run URL, checks, and APK SHA-256. If verification fails, CI records diagnostics and does **not** publish a new APK.

1. Download the APK onto an **Android 11 (API 30) or newer** device.
2. Allow “Install unknown apps” for the file manager/browser you use to open it, then install.
3. Follow Lichboard’s setup: enable the input method, select Lichboard, allow **Display over other apps**, then start the floating button. Notification access on Android 13+ is recommended for the service controls, not needed for typing.
4. Tap the button to show/hide the floating board. Drag the button or the keyboard’s `⠿ lichboard` title to reposition it. Hold the button for quick controls. Hold a letter for accents; tap an offered alternative. Tap Shift twice quickly or hold it for caps lock. Hold Ctrl/Alt to lock a modifier; a normal tap arms it for one keystroke.

With ADB:

```sh
adb install -r releases/Lichboard-1.0.0.apk
adb shell am start -n dev.lichboard/.MainActivity
```

**Signing:** the supplied CI release is non-debuggable but signed with an Android **development/evaluation certificate**, not a production identity. It is suitable for local installation/testing, not Play distribution. A fresh runner may generate a different development certificate; uninstall the previous evaluation build if Android reports a signature mismatch (this erases settings). Keep and use your own private release keystore for stable updates or distribution. No private signing key is committed.

## What “over any app” actually means

Android separates **drawing a keyboard** from **permission to deliver input**. Lichboard can draw an independent, non-focus-stealing keyboard over ordinary apps without a text field. It cannot grant itself unrestricted text/key injection into arbitrary applications.

| Route | Actual implementation | Limits |
|---|---|---|
| Normal system IME | `InputMethodService`, `InputConnection.commitText`, Unicode-aware deletion, editor actions, Android `KeyEvent` down/up pairs | Lichboard must be enabled/selected and an input connection must be active. |
| Floating / automatic | The same key surface in a `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_FOCUSABLE`; first tries the currently active Lichboard IME connection | Hiding the system keyboard does not create a connection. Apps can drop, reject, or ignore input. |
| Optional input assist | Explicitly opted-in `AccessibilityService`; `ACTION_SET_TEXT`, selection, copy/cut/paste and supported editor actions on the focused editable node | No password fields, arbitrary hardware events, background screen collection, or bypass of custom editors. It replaces selected text and may interact with an editor’s formatting/undo behavior. |
| Clipboard draft | An in-memory editor (up to 20,000 UTF-16 units), explicit **Copy draft**, then the receiving app’s Paste action | No automatic clipboard replacement or paste into arbitrary apps. Android can block a background clipboard read. Copying explicitly shares draft text with the system clipboard. |

Automatic mode prefers IME, then opt-in input assist, then a draft. IME-only, input-assist-only, and clipboard-draft modes are selectable. The **system IME always uses its own active connection**, regardless of the selected floating-input route. If an editor rejects an operation, the keyboard reports that rejection; an accepted Android input call is not a guarantee that a particular game or remote desktop understands it.

**Terminals, remote desktops, games:** Esc, Tab, Ctrl, Alt, Shift, arrows, Home, End, Page Up/Down, forward Delete, Insert and F1–F12 are real Android key events through the IME. The destination must expose an input connection and implement these key codes. Input assist cannot provide raw key injection. Unsupported special keys report that an IME connection is required rather than pretending they were delivered.

**Secure/fullscreen screens:** normal fullscreen apps can show the overlay where Android permits. Secure apps can suppress it, system permission dialogs can reject overlay-covered touches, and the lockscreen hides Lichboard. Nothing attempts to evade these restrictions.

## Features

- Persistent circular logo button: draggable, saved normalized screen position, 40–84 dp size, 30–100% opacity, tap to toggle and hold for quick settings.
- Independent draggable floating keyboard: 70–100% screen width, 240–480 dp preferred height (clamped in landscape), 55–100% opacity. IME height is also adjustable; IME opacity remains opaque for usability.
- QWERTY, number row, two symbol pages, one-shot/caps Shift, Ctrl/Alt one-shot or locked, Unicode accents on long press, repeating backspace/delete, Space and Enter.
- Navigation row, complete function/editing panel, emoji panel with three categories, explicit clipboard access, copy/cut/paste/select-all, volatile draft preview and clear action.
- Midnight (default), AMOLED Black, Dracula, Nord, Light and validated custom background/accent colors, rounded keys, short press/open transitions.
- First-run guidance, real editor test area, live service status, geometry reset, haptic and system key-click toggles, optional boot restore.
- Auto-hide means **hide the floating button while either Lichboard keyboard is visible**. The keyboard can still be closed from its toolbar or service notification; the button then returns. It is not an idle timer.
- A low-priority foreground-service notification with keyboard, hide and stop actions. Service stops and all windows are removed when disabled. No polling/timers while idle; only key-hold timers exist during an actual press.

## Optional accessibility setup

It is **not necessary** for the floating button or normal IME. In **Controls → Allow input assist**, read the disclosure, explicitly agree, and enable **Lichboard input assist** in Android’s Accessibility settings. Both the OS service **and** the Lichboard opt-in must be enabled. Turn off the in-app switch to stop using assist immediately, and disable the OS service to revoke access entirely.

For sideloaded apps on Android 13+, Android may block accessibility under **Restricted settings**. If you trust the build, open Android **App info → Lichboard → ⋮ → Allow restricted settings** before enabling it. OEM wording varies. Do not disable system protections globally.

The service does not inspect accessibility-event contents, record keystrokes, cache editable nodes, or read password fields. It only fetches the focused editable node in response to an explicit keyboard action. Clipboard contents are read only after an explicit Clipboard/Paste action, never in the background or into persistent history. In-memory drafts are not saved across process death. Android backs up neither app settings nor drafts (`allowBackup=false`).

## Background behavior

Enable **Start on boot** to restore an already-enabled button after a reboot/package update. A `specialUse` foreground service (with its declared subtype on Android 14+) owns the overlay. A boot receiver requests restoration; no alarm, job loop, hidden API or root is used.

Android/OEM battery rules and foreground-start restrictions can still delay or stop restoration. A force-stopped app cannot restart itself until launched. If needed, reopen Lichboard, check overlay access and enable its button again; consider the device’s normal per-app battery settings. Overlays are hidden while locked and the floating board is closed when the screen turns off. Secure screens are never bypassed.

## Open and build in Android Studio

Use Android Studio Ladybug or newer, JDK **17**, Android SDK platform **35** and Build Tools **35.0.0**. The repository includes the Gradle **8.9** wrapper. AGP is **8.7.3**, Kotlin/Compose compiler **2.0.21**, Compose BOM **2024.12.01**. Gradle resolves dependencies from Google, Maven Central and the Gradle Plugin Portal.

1. Open the repository root as a project; let Gradle sync.
2. Set Android Studio’s Gradle JDK to 17 and install SDK 35 when prompted.
3. Run the `app` configuration on API 30+ or use:

```sh
# Set ANDROID_HOME and JAVA_HOME as usual, or let Studio write local.properties.
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:assembleRelease
# APK: app/build/outputs/apk/release/app-release.apk
```

For a privately signed distribution build:

```sh
export LICHBOARD_KEYSTORE=/absolute/path/to/your-private-release.jks
export LICHBOARD_STORE_PASSWORD='your-keystore-password'
export LICHBOARD_KEY_ALIAS=lichboard
export LICHBOARD_KEY_PASSWORD='your-key-password'
./gradlew :app:assembleRelease
```

Never commit keystores/passwords. The `*.jks`, `*.keystore`, `local.properties` and build directories are ignored. For Play distribution, review current foreground `specialUse`, accessibility, privacy-disclosure and signing policies. The project is not a claim of Play approval.

## Testing and evidence

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug
# With an unlocked API 30+ emulator/device attached:
./gradlew :app:connectedDebugAndroidTest
```

The instrumentation suite sets overlay app-ops and selects the IME through the **test device’s ADB shell**, not inside the shipping app. Use a dedicated test emulator. It exercises a debug-only real `EditText`, a raw-key `InputConnection` target, and the actual overlay/IME service windows. The debug-only probe activity is not in the release APK.

Tests cover:

- UTF-16/code-point-safe buffer editing, selections, limits, and all route-policy combinations.
- Real normal-IME typing, Shift, backspace, numbers and Space.
- Floating typing without stealing editor focus, cursor movement, Ctrl+A replacement.
- No-editor clipboard draft, explicit copying, emoji and close lifecycle.
- Long-press accents, F1–F12/editing-key presence, rejection of unsupported draft special keys.
- Actual raw F12 and Ctrl+F1 delivery to a receiving view.
- Bubble drag/persistence, long-press controls, themes and geometry changes.
- Opt-in accessibility text insertion and deletion using a real focused editor.

[The CI workflow](.github/workflows/android.yml) builds, runs JVM tests and Lint, boots **Android 11 (API 30)** and **Android 15 (API 35)** emulators, runs the device tests on each, then installs and launches the **non-debuggable release APK** on each. It verifies APK signing/badging and records reports, screenshots and runtime crash logs in [`verification/`](verification/). A build is only marked verified when all stages succeed; inspect `latest.json` for the current result. CI publishes evidence/APK back to **this working branch only**, with `[skip ci]` to avoid loops, and retains the complete Actions artifact for 14 days.

Emulators cannot validate physical vibration/audio, OEM power management, every secure/fullscreen policy, real reboot restoration, or the behavior of arbitrary third-party games/terminal/remote-desktop apps. Test those on intended physical devices. No such universal behavior is claimed.

## Project map

```text
app/src/main/
  AndroidManifest.xml              Permissions, activity, IME, FGS, accessibility, boot receiver
  java/dev/lichboard/
    MainActivity.kt                 Public settings/permission entry points
    LichboardApplication.kt         Process-scoped preferences and volatile draft
    ui/LichboardScreen.kt           Compose onboarding, Board, Style, Controls, test editor
    keyboard/                      Native shared keyboard, key gestures, palettes
    ime/                           InputMethodService and weak active-connection bridge
    input/                         Routing policy, input router, Unicode-aware volatile draft
    accessibility/                 Explicit focused-node editing and weak service bridge
    overlay/                       Foreground owner, draggable button/board, boot receiver
  res/                             Vector logos, strings, styles, service metadata
app/src/debug/                     Test-only editor/raw key activity (excluded from release)
app/src/test/                      JVM tests
app/src/androidTest/               Real Android service/window integration tests
scripts/                           CI device test and evidence publication scripts
releases/                          Verified installable APK
verification/                      Machine-readable results, reports and screenshots
```

## Troubleshooting

- **Keyboard appears but nothing reaches the app:** select Lichboard as the Android IME, focus a compatible editor and check the keyboard’s route label. A draft label means text is staying in memory. Use input assist where supported or explicit copy/paste.
- **F-keys/shortcuts do nothing:** the receiving app must implement Android key events through its active input connection. Accessibility and the clipboard cannot synthesize arbitrary hardware input.
- **Button missing while typing:** expected with Auto-hide enabled. Close the keyboard or use the notification. Secure screens and lockscreen intentionally hide overlays.
- **Button missing after force-stop/reboot:** reopen Lichboard, verify overlay access, button toggle and boot setting, and inspect the device’s battery restrictions.
- **No key clicks/haptics:** Lichboard respects system sound/haptics settings. Some devices/emulators have no physical actuator or key-click sound assets.
- **A previous APK will not update:** evaluation certificates can differ across CI runners. Uninstall the old evaluation build or rebuild both with your own stable keystore.
