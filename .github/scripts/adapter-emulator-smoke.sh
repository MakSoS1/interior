#!/usr/bin/env bash
set -u
mkdir -p evidence
timeout 90s adb wait-for-device
timeout 90s adb install -r apk/debug/app-debug.apk || exit 1
timeout 60s adb install -r apk/androidTest/debug/app-debug-androidTest.apk || exit 1
adb logcat -c || :
# Capture logcat continuously, including the moments before a possible native crash or ADB loss.
adb logcat -v threadtime > evidence/logcat-live.txt 2>&1 &
logger_pid=$!
sleep 3
set +e
timeout 150s adb shell am instrument -w -r -e class com.maksos.interiorar.AndroidSmokeTest \
  com.maksos.interiorar.test/androidx.test.runner.AndroidJUnitRunner \
  > evidence/instrumentation.txt 2>&1
result=$?
set -e
echo "$result" > evidence/instrument-exitcode.txt
timeout 20s adb logcat -d > evidence/logcat-after.txt 2>&1 || :
timeout 15s adb shell dumpsys activity processes > evidence/processes.txt 2>&1 || :
timeout 15s adb shell dumpsys activity activities > evidence/activities.txt 2>&1 || :
timeout 15s adb pull /sdcard/Android/data/com.maksos.interiorar/files/qa evidence/qa > evidence/pull.log 2>&1 || :
kill "$logger_pid" 2>/dev/null || :
kill -9 "$logger_pid" 2>/dev/null || :
cat evidence/instrumentation.txt
if [ "$result" -ne 0 ] || ! grep -Eq 'OK \(2 tests\)' evidence/instrumentation.txt; then
    echo "=== Emulator native/Java diagnostics ==="
    grep -Ei -m 65 'FATAL EXCEPTION|Fatal signal|SIGSEGV|AndroidRuntime|sceneview|filament|OutOfMemory|TestRunner|ANR|tombstoned|crash' evidence/logcat-live.txt | tail -65 || :
    exit 1
fi
