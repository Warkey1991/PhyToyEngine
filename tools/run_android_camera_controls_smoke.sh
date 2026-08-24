#!/bin/sh
set -eu

ADB_BIN=${ADB_BIN:-adb}
REPORT_DIR=${REPORT_DIR:-reports/android_camera_controls_smoke}
SKIP_BUILD=${SKIP_BUILD:-0}
PACKAGE=com.phytoy.sample
ACTIVITY="$PACKAGE/.MainActivity"
APK=android/sample/build/outputs/apk/benchmark/sample-benchmark.apk
DEVICE_UI=/sdcard/phytoy_controls_ui.xml

mkdir -p "$REPORT_DIR"
if [ "$SKIP_BUILD" != 1 ]; then
    android/gradlew -p android :sample:assembleBenchmark --offline
fi

"$ADB_BIN" install -r "$APK"
"$ADB_BIN" shell pm grant "$PACKAGE" android.permission.CAMERA
"$ADB_BIN" shell am force-stop "$PACKAGE"
"$ADB_BIN" shell am start -W -n "$ACTIVITY" > "$REPORT_DIR/start.txt"

dump_ui() {
    "$ADB_BIN" shell uiautomator dump "$DEVICE_UI" >/dev/null 2>&1 || true
    "$ADB_BIN" pull "$DEVICE_UI" "$REPORT_DIR/ui.xml" >/dev/null 2>&1 || true
}

control_center() {
    target=$1
    expected_description=${2:-}
    python3 - "$REPORT_DIR/ui.xml" "$target" "$expected_description" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

path, target, expected = sys.argv[1:]
for node in ET.parse(path).iter("node"):
    if not node.attrib.get("resource-id", "").endswith(f":id/{target}"):
        continue
    if node.attrib.get("enabled") != "true":
        continue
    if expected and expected not in node.attrib.get("content-desc", ""):
        continue
    values = [int(value) for value in re.findall(r"\d+", node.attrib["bounds"])]
    print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
    break
PY
}

preview_focus_point() {
    python3 - "$REPORT_DIR/ui.xml" <<'PY'
import re
import sys
import xml.etree.ElementTree as ET

for node in ET.parse(sys.argv[1]).iter("node"):
    if node.attrib.get("class") != "android.view.TextureView":
        continue
    left, top, right, bottom = [int(value) for value in re.findall(r"\d+", node.attrib["bounds"])]
    print(int(left + (right - left) * 0.38), int(top + (bottom - top) * 0.38))
    break
PY
}

wait_for_control() {
    target=$1
    expected_description=${2:-}
    coordinates=""
    attempt=0
    while [ -z "$coordinates" ] && [ "$attempt" -lt 30 ]; do
        dump_ui
        if [ -f "$REPORT_DIR/ui.xml" ]; then
            coordinates=$(control_center "$target" "$expected_description" || true)
        fi
        attempt=$((attempt + 1))
        [ -n "$coordinates" ] || sleep 1
    done
    [ -n "$coordinates" ] || return 1
    printf '%s\n' "$coordinates"
}

switch_coordinates=$(wait_for_control switch_camera "Switch to front camera")
dump_ui
focus_coordinates=$(preview_focus_point)
set -- $focus_coordinates
"$ADB_BIN" logcat -c
"$ADB_BIN" shell input tap "$1" "$2"
sleep 2
"$ADB_BIN" logcat -d -v threadtime > "$REPORT_DIR/back_focus_log.txt"

set -- $switch_coordinates
"$ADB_BIN" shell input tap "$1" "$2"
front_switch_coordinates=$(wait_for_control switch_camera "Switch to back camera")
cp "$REPORT_DIR/ui.xml" "$REPORT_DIR/front_ui.xml"

focus_coordinates=$(preview_focus_point)
set -- $focus_coordinates
"$ADB_BIN" logcat -c
"$ADB_BIN" shell input tap "$1" "$2"
sleep 2
"$ADB_BIN" logcat -d -v threadtime > "$REPORT_DIR/front_focus_log.txt"

shutter_coordinates=$(wait_for_control shutter)
set -- $shutter_coordinates
"$ADB_BIN" logcat -c
"$ADB_BIN" shell input tap "$1" "$2"

photo_uri=""
attempt=0
while [ -z "$photo_uri" ] && [ "$attempt" -lt 15 ]; do
    "$ADB_BIN" logcat -d -v threadtime > "$REPORT_DIR/front_capture_log.txt"
    photo_uri=$(python3 - "$REPORT_DIR/front_capture_log.txt" <<'PY'
import re
import sys
from pathlib import Path

text = Path(sys.argv[1]).read_text(encoding="utf-8", errors="replace")
matches = re.findall(r"Digital 01 photo saved: (content://\S+)", text)
print(matches[-1] if matches else "")
PY
    )
    attempt=$((attempt + 1))
    [ -n "$photo_uri" ] || sleep 1
done

[ -n "$photo_uri" ] || {
    echo "Front-camera Digital 01 photo was not saved" >&2
    exit 1
}

"$ADB_BIN" shell content query \
    --uri "$photo_uri" \
    --projection _id:_display_name:mime_type:width:height:relative_path \
    > "$REPORT_DIR/front_media.txt"

python3 - "$REPORT_DIR/back_focus_log.txt" "$REPORT_DIR/front_focus_log.txt" \
    "$REPORT_DIR/front_capture_log.txt" "$REPORT_DIR/front_media.txt" \
    "$REPORT_DIR/front_ui.xml" > "$REPORT_DIR/evaluation.json" <<'PY'
import json
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

back_focus, front_focus, capture, media, front_ui = [
    Path(path).read_text(encoding="utf-8", errors="replace") for path in sys.argv[1:]
]

def field(name: str) -> str:
    match = re.search(rf"(?:^|, )\b{name}=([^,]+)", media)
    return match.group(1).strip() if match else ""

width = int(field("width") or 0)
height = int(field("height") or 0)
preview_aspect = None
for node in ET.fromstring(front_ui).iter("node"):
    if node.attrib.get("class") != "android.view.TextureView":
        continue
    left, top, right, bottom = [
        int(value) for value in re.findall(r"\d+", node.attrib["bounds"])
    ]
    preview_aspect = (right - left) / max(1, bottom - top)
    break
three_a_gate = re.search(
    r"Camera2 3A gate status=(READY|TIMED_OUT|FAILED).*elapsed_ms=(\d+)",
    capture,
)
gate_position = capture.find("Camera2 3A gate status=")
still_position = capture.find("Camera2 high-resolution PRIVATE capture submitted:")
ready_position = capture.find("Digital 01 still capture ready:")
restore_position = capture.find("Camera2 3A restored continuous preview")
checks = {
    "back_touch_metering_requested": "Touch AF/AE requested" in back_focus,
    "front_camera_ready": "Switch to back camera" in front_ui,
    "front_12mp_ui": "12.0 MP" in front_ui or "12.5 MP" in front_ui,
    "front_touch_metering_requested": "Touch AF/AE requested" in front_focus,
    "front_touch_af_reused_for_capture": "Camera2 3A reusing touch AF lock" in capture,
    "front_gpu_still_surface": "one_submission host_readback source=still" in capture,
    "front_high_resolution_photo": width * height >= 12_000_000,
    "front_portrait_orientation": height > width,
    "front_jpeg_media_store": field("mime_type") == "image/jpeg" and
        field("relative_path").startswith("Pictures/PhyToy"),
    "front_bounded_3a_gate": three_a_gate is not None and
        int(three_a_gate.group(2)) <= 3_250,
    "front_3a_before_still": 0 <= gate_position < still_position < ready_position,
    "front_continuous_preview_restored": ready_position < restore_position,
    "front_capture_matched_3_by_4_viewport": preview_aspect is not None and
        abs(preview_aspect - 0.75) <= 0.01,
}
result = {
    "passed": all(checks.values()),
    "front_width": width,
    "front_height": height,
    "front_three_a_status": three_a_gate.group(1) if three_a_gate else "missing",
    "front_three_a_elapsed_ms": int(three_a_gate.group(2)) if three_a_gate else None,
    "front_preview_aspect": preview_aspect,
    "checks": checks,
    "observations": {
        "back_autofocus_terminal_state": "Touch AF locked" in back_focus or
            "Touch AF completed without lock" in back_focus,
        "front_autofocus_terminal_state": "Touch AF locked" in front_focus or
            "Touch AF completed without lock" in front_focus,
    },
}
print(json.dumps(result, indent=2, ensure_ascii=False))
if not result["passed"]:
    raise SystemExit(1)
PY

cat "$REPORT_DIR/evaluation.json"
