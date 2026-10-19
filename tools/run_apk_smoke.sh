#!/usr/bin/env bash
set -uo pipefail
mkdir -p emulator-results
adb install -r deliver/Interior-AR-Adapter.apk || exit 1
adb install -r deliver/Interior-AR-Adapter-test.apk || exit 1
adb logcat -c
adb logcat -v threadtime > emulator-results/logcat-live.txt 2>&1 &
LOG_PID=$!
trap 'kill "$LOG_PID" 2>/dev/null || true' EXIT
# A native crash must fail visibly; do not replace 3D tests by an ignored test.
timeout 240s adb shell am instrument -w -r com.maksos.interiorar.test/androidx.test.runner.AndroidJUnitRunner > emulator-results/instrumentation.txt 2>&1
STATUS=$?
printf '%s\n' "$STATUS" > emulator-results/instrumentation-exit-code.txt
timeout 15s adb pull /sdcard/Android/data/com.maksos.interiorar/files/qa emulator-results/screens 2>emulator-results/pull-screens.txt || true
timeout 10s adb exec-out screencap -p > emulator-results/screen.png || true
timeout 10s adb logcat -d > emulator-results/logcat-final.txt || true
cat emulator-results/instrumentation.txt
if [ "$STATUS" -ne 0 ]; then exit "$STATUS"; fi
grep -E 'OK \([1-9][0-9]* tests?\)' emulator-results/instrumentation.txt || exit 1
if grep -E 'FAILURES|INSTRUMENTATION_FAILED|Process crashed' emulator-results/instrumentation.txt; then exit 1; fi
