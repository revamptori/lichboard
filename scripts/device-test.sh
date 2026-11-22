#!/usr/bin/env bash
set -euo pipefail
api="$1"
mkdir -p "verification/api-$api"
adb logcat -c
save_evidence() {
  # Preserve failure stack traces as well as successful results.
  set +e
  adb logcat -d -s AndroidRuntime:E TestRunner:I > "verification/api-$api/runtime.log"
  if [ -d app/build/outputs/androidTest-results/connected ]; then
    find app/build/outputs/androidTest-results/connected -name 'TEST-*.xml' -exec cp {} "verification/api-$api/" \;
    # Full UTP/protobuf/device diagnostics remain in the downloadable Actions artifact.
  fi
  adb shell dumpsys activity lastanr > "verification/api-$api/last-anr.txt"
  adb pull /sdcard/Download/lichboard-tests "verification/api-$api/screenshots" || true
  return 0
}
trap save_evidence EXIT
./gradlew --no-daemon :app:connectedDebugAndroidTest 2>&1 | tee "ci-emulator-$api.log"
# Test the actual non-debuggable release artifact, not only the instrumentation build.
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell am force-stop dev.lichboard
adb shell am start -W -n dev.lichboard/.MainActivity | tee "verification/api-$api/release-launch.txt"
sleep 2
adb shell pidof dev.lichboard | tee "verification/api-$api/release-pid.txt"
adb shell screencap -p /sdcard/lichboard-release.png
adb pull /sdcard/lichboard-release.png "verification/api-$api/release.png"
