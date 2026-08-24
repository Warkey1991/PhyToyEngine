#!/bin/sh
set -eu

ADB_BIN=${ADB_BIN:-adb}
REPORT_DIR=${REPORT_DIR:-reports/android_camera2_product_alpha}
SKIP_BUILD=${SKIP_BUILD:-0}
PACKAGE=com.phytoy.sample
ACTIVITY="$PACKAGE/.MainActivity"
APK=android/sample/build/outputs/apk/benchmark/sample-benchmark.apk
CONTRACT=acceptance/android_camera2_product_alpha_v1.json

mkdir -p "$REPORT_DIR"
if [ "$SKIP_BUILD" != 1 ]; then
    android/gradlew -p android :sample:assembleBenchmark --offline
fi

"$ADB_BIN" shell dumpsys thermalservice > "$REPORT_DIR/thermal_before.txt"
"$ADB_BIN" install -r "$APK"
"$ADB_BIN" shell pm grant "$PACKAGE" android.permission.CAMERA
"$ADB_BIN" shell input keyevent KEYCODE_WAKEUP || true
"$ADB_BIN" shell wm dismiss-keyguard || true
"$ADB_BIN" shell am force-stop "$PACKAGE"
"$ADB_BIN" shell dumpsys gfxinfo "$PACKAGE" reset >/dev/null || true
"$ADB_BIN" shell am start -W -n "$ACTIVITY"

pid=""
attempt=0
while [ -z "$pid" ] && [ "$attempt" -lt 10 ]; do
    pid=$("$ADB_BIN" shell pidof -s "$PACKAGE" | tr -d '\r')
    attempt=$((attempt + 1))
    [ -n "$pid" ] || sleep 1
done
if [ -z "$pid" ]; then
    echo "Camera2 sample process did not start" >&2
    exit 1
fi

attempt=0
while [ "$attempt" -lt 45 ]; do
    "$ADB_BIN" logcat --pid="$pid" -d -v threadtime > "$REPORT_DIR/camera2_log.txt"
    if python3 tools/evaluate_android_camera2_log.py \
      --contract "$CONTRACT" \
      --log "$REPORT_DIR/camera2_log.txt" \
      > "$REPORT_DIR/evaluation.json"; then
        "$ADB_BIN" shell dumpsys meminfo "$PACKAGE" > "$REPORT_DIR/meminfo.txt"
        "$ADB_BIN" shell dumpsys gfxinfo "$PACKAGE" > "$REPORT_DIR/gfxinfo.txt"
        "$ADB_BIN" shell dumpsys thermalservice > "$REPORT_DIR/thermal_after.txt"
        cat "$REPORT_DIR/evaluation.json"
        exit 0
    fi
    if ! "$ADB_BIN" shell pidof -s "$PACKAGE" >/dev/null; then
        echo "Camera2 sample process exited before acceptance" >&2
        cat "$REPORT_DIR/camera2_log.txt" >&2
        exit 1
    fi
    attempt=$((attempt + 1))
    sleep 1
done

"$ADB_BIN" shell dumpsys meminfo "$PACKAGE" > "$REPORT_DIR/meminfo.txt" || true
"$ADB_BIN" shell dumpsys gfxinfo "$PACKAGE" > "$REPORT_DIR/gfxinfo.txt" || true
"$ADB_BIN" shell dumpsys thermalservice > "$REPORT_DIR/thermal_after.txt" || true
echo "Camera2 PRIVATE smoke acceptance timed out" >&2
cat "$REPORT_DIR/evaluation.json" >&2
exit 1
