#!/bin/bash
set -euo pipefail

APP_APK=$(find Common/build/outputs/apk/mobileLibre3SiDexNogoogle/debug -name "*.apk" ! -name "*-unsigned*" | head -1)
TEST_APK=$(find Common/build/outputs/apk/androidTest/mobileLibre3SiDexNogoogle/debug -name "*.apk" | head -1)

echo "App APK:  $APP_APK"
echo "Test APK: $TEST_APK"

adb shell dumpsys batterystats --reset || true

adb logcat -c
adb install -r -t "$APP_APK"

if [[ -n "$TEST_APK" ]]; then
  adb install -r -t "$TEST_APK"
  adb shell am instrument -w -e size small \
    "cloud.glucodroid.debug.test/androidx.test.runner.AndroidJUnitRunner" \
    2>&1 | tee instrumentation.log || true
fi

# Launch app and let it run for 30 seconds to surface any startup races
adb shell monkey -p cloud.glucodroid.debug -c android.intent.category.LAUNCHER 1 || true
sleep 30

adb logcat -d > logcat-full.log
adb shell dumpsys batterystats > batterystats.txt || true
