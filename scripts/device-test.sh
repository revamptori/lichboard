#!/usr/bin/env bash
set -euo pipefail
api="$1"
mkdir -p "verification/api-$api"
adb logcat -c
copy_junit() {
  local suite="$1"
  if [ -d app/build/outputs/androidTest-results/connected ]; then
    find app/build/outputs/androidTest-results/connected -name 'TEST-*.xml' -exec cp {} "verification/api-$api/$suite.xml" \;
  fi
}
save_evidence() {
  set +e
  adb logcat -d -s AndroidRuntime:E TestRunner:I > "verification/api-$api/runtime.log"
  adb shell dumpsys activity lastanr > "verification/api-$api/last-anr.txt"
  adb pull /sdcard/Download/lichboard-tests "verification/api-$api/screenshots" || true
  return 0
}
trap save_evidence EXIT
# Separate instrumentation invocations prevent Android 11's UiAutomation node cache
# from competing with the actual AccessibilityService in the same instrumented process.
set +e
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.lichboard.LichboardIntegrationTest 2>&1 | tee "ci-emulator-$api.log"
keys_status=${PIPESTATUS[0]}
copy_junit keyboard-and-windows
./gradlew --no-daemon :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.lichboard.AccessibilityIntegrationTest 2>&1 | tee -a "ci-emulator-$api.log"
assist_status=${PIPESTATUS[0]}
copy_junit accessibility
set -e
if [ "$keys_status" -ne 0 ] || [ "$assist_status" -ne 0 ]; then exit 1; fi
# Test the actual non-debuggable release artifact, not only the instrumentation build.
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop dev.lichboard
adb shell am start -W -n dev.lichboard/.MainActivity | tee "verification/api-$api/release-launch.txt"
sleep 2
adb shell pidof dev.lichboard | tee "verification/api-$api/release-pid.txt"
adb shell screencap -p /sdcard/lichboard-release.png
adb pull /sdcard/lichboard-release.png "verification/api-$api/release.png"
