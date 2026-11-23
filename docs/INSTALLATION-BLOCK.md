# Play Protect installation blocks

A physical-device user reported **“App blocked to protect your device”** while installing the original combined Lichboard APK from the web. The dialog said the app could request access to sensitive data. It did not identify the exact classifier or establish which component caused the block.

## What changed in 1.1.0

- **Standard (recommended)**, package `dev.lichboard`, contains the normal IME, draggable overlay button/keyboard, clipboard draft, themes and settings. It has **no AccessibilityService declaration, service implementation class, or accessibility-service metadata resource**. Its UI offers no accessibility opt-in or routing mode, and old preferences cannot activate one.
- **Assist (explicitly optional)**, package `dev.lichboard.assist`, is separately named **Lichboard Assist**. Its additional capability is clearly disclosed. It includes the accessibility service only in its own flavor-specific manifest, Kotlin source set and resources. OS permission plus informed in-app opt-in are both required.
- Both editions use the same public input APIs as before. Neither injects arbitrary input, bypasses secure screens, monitors accessibility-event contents, or requests Internet/SMS/notification-reading access.

This is actual feature/capability separation, not renaming the sensitive service, hiding it, dynamically downloading it, or attempting to evade a scanner. The default APK genuinely cannot provide accessibility input assistance. The Assist app is not a workaround for a blocked Standard install and may itself be blocked.

## What to install

Start with the verified **`releases/Lichboard-1.2.0-standard.apk`**. Confirm its checksum against `releases/SHA256SUMS` or `verification/latest.json`. Keep Play Protect enabled. After a permitted installation, enable/select Lichboard as the Android keyboard, then grant display-over-other-apps access if you want the floating feature.

If Play Protect still blocks Standard, stop rather than disabling the protection. Record:

- Android version and phone/OEM version;
- exact warning text or screenshot;
- which APK edition/version, its SHA-256, and where it was downloaded;
- whether the warning is an **installation block** or an accessibility **Restricted settings** message after installation.

A suspected false positive can be submitted through Google's official Play Protect review/appeal process. No particular review outcome is promised. A privately signed distribution build and a reviewed distribution channel may be appropriate, but changing a certificate alone is not a guarantee of acceptance.

## Distinct protections

- **Install unknown apps:** ordinary per-source sideload permission. Granting it does not override Play Protect.
- **Play Protect installation block:** occurs before the app can run. An in-app switch or Android accessibility “Allow restricted settings” option cannot resolve this block.
- **Restricted settings:** can prevent enabling an already installed sideloaded app's accessibility service. Relevant only to the optional Assist edition after installation; it is not an installer fix.
- **IME/overlay consent:** the normal keyboard and display-over-other-apps approvals still apply to both editions. They do not grant unrestricted input injection.

## Verification boundaries

CI builds/tests both editions on stock AOSP Android 11 and 15 emulators, validates signing, inspects the actual APK manifests and DEX payloads, checks installed Standard service/class absence and preference restrictions, and launches both non-debuggable release APKs. Those images **do not include Google Play Protect** and are not proof of acceptance by Google or an OEM installer.

`verification/latest.json` explicitly records Play Protect acceptance as **not verified**. Do not interpret a successful Gradle build, Android signature verification, emulator install, or ADB install as security-product approval. Development ADB commands are not offered as a way around the user's protection warning.

The supplied CI APKs remain development-certificate evaluation builds, not Play-approved production releases. Private stable signing keys must never be committed or sent in chat.
