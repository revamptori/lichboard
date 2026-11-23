#!/usr/bin/env bash
set -euo pipefail
api="${1:?Specify the device API level (30 or 35)}"
# Each variant gets independent reports and screenshots on the same dedicated test emulator.
run_variant() (
  set -euo pipefail
  variant="$1"
  task_variant="${variant^}"
  package="dev.lichboard"
  if [ "$variant" = assist ]; then package="dev.lichboard.assist"; fi
  out="verification/api-$api/$variant"
  mkdir -p "$out"
  adb logcat -c
  copy_junit() {
    local suite="$1"
    local reports=()
    if [ -d app/build/outputs/androidTest-results/connected ]; then
      mapfile -t reports < <(find app/build/outputs/androidTest-results/connected -name 'TEST-*.xml')
    fi
    if [ "${#reports[@]}" -eq 1 ]; then
      cp "${reports[0]}" "$out/$suite.xml"
    else
      echo "Expected one device JUnit report; found ${#reports[@]} for $variant/$suite"
    fi
  }
  save_evidence() {
    set +e
    adb logcat -d -s AndroidRuntime:E TestRunner:I > "$out/runtime.log"
    adb shell dumpsys activity lastanr > "$out/last-anr.txt"
    adb pull "/sdcard/Download/lichboard-tests-$variant" "$out/screenshots" || true
    return 0
  }
  trap save_evidence EXIT
  classes="dev.lichboard.LichboardIntegrationTest"
  if [ "$variant" = standard ]; then classes="$classes,dev.lichboard.StandardCapabilitiesTest"; fi
  # Remove only generated results so the second invocation cannot copy an old suite.
  rm -rf app/build/outputs/androidTest-results/connected
  set +e
  ./gradlew --no-daemon ":app:connected${task_variant}DebugAndroidTest" "-Pandroid.testInstrumentationRunnerArguments.class=$classes" 2>&1 | tee "ci-emulator-$api-$variant.log"
  keys_status=${PIPESTATUS[0]}
  copy_junit keyboard-and-windows
  assist_status=0
  if [ "$variant" = assist ]; then
    # Android 11 requires a fresh instrumentation invocation for the actual AccessibilityService.
    rm -rf app/build/outputs/androidTest-results/connected
    ./gradlew --no-daemon :app:connectedAssistDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.lichboard.AccessibilityIntegrationTest 2>&1 | tee -a "ci-emulator-$api-$variant.log"
    assist_status=${PIPESTATUS[0]}
    copy_junit accessibility
  fi
  set -e
  if [ "$keys_status" -ne 0 ] || [ "$assist_status" -ne 0 ]; then exit 1; fi
  # Smoke-test the actual, non-debuggable flavor-specific release artifact.
  adb install -r "app/build/outputs/apk/$variant/release/app-$variant-release.apk"
  adb shell am force-stop "$package"
  adb shell am start -W -n "$package/dev.lichboard.MainActivity" | tee "$out/release-launch.txt"
  grep -q 'Status: ok' "$out/release-launch.txt"
  sleep 2
  adb shell pidof "$package" | tee "$out/release-pid.txt"
  adb shell screencap -p /sdcard/lichboard-release.png
  adb pull /sdcard/lichboard-release.png "$out/release.png"
  adb shell am force-stop "$package"
)
set +e
run_variant standard
standard_status=$?
run_variant assist
assist_status=$?
set -e
if [ "$standard_status" -ne 0 ] || [ "$assist_status" -ne 0 ]; then exit 1; fi
