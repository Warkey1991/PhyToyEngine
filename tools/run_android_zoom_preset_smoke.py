#!/usr/bin/env python3
"""Emulator-only zoom regression on a hash-verified installed APK.

Checks the camera service's actual received-frame zoom and preset/slider/reset
state through a camera close/reopen cycle. Requires a zoomRatio-capable emulator.
This catches callbacks that only change CameraChrome's text. Saves real preview
screenshots and camera service dumps. No captures, purchases or permission changes.
"""
from __future__ import annotations

import argparse
import json
import re
import time
import traceback
from pathlib import Path

from run_android_billing_smoke import FREE
from run_android_ui_redesign_smoke import RedesignSmoke


class ZoomSmoke(RedesignSmoke):
    @staticmethod
    def received_zoom(service, package):
        assert f"Client package: {package}" in service, "Expected camera client is absent"
        frame = service.split("Latest received frame:", 1)[1]
        match = re.search(r"android\.control\.zoomRatio \([^\n]+\): float\[1\]\s*\[([\d.]+)", frame)
        assert match, "No actual received-frame zoom ratio; use a zoomRatio-capable emulator"
        return float(match.group(1))

    def preset(self, ratio):
        nodes = self.camera()
        node = next((n for n in nodes if n.get("text") == f"{ratio}×"
                     and n.get("clickable") == "true" and self.visible(n)), None)
        assert node is not None, f"Missing {ratio}× preset"
        self.tap(node)
        self.poll(lambda ns: abs(self.zoom_value(ns) - ratio) < .01,
                  f"{ratio}× preset did not update")
        self.observe(f"live-preset-{ratio}x", ratio)

    def observe(self, name, expected, *, tolerance=.001):
        # Verify the running session before any reopen can apply the value.
        deadline = time.monotonic() + 8
        received = None
        while time.monotonic() < deadline:
            service = self.adb("shell", "dumpsys", "media.camera")
            received = self.received_zoom(service, self.args.package)
            if abs(received - expected) < tolerance:
                break
            time.sleep(.2)
        (self.output / (name + "-camera-service.txt")).write_text(service, encoding="utf-8")
        self.snapshot(name)
        assert received is not None and abs(received - expected) < tolerance, (
            f"Live camera frames use {received}×, expected {expected}×")
        self.check(name, expected_zoom=expected, received_frame_zoom=received,
                   camera_reopened=False)

    def reopen(self, expected, name, *, tolerance=.001):
        self.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        time.sleep(1)
        self.adb("shell", "am", "start", "-W", "-n",
                 self.args.package + "/" + self.args.activity)
        self.camera()
        nodes = self.poll(lambda ns: abs(self.zoom_value(ns) - expected) < .051,
                          f"Camera reopen lost actual {expected}× controls")
        time.sleep(1)
        self.snapshot(name, nodes)
        service = self.adb("shell", "dumpsys", "media.camera")
        (self.output / (name + "-camera-service.txt")).write_text(service, encoding="utf-8")
        received = self.received_zoom(service, self.args.package)
        assert abs(received - expected) < tolerance, f"Camera frames use {received}×, expected {expected}×"
        self.check(name, expected_zoom=expected,
                   actual_camera_zoom=self.zoom_value(nodes), received_frame_zoom=received,
                   camera_reopened=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only an explicitly selected emulator is allowed")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid package")
    if not re.fullmatch(r"[0-9a-f]{64}", args.expected_apk_sha256):
        parser.error("Invalid APK SHA-256")
    smoke = ZoomSmoke(args)
    smoke.report.update(scope=__doc__, not_covered=["physical cameras", "still-photo quality",
                        "automated optical/pixel correctness", "pinch/multitouch"],
                        private_preference_write_scope="No direct preference writes; zoom uses UI only")
    media = hashes = permissions = None
    launched = False
    try:
        assert smoke.adb("get-serialno").strip() == args.serial
        assert "1" in [smoke.adb("shell", "getprop", key).strip()
                       for key in ("ro.kernel.qemu", "ro.boot.qemu")]
        size = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)",
                          smoke.adb("shell", "wm", "size"))[-1]
        smoke.width, smoke.height = map(int, size)
        smoke.density = int(re.findall(r"(?:Physical|Override) density:\s*(\d+)",
                            smoke.adb("shell", "wm", "density"))[-1]) / 160
        base = next(line[8:] for line in smoke.adb("shell", "pm", "path", args.package).splitlines()
                    if line.startswith("package:") and line.endswith("/base.apk"))
        actual_hash = smoke.shell("sha256sum", base).split()[0]
        assert actual_hash == args.expected_apk_sha256, "Installed APK mismatch"
        smoke.report["installed_apk_sha256"] = actual_hash
        permissions = smoke.permissions()
        assert permissions.get("android.permission.CAMERA") == "true"
        media = smoke.media("initial")
        hashes = smoke.media_hashes(media, "initial")
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        assert any((n := smoke.find(style, nodes)) and n.get("selected") == "true" for style in FREE)
        assert abs(smoke.zoom_value(nodes) - 1) < .01, "Fresh start must be 1×"
        smoke.phase = "actual camera controls"
        smoke.reopen(1, "camera-1x")
        smoke.preset(2)
        smoke.reopen(2, "camera-2x")
        smoke.preset(1)
        smoke.reopen(1, "camera-return-1x")
        smoke.tap(smoke.zoom_opener(smoke.camera()))
        slider = smoke.scroll_to("adjustment_slider")
        assert slider.get("class") == "android.widget.SeekBar" and slider.get("enabled") == "true"
        left, top, right, bottom = smoke.bounds(slider)
        assert smoke.visible(slider) and min(right - left, bottom - top) >= 48 * smoke.density - 1
        smoke.adb("shell", "input", "tap", str(left + int((right - left) * .65)),
                  str((top + bottom) // 2))
        nodes = smoke.poll(lambda ns: abs(smoke.zoom_value(ns) - 1) > .1,
                           "Continuous slider did not change zoom")
        ratio = smoke.zoom_value(nodes)
        smoke.tap(smoke.accessible_target("adjustment_done"))
        smoke.observe("live-slider-zoom", ratio, tolerance=.051)
        # The visible readout rounds continuous ratios to one decimal place.
        smoke.reopen(ratio, "camera-slider-zoom", tolerance=.051)
        smoke.tap(smoke.zoom_opener(smoke.camera()))
        smoke.tap(smoke.accessible_target("adjustment_reset"))
        smoke.tap(smoke.accessible_target("adjustment_done"))
        smoke.observe("live-slider-reset", 1)
        smoke.reopen(1, "camera-slider-reset")
        smoke.report["passed"] = True
    except Exception as exc:
        smoke.report["failure"] = str(exc)
        smoke.report["traceback"] = traceback.format_exc()
    finally:
        try:
            if launched:
                nodes = smoke.camera()
                if abs(smoke.zoom_value(nodes) - 1) >= .01:
                    smoke.preset(1)
            if media is not None:
                assert smoke.media("final") == media, "Media IDs changed"
                assert smoke.media_hashes(media, "final") == hashes, "Photo bytes changed"
            if permissions is not None:
                assert smoke.permissions() == permissions, "Permissions changed"
            if smoke.dump_created:
                smoke.shell("rm", "-f", smoke.dump)
            smoke.report["cleanup_passed"] = True
        except Exception as exc:
            smoke.report["cleanup_failure"] = str(exc)
            smoke.report["passed"] = False
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "checks": len(smoke.report["checks"]),
                      "output": str(smoke.output), "failure": smoke.report.get("failure"),
                      "cleanup_failure": smoke.report.get("cleanup_failure")}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
