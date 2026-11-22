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
    cp -r app/build/outputs/androidTest-results/connected/. "verification/api-$api/"
  fi
  adb pull /sdcard/Android/data/dev.lichboard/files/screenshots "verification/api-$api/" >/dev/null 2>&1
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
