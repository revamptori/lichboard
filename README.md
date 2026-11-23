# Lichboard

**Your keyboard. On your terms.**

A real, native Android 11+ keyboard with a persistent draggable overlay button, an independent floating keyboard, and a normal Android input method. Kotlin, Android SDK, Jetpack Compose settings; no WebView, root, hidden APIs, network permission, analytics, or account.

## Install

### Choose an edition

| Edition | Download after CI verification | What it includes |
|---|---|---|
| **Standard — recommended** | **[`Lichboard-1.2.0-standard.apk`](releases/Lichboard-1.2.0-standard.apk)** | Normal IME, floating keyboard/bubble, clipboard draft, themes and controls. **No AccessibilityService declaration, implementation, or service metadata.** Package `dev.lichboard`. |
| **Assist — explicitly optional** | [`Lichboard-1.2.0-assist.apk`](releases/Lichboard-1.2.0-assist.apk) | All Standard features plus consent-based accessibility editing of supported non-password fields. Named **Lichboard Assist**, separate package `dev.lichboard.assist`. |

The APKs are only published after both editions build, pass JVM tests/Lint, pass their Android 11 / Android 15 device tests, and launch as non-debuggable releases on both emulators. [`verification/latest.json`](verification/latest.json) records the tested source, outcomes, capabilities and each APK's SHA-256. [`releases/SHA256SUMS`](releases/SHA256SUMS) provides download checksums. Failed verification never publishes new APKs.

**Play Protect:** a user reported that the earlier combined-capability APK was blocked during installation. Standard now *removes* the optional accessibility component rather than merely disabling a switch. This is capability minimization, **not** an attempt to hide capabilities or bypass security. We cannot determine Google's exact classification from the warning, and removing accessibility or changing a signing certificate does **not** guarantee acceptance. Keep Play Protect enabled. See [installation blocks](docs/INSTALLATION-BLOCK.md). Stock emulator installation tests are **not** Play Protect approval.

1. Download **Standard** unless you specifically need accessibility input assist, onto an **Android 11 (API 30) or newer** device.
2. Allow “Install unknown apps” for the file manager/browser you use to open it, then install.
3. Follow Lichboard’s setup: enable the input method, select Lichboard, allow **Display over other apps**, then start the floating button. Notification access on Android 13+ is recommended for the service controls, not needed for typing.
4. Tap the button to show/hide the floating board. Drag the button or the keyboard’s `⠿ lichboard` title to reposition it. Hold the button for quick controls. Hold a letter for accents; tap an offered alternative. Tap Shift twice quickly or hold it for caps lock. Hold Ctrl/Alt to lock a modifier; a normal tap arms it for one keystroke.

For development on a dedicated test device with ADB (not as a workaround for a Play Protect block):

```sh
adb install -r releases/Lichboard-1.2.0-standard.apk
adb shell am start -n dev.lichboard/dev.lichboard.MainActivity
# Assist uses package dev.lichboard.assist and the same fully qualified activity class.
```

Both editions can coexist, with separate settings and keyboard-picker entries. Enable the floating button in only the edition you intend to use; you normally need only Standard. An existing development build may require uninstalling before updating because CI certificates can change; uninstalling erases that edition's settings.

**Signing:** the supplied CI release is non-debuggable but signed with an Android **development/evaluation certificate**, not a production identity. It is suitable for local installation/testing, not Play distribution. A fresh runner may generate a different development certificate; uninstall the previous evaluation build if Android reports a signature mismatch (this erases settings). Keep and use your own private release keystore for stable updates or distribution. No private signing key is committed.

## What “over any app” actually means

Android separates **drawing a keyboard** from **permission to deliver input**. Lichboard can draw an independent, non-focus-stealing keyboard over ordinary apps without a text field. It cannot grant itself unrestricted text/key injection into arbitrary applications.

| Route | Actual implementation | Limits |
|---|---|---|
| Normal system IME | `InputMethodService`, `InputConnection.commitText`, Unicode-aware deletion, editor actions, Android `KeyEvent` down/up pairs | Lichboard must be enabled/selected and an input connection must be active. |
| Floating / automatic | The same key surface in a `TYPE_APPLICATION_OVERLAY` window with `FLAG_NOT_FOCUSABLE`; first tries the currently active Lichboard IME connection | Hiding the system keyboard does not create a connection. Apps can drop, reject, or ignore input. |
| Optional input assist (**Assist edition only**) | Explicitly opted-in `AccessibilityService`; `ACTION_SET_TEXT`, selection, copy/cut/paste and supported editor actions on the focused editable node | No password fields, arbitrary hardware events, background screen collection, or bypass of custom editors. It replaces selected text and may interact with an editor’s formatting/undo behavior. |
| Clipboard draft | An in-memory editor (up to 20,000 UTF-16 units), explicit **Copy draft**, then the receiving app’s Paste action | No automatic clipboard replacement or paste into arbitrary apps. Android can block a background clipboard read. Copying explicitly shares draft text with the system clipboard. |

In **Standard**, automatic mode uses the active Lichboard IME or a draft; only Automatic, IME-only and clipboard-draft routes are offered. Old preferences cannot activate accessibility. In **Assist**, automatic mode tries the active IME, then explicitly opted-in input assist, then a draft, and an assist-only route is also offered. The **system IME always uses its own active connection**, regardless of the selected floating-input route. If an editor rejects an operation, the keyboard reports that rejection; an accepted Android input call is not a guarantee that a particular game or remote desktop understands it.

**Terminals, remote desktops, games:** Esc, Tab, Ctrl, Alt, Shift, arrows, Home, End, Page Up/Down, forward Delete, Insert and F1–F12 are real Android key events through the IME. The destination must expose an input connection and implement these key codes. Input assist cannot provide raw key injection. Unsupported special keys report that an IME connection is required rather than pretending they were delivered.

**Secure/fullscreen screens:** normal fullscreen apps can show the overlay where Android permits. Secure apps can suppress it, system permission dialogs can reject overlay-covered touches, and the lockscreen hides Lichboard. Nothing attempts to evade these restrictions.

## 1.2 input improvements

- **Immediate normal input:** an active IME connection receives each ready key on the calling Main turn. No per-key Main → CPU worker → Main round trip, debounce, or 64-key channel overflow. Only genuinely slow assist/clipboard work leaves Main.
- **Less work while typing:** unchanged letter labels/modifier colors are left alone; status/preview rendering is coalesced to a frame, not rebuilt for every character. Clipboard actions update their preview without recreating the buttons. Backspace uses selection notifications rather than a blocking selected-text query per press.
- **More reliable touches:** the space between painted keys is touchable, small edge drift is tolerated using Android's touch slop, and overlapping fingers preserve press order. Unambiguous keys activate on down; keys with long-press alternatives wait for release or the next key so holding still works without inserting an unwanted base letter. Per-key scale animations are gone.
- **Ordered slow-target backlog:** only already-pending plain text is combined (up to 128 units per batch), never across edit commands, modifiers or input sessions. There is no batching delay on the normal IME path. The queue remains bounded and reports overload rather than silently accepting discarded actions.
- **Session safety:** closing/detaching drops unsent actions. Both panels check editor/route sessions before queued work; Assist also invalidates pending input on focus/window notifications without reading event text. Already-sent Android actions cannot be retracted.

[Input performance and test methodology](docs/INPUT-PERFORMANCE.md) explains the burst, multi-touch and latency checks. The fastest supported route is the **selected Lichboard IME with a focused compatible editor**; the floating board uses that same connection in Automatic mode. A draft is not direct typing, and optional accessibility replacement is not as capable or efficient as a real input connection. No phone-specific speedup or universal app compatibility is claimed.

## Features

- Persistent circular logo button: draggable, saved normalized screen position, 40–84 dp size, 30–100% opacity, tap to toggle and hold for quick settings.
- Independent draggable floating keyboard: 70–100% screen width, 240–480 dp preferred height (clamped in landscape), 55–100% opacity. IME height is also adjustable; IME opacity remains opaque for usability.
- QWERTY, number row, two symbol pages, one-shot/caps Shift, Ctrl/Alt one-shot or locked, Unicode accents on long press, repeating backspace/delete, Space and Enter.
- Navigation row, complete function/editing panel, emoji panel with three categories, explicit clipboard access, copy/cut/paste/select-all, volatile draft preview and clear action.
- Midnight (default), AMOLED Black, Dracula, Nord, Light and validated custom background/accent colors, rounded keys, immediate pressed-state colors and a short open transition.
- First-run guidance, real editor test area, live service status, geometry reset, haptic and system key-click toggles, optional boot restore.
- Auto-hide means **hide the floating button while either Lichboard keyboard is visible**. The keyboard can still be closed from its toolbar or service notification; the button then returns. It is not an idle timer.
- A low-priority foreground-service notification with keyboard, hide and stop actions. Service stops and all windows are removed when disabled. No polling/timers while idle; only key-hold timers exist during an actual press. Ready IME/draft input executes inline; input assist runs off the UI thread through an ordered, bounded, text-coalescing input queue so a slow accessibility target cannot freeze the keyboard. Detaching/closing drops pending actions; both panels validate editor/route sessions before queued work. Android calls already sent cannot be retracted. Discarded key pages do not remain in a global view registry.

## Optional accessibility setup — Assist edition only

Standard contains no accessibility service and offers no assist toggle, consent dialog or setup action. Install **Lichboard Assist** separately only if you need this capability and accept the disclosure. The implementation and metadata are compiled only from `app/src/assist/`; this is not a disabled service hidden in Standard.

It is **not necessary** for the floating button or normal IME. In **Controls → Allow input assist**, read the disclosure, explicitly agree, and enable **Lichboard input assist** in Android’s Accessibility settings. Both the OS service **and** the Lichboard opt-in must be enabled. Turn off the in-app switch to stop using assist immediately, and disable the OS service to revoke access entirely.

For sideloaded apps on Android 13+, Android may block accessibility under **Restricted settings**. If you trust the build, open Android **App info → Lichboard → ⋮ → Allow restricted settings** before enabling it. OEM wording varies. This applies only to service activation **after installation** and does not resolve a Play Protect installation block. Do not disable Play Protect or system protections to install the app.

The service does not read accessibility-event text/source nodes, record keystrokes, cache editable nodes, or read password fields. Focus/window notifications only invalidate an opaque input-session token to discard stale pending input. It fetches the focused editable node only in response to an explicit keyboard action. Clipboard contents are read only after an explicit Clipboard/Paste action, never in the background or into persistent history. In-memory drafts are not saved across process death. Android backs up neither app settings nor drafts (`allowBackup=false`).

## Background behavior

Enable **Start on boot** to restore an already-enabled button after a reboot/package update. A `specialUse` foreground service (with its declared subtype on Android 14+) owns the overlay. A boot receiver requests restoration; no alarm, job loop, hidden API or root is used.

Android/OEM battery rules and foreground-start restrictions can still delay or stop restoration. A force-stopped app cannot restart itself until launched. If needed, reopen Lichboard, check overlay access and enable its button again; consider the device’s normal per-app battery settings. Overlays are hidden while locked and the floating board is closed when the screen turns off. Secure screens are never bypassed.

## Open and build in Android Studio

Use Android Studio Ladybug or newer, JDK **17**, Android SDK platform **35** and Build Tools **35.0.0**. The repository includes the Gradle **8.9** wrapper. AGP is **8.7.3**, Kotlin/Compose compiler **2.0.21**, Compose BOM **2024.12.01**. Gradle resolves dependencies from Google, Maven Central and the Gradle Plugin Portal.

1. Open the repository root as a project; let Gradle sync.
2. Set Android Studio’s Gradle JDK to 17 and install SDK 35 when prompted.
3. Choose **standardDebug** in Build Variants by default; choose **assistDebug** explicitly for the optional edition. Run `app` on API 30+ or use:

```sh
# Set ANDROID_HOME and JAVA_HOME as usual, or let Studio write local.properties.
./gradlew :app:assembleStandardDebug :app:testStandardDebugUnitTest :app:lintStandardDebug
./gradlew :app:assembleStandardRelease
# APK: app/build/outputs/apk/standard/release/app-standard-release.apk
# Optional edition:
./gradlew :app:assembleAssistDebug :app:testAssistDebugUnitTest :app:lintAssistDebug :app:assembleAssistRelease
# APK: app/build/outputs/apk/assist/release/app-assist-release.apk
```

For a privately signed distribution build:

```sh
export LICHBOARD_KEYSTORE=/absolute/path/to/your-private-release.jks
export LICHBOARD_STORE_PASSWORD='your-keystore-password'
export LICHBOARD_KEY_ALIAS=lichboard
export LICHBOARD_KEY_PASSWORD='your-key-password'
./gradlew :app:assembleStandardRelease :app:assembleAssistRelease
```

Never commit keystores/passwords. The `*.jks`, `*.keystore`, `local.properties` and build directories are ignored. For Play distribution, review current foreground `specialUse`, accessibility, privacy-disclosure and signing policies. The project is not a claim of Play approval.

### Complete project archive

After successful CI verification and pulling its evidence commit, run `python3 scripts/package-project.py`. It checks both APK checksums and that all application/build/test scripts match the tested commit, then creates **`deliverables/Lichboard-1.2.0-AndroidStudio.zip`** and `deliverables/SHA256SUMS`. The archive contains all Kotlin sources, resources, manifests, wrapper, tests, CI scripts, README, verification reports/screenshots and both installable APKs, under a single `Lichboard/` folder. Extract it and open that folder in Android Studio. No SDK, local caches, Git history, credentials or private keys are bundled; first Gradle sync needs Internet access.

## Testing and evidence

```sh
./gradlew :app:testStandardDebugUnitTest :app:lintStandardDebug :app:testAssistDebugUnitTest :app:lintAssistDebug
./gradlew :app:assembleStandardDebug :app:assembleStandardDebugAndroidTest :app:assembleStandardRelease :app:assembleAssistDebug :app:assembleAssistDebugAndroidTest :app:assembleAssistRelease
python3 scripts/verify-apks.py  # Requires ANDROID_HOME; checks actual release manifests/DEX/signatures.
# With an unlocked dedicated API 30+ emulator/device attached; runs both editions:
bash scripts/device-test.sh 30  # Use 35 for API 35, etc.
```

The instrumentation suite sets overlay app-ops and selects the IME through the **test device’s ADB shell**, not inside the shipping app. Use a dedicated test emulator. It exercises a debug-only real `EditText`, a raw-key `InputConnection` target, and the actual overlay/IME service windows. The debug-only probe activity is not in the release APK.

Both editions run the common key/window suite. Standard additionally checks installed service/class absence and rejection of assist preferences. Only Assist compiles/runs the accessibility suite, in a **separate instrumentation invocation** from key/window tests. For accessibility, UiAutomation is used only for the test shell with its event subscription disabled; this avoids Android 11’s same-process shared accessibility-node-cache contention. Actual field editing still goes through the real AccessibilityService and Android node actions, with consent, hint-text and password checks. End-to-end normal/floating gestures use UiAutomator. Separate native MotionEvent tests exercise edge drift, cancellation, hold/repeat and split-pointer rolling; burst/latency tests activate native keys directly through the actual IME into a real EditText. Timing ends at target commitText entry, not at pixels appearing on screen.

Tests cover:

- UTF-16/code-point-safe buffer editing, selections, limits, concurrent draft edits, snapshot-safe cuts, and all route-policy combinations.
- Immediate ordered dispatch of 4,000 ready actions; 4,096 characters through a suspended backend; batching boundaries, explicit overload, failures, cancellation, reentrancy and editor-session changes.
- Actual IME 520-character bursts followed by 128 rapid backspaces with no loss/reordering or per-backspace selected-text queries; 105 activation-to-target-commit latency samples per edition/API at an 8 ms requested cadence. See the JSON reports and their explicitly limited scope.
- Real normal-IME typing, Shift, backspace, numbers and Space.
- Floating typing without stealing editor focus, cursor movement, Ctrl+A replacement.
- Automatic no-editor draft fallback, explicit clipboard draft/copy, emoji and close lifecycle.
- Repeated native page rebuilds, immediate 512-key draft input, ignored detached callbacks, and fresh input after reattachment. Separately tests cancellation of truly pending work, rather than incorrectly discarding keys that already completed inline.
- Native hit-cell continuity, slight edge drift, canceled drags, down activation, accessibility clicks, hold/repeat disposal, overlapping pointers and one-shot Shift order.
- Real editor switching without replay into the next field, Unicode code-point deletion and selected-text replacement.
- Long-press accents, F1–F12/editing-key presence, rejection of unsupported draft special keys.
- Actual raw F12 and Ctrl+F1 delivery to a receiving view.
- Bubble drag/persistence, long-press controls, themes and geometry changes.
- Compose Board/Style/Controls navigation and a real theme preference change, with native screenshots.
- Standard: actual APK manifest/DEX/resource absence of the accessibility service, runtime absence, filtered UI/routes, and normalization of old assist preferences.
- Assist: consent dialog cancellation and refusal without in-app opt-in, then text insertion/deletion/hint handling/password refusal, a 480-character queued burst and stale focus-token rejection using real focused editors.

[The CI workflow](.github/workflows/android.yml) builds, runs JVM tests and Lint, boots **Android 11 (API 30)** and **Android 15 (API 35)** emulators, runs the device tests on each, then installs and launches **both non-debuggable release APKs** on each. It verifies flavor-specific APK signing/badging/manifest/DEX and records reports, screenshots and runtime crash logs in [`verification/`](verification/). A build is only marked verified when all stages succeed; inspect `latest.json` for the current result. CI publishes evidence/APK back to **this working branch only**, with `[skip ci]` to avoid loops, and retains the complete Actions artifact for 14 days.

These stock AOSP emulators **do not include Google Play Protect**. No successful build, test, signature check, or ADB install is a guarantee of Play Protect acceptance on a phone. That remains a physical-device check. Emulators also cannot validate physical vibration/audio, OEM power management, every secure/fullscreen policy, real reboot restoration, or the behavior of arbitrary third-party games/terminal/remote-desktop apps. Test those on intended physical devices. No such universal behavior is claimed.

## Project map

```text
app/src/main/
  AndroidManifest.xml              Shared permissions, activity, IME, FGS, boot receiver (no accessibility service)
  java/dev/lichboard/
    MainActivity.kt                 Public settings/permission entry points
    LichboardApplication.kt         Process-scoped preferences and volatile draft
    ui/LichboardScreen.kt           Compose onboarding, Board, Style, Controls, test editor
    keyboard/                      Native shared keyboard, key gestures, palettes
    ime/                           InputMethodService and weak active-connection bridge
    input/                         Routing policy, input router, Unicode-aware volatile draft
    accessibility/                 Backend contract and weak bridge; no Android service implementation
    overlay/                       Foreground owner, draggable button/board, boot receiver
  res/                             Vector logos, strings, styles, service metadata
app/src/assist/                    Optional AccessibilityService/manifest/disclosure resources only
app/src/debug/                     Test-only editor/raw key activity (excluded from release)
app/src/test/                      JVM tests
app/src/androidTest/               Shared real Android service/window tests
app/src/androidTestStandard/       Standard capability-absence and preference tests
app/src/androidTestAssist/         Assist-only accessibility integration tests
scripts/                           CI device test and evidence publication scripts
releases/                          Verified Standard/Assist APKs and checksums
verification/                      Machine-readable results, reports and screenshots
```

## Troubleshooting

- **Play Protect blocks installation:** do not disable it. Try the verified Standard edition, which omits accessibility completely. Acceptance is not guaranteed; if Standard is also blocked, stop and record the Android version, install source, APK checksum and exact warning for investigation/review. [Details](docs/INSTALLATION-BLOCK.md).
- **Input feels slow:** select Lichboard as the Android keyboard and use Automatic or IME-only routing with a focused editor. Look for **IME** in the route label; Assist is a slower compatibility fallback, and **DRAFT** is memory-only. Check [1.2 input changes and test scope](docs/INPUT-PERFORMANCE.md). If a particular app still drops text, report the edition, Android/OEM version, route label and receiving app.
- **Keyboard appears but nothing reaches the app:** select Lichboard as the Android IME, focus a compatible editor and check the keyboard’s route label. A draft label means text is staying in memory. Use input assist where supported or explicit copy/paste.
- **F-keys/shortcuts do nothing:** the receiving app must implement Android key events through its active input connection. Accessibility and the clipboard cannot synthesize arbitrary hardware input.
- **Button missing while typing:** expected with Auto-hide enabled. Close the keyboard or use the notification. Secure screens and lockscreen intentionally hide overlays.
- **Button missing after force-stop/reboot:** reopen Lichboard, verify overlay access, button toggle and boot setting, and inspect the device’s battery restrictions.
- **No key clicks/haptics:** Lichboard respects system sound/haptics settings. Some devices/emulators have no physical actuator or key-click sound assets.
- **A previous APK will not update:** evaluation certificates can differ across CI runners. Uninstall the old evaluation build or rebuild both with your own stable keystore.
