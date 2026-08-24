#!/bin/sh
set -eu

ADB_BIN=${ADB_BIN:-adb}
REPORT_DIR=${REPORT_DIR:-reports/android_photo_smoke}
SKIP_BUILD=${SKIP_BUILD:-0}
PACKAGE=com.phytoy.sample
ACTIVITY="$PACKAGE/.MainActivity"
APK=android/sample/build/outputs/apk/benchmark/sample-benchmark.apk
DEVICE_UI=/sdcard/phytoy_photo_ui.xml

mkdir -p "$REPORT_DIR"
if [ "$SKIP_BUILD" != 1 ]; then
    android/gradlew -p android :sample:assembleBenchmark --offline
fi

"$ADB_BIN" install -r "$APK"
"$ADB_BIN" shell pm grant "$PACKAGE" android.permission.CAMERA
"$ADB_BIN" shell am force-stop "$PACKAGE"
"$ADB_BIN" logcat -c
"$ADB_BIN" shell am start -W -n "$ACTIVITY" > "$REPORT_DIR/start.txt"

coordinates=""
attempt=0
while [ -z "$coordinates" ] && [ "$attempt" -lt 30 ]; do
    "$ADB_BIN" shell uiautomator dump "$DEVICE_UI" >/dev/null 2>&1 || true
    "$ADB_BIN" pull "$DEVICE_UI" "$REPORT_DIR/ui.xml" >/dev/null 2>&1 || true
    if [ -f "$REPORT_DIR/ui.xml" ]; then
        coordinates=$(python3 - "$REPORT_DIR/ui.xml" <<'PY' || true
import re
import sys
import xml.etree.ElementTree as ET

for node in ET.parse(sys.argv[1]).iter("node"):
    if node.attrib.get("resource-id", "").endswith(":id/shutter"):
        if node.attrib.get("enabled") != "true":
            break
        values = [int(value) for value in re.findall(r"\d+", node.attrib["bounds"])]
        print((values[0] + values[2]) // 2, (values[1] + values[3]) // 2)
        break
PY
        )
    fi
    attempt=$((attempt + 1))
    [ -n "$coordinates" ] || sleep 1
done

if [ -z "$coordinates" ]; then
    echo "Enabled PhyToy shutter was not found" >&2
    exit 1
fi

set -- $coordinates
"$ADB_BIN" logcat -c
"$ADB_BIN" shell input tap "$1" "$2"

photo_uri=""
attempt=0
while [ -z "$photo_uri" ] && [ "$attempt" -lt 15 ]; do
    "$ADB_BIN" logcat -d -v threadtime > "$REPORT_DIR/capture_log.txt"
    photo_uri=$(python3 - "$REPORT_DIR/capture_log.txt" <<'PY'
import re
import sys

text = open(sys.argv[1], encoding="utf-8", errors="replace").read()
matches = re.findall(r"Digital 01 photo saved: (content://\S+)", text)
print(matches[-1] if matches else "")
PY
    )
    attempt=$((attempt + 1))
    [ -n "$photo_uri" ] || sleep 1
done

if [ -z "$photo_uri" ]; then
    echo "Digital 01 photo was not saved within 15 seconds" >&2
    tail -120 "$REPORT_DIR/capture_log.txt" >&2
    exit 1
fi

"$ADB_BIN" shell content query \
    --uri "$photo_uri" \
    --projection _id:_display_name:mime_type:width:height:relative_path \
    > "$REPORT_DIR/media.txt"
"$ADB_BIN" exec-out content read --uri "$photo_uri" > "$REPORT_DIR/captured.jpg"

python3 - "$photo_uri" "$REPORT_DIR/media.txt" "$REPORT_DIR/captured.jpg" \
    "$REPORT_DIR/capture_log.txt" \
    > "$REPORT_DIR/evaluation.json" <<'PY'
import json
import re
import sys
from pathlib import Path

uri, metadata_path, image_path, log_path = sys.argv[1:]
metadata = Path(metadata_path).read_text(encoding="utf-8", errors="replace")
image = Path(image_path).read_bytes()
capture_log = Path(log_path).read_text(encoding="utf-8", errors="replace")

def field(name: str) -> str:
    match = re.search(rf"(?:^|, )\b{name}=([^,]+)", metadata)
    return match.group(1).strip() if match else ""

width = int(field("width") or 0)
height = int(field("height") or 0)
checks = {
    "media_row_exists": metadata.startswith("Row:"),
    "jpeg_mime": field("mime_type") == "image/jpeg",
    "portrait_dimensions": width >= 3_000 and height >= 4_000 and height > width,
    "high_resolution_12mp": width * height >= 12_000_000,
    "phytoy_album": field("relative_path").startswith("Pictures/PhyToy"),
    "jpeg_signature": image.startswith(b"\xff\xd8") and image.endswith(b"\xff\xd9"),
    "nonempty_file": len(image) >= 50_000,
    "gpu_still_surface": "one_submission host_readback source=still" in capture_log,
}
result = {
    "passed": all(checks.values()),
    "uri": uri,
    "display_name": field("_display_name"),
    "width": width,
    "height": height,
    "bytes": len(image),
    "checks": checks,
}
print(json.dumps(result, indent=2, ensure_ascii=False))
if not result["passed"]:
    raise SystemExit(1)
PY

cat "$REPORT_DIR/evaluation.json"
