# Verification evidence

`latest.json` records observed results, the tested source SHA and workflow URL, build/JVM/Lint outcomes, both device API outcomes, and separate Standard/Assist capabilities, test counts, signing notes and APK hashes. `verifiedReleasePublished: true` requires **both** editions to pass all stages. From 1.2, publication also requires the burst/latency evidence for each edition/API with zero loss/order errors, no per-backspace selected-text queries and the stated latency budget; counts alone are insufficient.

- `ci-build.log`: build/test/Lint diagnostics.
- `ci-emulator-{30,35}-{standard,assist}.log`: each edition's instrumentation output.
- `unit-tests/{standard,assist}/*.xml`: variant-specific JVM reports.
- `api-{30,35}/{standard,assist}/`: key/window/touch/capability reports, Assist-only accessibility report, runtime/ANR logs, actual release launch results and native screenshots.
- `api-{30,35}/{standard,assist}/screenshots/input-burst.json`: actual IME 520-character burst / 128-backspace results and local dispatch-call timings.
- `api-{30,35}/{standard,assist}/screenshots/input-latency.json`: 105 direct native-key activation-to-target-commit samples at an 8 ms requested cadence, dispatch/delivery p50/p95/max and explicit measurement scope. These are debug emulator timings, **not physical touch-to-display latency** or a baseline speedup comparison. The broad regression budget is p95 < 100 ms. [Methodology](../docs/INPUT-PERFORMANCE.md).
- `apks/{standard,assist}/`: actual APK signature verification, badging, merged manifest dump and machine-readable capabilities.
- `apk-capabilities.json`: aggregate actual-APK inspection. Standard must have no accessibility service declaration, implementation class or service metadata; Assist must have them. Both must be non-debuggable, omit the debug-only probe, and request no Internet permission.

Standard's installed-package tests also verify service/class absence and rejection of stale or manually supplied assist preferences. The common UI tests check edition-specific routing/controls; Assist additionally tests consent cancellation and actual focused-node editing, hints, deletion and password refusal.

On failure CI still saves diagnostics but does not publish new APKs. The complete Actions artifact is retained for 14 days.

## Not verified by these tests

**Google Play Protect acceptance is not tested or guaranteed.** The stock AOSP emulator images do not include it or the user's OEM installation policy. `latest.json` explicitly records this limitation, even when builds and device tests succeed. See [installation blocks](../docs/INSTALLATION-BLOCK.md).

Physical haptics/audio, OEM background management, actual reboot restoration, secure-screen policies, and compatibility with arbitrary third-party apps remain physical-device checks.
