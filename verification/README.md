# Verification evidence

`latest.json` records observed results, the tested source SHA and workflow URL, build/JVM/Lint outcomes, both device API outcomes, and separate Standard/Assist capabilities, test counts, signing notes and APK hashes. `verifiedReleasePublished: true` requires **both** editions to pass all stages.

- `ci-build.log`: build/test/Lint diagnostics.
- `ci-emulator-{30,35}-{standard,assist}.log`: each edition's instrumentation output.
- `unit-tests/{standard,assist}/*.xml`: variant-specific JVM reports.
- `api-{30,35}/{standard,assist}/`: key/window/capability reports, Assist-only accessibility report, runtime/ANR logs, actual release launch results and native screenshots.
- `apks/{standard,assist}/`: actual APK signature verification, badging, merged manifest dump and machine-readable capabilities.
- `apk-capabilities.json`: aggregate actual-APK inspection. Standard must have no accessibility service declaration, implementation class or service metadata; Assist must have them. Both must be non-debuggable, omit the debug-only probe, and request no Internet permission.

Standard's installed-package tests also verify service/class absence and rejection of stale or manually supplied assist preferences. The common UI tests check edition-specific routing/controls; Assist additionally tests consent cancellation and actual focused-node editing, hints, deletion and password refusal.

On failure CI still saves diagnostics but does not publish new APKs. The complete Actions artifact is retained for 14 days.

## Not verified by these tests

**Google Play Protect acceptance is not tested or guaranteed.** The stock AOSP emulator images do not include it or the user's OEM installation policy. `latest.json` explicitly records this limitation, even when builds and device tests succeed. See [installation blocks](../docs/INSTALLATION-BLOCK.md).

Physical haptics/audio, OEM background management, actual reboot restoration, secure-screen policies, and compatibility with arbitrary third-party apps remain physical-device checks.
