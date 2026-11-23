# Lichboard 1.2 input performance

## What changed

The previous key path put every action into a 64-slot channel, dispatched it from Main to a CPU worker, then switched back to Main to discover the IME and again to send the action. Ordinary typing also rewrote all letter labels/modifier colors and the status after every character. Full channels rejected further presses. These were avoidable scheduling, allocation and layout costs; source inspection alone does not prove the cause of any particular phone's missed input.

1.2 captures an `InputAction` at activation and drains an ordered Main-confined `InputQueue`. A ready IME or memory-only draft operation executes before `offer` returns, without a debounce timer or worker hop. Slow accessibility/clipboard IPC suspends off Main. Only plain unmodified text **already waiting behind a slow operation** is joined, up to 128 UTF-16 units per batch, never across command/modifier/session boundaries. The backlog is bounded to 256 groups (plus an in-flight action); a target that cannot keep up is reported, not treated as successful input. No universal no-loss guarantee is made for an indefinitely blocked target.

Rendering is separate from dispatch: unchanged modifier/letter state is not rewritten, repeated identical status is not assigned, and feedback/preview updates do not rebuild clipboard buttons. Status work is coalesced to the next animation frame; input is **not** delayed until that frame. Clipboard preview reads leave Main. Repeating deletion uses the IME's selection notifications/initial state rather than synchronous `getSelectedText` calls. Unknown selection and raw editors retain Android key-event handling. Raw/modified text reuses a character map.

Painted gaps now lie inside key hit cells. Android touch slop allows a slight release drift beyond the edge. Unambiguous keys activate on down; keys offering a long press stay pending until release or the next key's down. Flushing that pending tap before the next key preserves down-order when fingers overlap, including one-shot modifiers. Long presses still offer accents or modifier locks without first inserting a base letter. Repeat/hold timers end on release, cancel, rebuild or detach; keys no longer run scale animations.

Editor/route generations prevent unsent work or stale result messages being carried into a new session. Detach/hide drops pending actions. Assist observes only the **type** of focus/window notifications to invalidate an opaque token, not their text/source nodes; actual node discovery remains explicit-action-only and off Main, once per action/batch. It caches no editable node, excludes passwords, and is absent from Standard. Android operations already dispatched cannot be retracted, and an accepted InputConnection call is not proof that an arbitrary receiving app implemented it.

## Regression checks

Both editions use the same input surface and normal-IME implementation. CI runs the following on Android 11/API 30 and Android 15/API 35:

- **JVM queue tests:** 4,000 ready actions must execute in order before each offer returns. A suspended backend must accept 4,096 characters without loss and with much fewer executions, while edits/modifiers remain ordered. Other tests cover bounded overload, exceptions, scope disposal, cancellation, reentrancy and editor changes. These are deterministic dispatch tests, not device benchmarks.
- **Real IME burst:** activate 520 native character keys during one Main callback, then wait for exact text in a real `EditText`. Activate 128 backspaces rapidly and verify the exact remainder. The debug target counts selected-text queries and code-point deletions. This catches the old queue-overflow/thread-hop behavior; it is deliberately synthetic, not a human typing-rate claim.
- **Real IME timing:** after warm-up, activate 105 native keys, requesting an 8 ms pause between activations on the instrumentation thread. Record `elapsedRealtimeNanos` immediately before `performClick`, after it returns, and when the real target InputConnection enters `commitText`. Require exact values/order with no missing samples, and record p50/p95/max for dispatch-call duration and activation-to-target-commit duration. A generous **p95 < 100 ms** budget catches gross regressions; it is not a promised latency.
- **Native touch tests:** dispatch actual MotionEvents through key views and the split-pointer panel hierarchy. Check contiguous key cells, edge drift, moving-away/cancel, immediate down activation without duplicate up/click, long-press behavior, timer disposal, repeat stopping, reverse-release rolling and one-shot Shift. Existing UiAutomator tests also tap the real IME and overlay windows and long-press alternatives.
- **Lifecycle/editing:** 512 immediate draft activations survive a later detach; detached callbacks are ignored and reattachment accepts fresh input. Separate suspended-queue tests ensure genuinely pending input is discarded. Real editor switching checks that old input is not replayed in the next field. Real selection replacement, supplementary-code-point deletion, Ctrl shortcuts and raw F-keys remain covered.
- **Assist only:** consent/password/privacy checks, real text insertion/deletion, a 480-character queued burst into a supported editor, and rejection of a stale focus token. This stays in a separate, quiet-accessibility instrumentation invocation to avoid Android 11 UiAutomation/node-cache contention.

The debug-only `ProbeActivity`/recording target is excluded from release APKs. Test recording is temporary in-process state, not a shipping input logger. Reports contain counts/timing and no private user text.

## Where to find observed results

`verification/latest.json` identifies the exact tested source commit, Actions URL, edition/API outcomes, APK hashes and `inputPerformance` data. Per-device reports are:

- `verification/api-{30,35}/{standard,assist}/screenshots/input-burst.json`
- `verification/api-{30,35}/{standard,assist}/screenshots/input-latency.json`
- `verification/api-{30,35}/{standard,assist}/keyboard-and-windows.xml`
- `verification/api-{30,35}/assist/accessibility.xml`
- `verification/unit-tests/{standard,assist}/*.xml`

Publication requires the new evidence for **all four edition/API combinations**, not merely a successful build. Failed verification leaves the previously published APKs in place. Always inspect the report's version/outcomes rather than assuming the source version has already been verified.

## Measurement limits and phone testing

The latency measurement is **direct key activation → receiving InputConnection entry on a debug AOSP emulator**. The debug probe and IME run in the instrumented app's process, so it does not establish cross-process delivery performance in a third-party app. It is not touch-sensor latency, animation/render time, touch-to-pixel latency, an on-phone measurement, or a quantified improvement over a measured 1.1 baseline. Runner load, JIT, debug instrumentation and emulation affect it. Burst tests intentionally keep Main busy and should not be interpreted as real-world typing latency. No "times faster" claim is made.

For the fastest supported route, select **Lichboard as the current Android keyboard**, focus a compatible editor, and use Automatic or IME-only routing. The floating panel uses the same fast InputConnection when its label says **IME**. A **DRAFT** label means memory-only text; it does not send to an app. Optional Assist is compatibility editing via accessibility IPC/full-text replacement, not unrestricted injection or an equally fast substitute for an IME.

Physical-device validation remains necessary for the reported phone issue. Check the actual edition/version, Android/OEM version, route label and receiving app, then try rapid ordinary typing, overlapping taps, backspace/selection, punctuation/emoji, and switching fields/apps. Physical haptics/audio, OEM power rules, arbitrary custom editors and Google Play Protect acceptance are outside these emulator measurements. Keep device protections enabled.
