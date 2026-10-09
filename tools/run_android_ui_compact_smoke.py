#!/usr/bin/env python3
"""Safe 640x320dp/200% ToviCam UI regression on an explicit emulator.

Requires an installed SHA-256-matched, unconfigured build, API 30+, CAMERA
already granted, active density 240, an initial free style and an existing app
JPEG. Temporarily sets wm size to 960x480 and font_scale to 2.0. Finally restores
the exact original size override state, font setting and visible free style.
Never captures, buys, deletes, shares, grants permissions or writes private
preferences. Restore is inspected, not invoked. Saves screenshots and scoped
JSON; checks MediaStore IDs/JPEG hashes, locale and permission invariants.
Does not cover checkout, billing restore success, photos UI, capture/engine
quality, pinch, physical devices, other screen sizes or visual pixel fidelity.
"""
from __future__ import annotations

import argparse
import json
import re
import struct
import time
import traceback
from pathlib import Path

from run_android_billing_smoke import CAMERA_HIDDEN, FREE, PAID
from run_android_ui_redesign_smoke import RedesignSmoke


def size_state(raw):
    physical = re.search(r"Physical size:\s*(\d+x\d+)", raw)
    override = re.search(r"Override size:\s*(\d+x\d+)", raw)
    assert physical, "Physical display geometry is unavailable"
    return {"physical": physical.group(1), "override": override.group(1) if override else None}


class CompactSmoke(RedesignSmoke):
    def __init__(self, args):
        super().__init__(args)
        self.report.update({
            "scope": "640x320dp at 200%: camera EV/zoom, trial/detail targets, settings Back",
            "not_covered": [
                "checkout, entitlement changes, billing restore success or pending/owned states",
                "photo browsing, zoom, sharing or deletion", "capture, EXIF, image/engine quality",
                "pinch/multitouch", "physical devices", "other geometries/fonts",
                "visual pixel fidelity or complete settings operations",
            ],
            "restore_button_taps": 0, "photos_taken": 0, "photos_deleted": 0, "shares_sent": 0,
        })

    def tap(self, node, **kwargs):
        identifier = node.get("resource-id", "").rsplit("/", 1)[-1]
        allowed = set(FREE + PAID) | {
            "exposure_value", "zoom_ratio", "adjustment_reset", "adjustment_done",
            "camera_unlock_style", "purchase_back", "purchase_preview", "open_settings", "settings_back",
        }
        zoom_opener = not identifier and node.get("selected") == "true" and \
            re.fullmatch(r"\d+(?:[.,]\d+)?×", node.get("text", ""))
        assert identifier in allowed or zoom_opener, f"Compact smoke forbids tapping {identifier or node.get('text')}"
        super().tap(node, **kwargs)

    def geometry(self, name, *, timeout=35):
        data = self.adb("exec-out", "screencap", "-p", binary=True, timeout=timeout)
        assert data.startswith(b"\x89PNG\r\n\x1a\n") and len(data) >= 24, "Invalid screenshot geometry"
        self.width, self.height = struct.unpack(">II", data[16:24])
        (self.output / (name + ".png")).write_bytes(data)
        self.report["artifacts"][name + "_screenshot"] = name + ".png"
        return self.width, self.height

    def adjustment(self, identifier, font):
        # Use the same actual-value and 48dp checks as portrait. The selected
        # focal preset now opens zoom, and the panel's Reset returns to 1×.
        super().adjustment(identifier, font)

    def details(self, initial_style):
        self.tap(self.style_target(PAID[0]))
        self.snapshot("compact-trial", self.camera(PAID[0], trial=True))
        self.tap(self.accessible_target("camera_unlock_style", record="compact/unlock"))
        self.purchase()
        status = self.scroll_to("purchase_status", interactive=False)
        assert self.label_matches(status.get("text", ""), "billing_not_configured"), "Only unconfigured checkout may be tested"
        self.snapshot("compact-detail-status")
        self.accessible_target("purchase_restore", record="compact/purchase_restore")
        self.accessible_target("purchase_retry", record="compact/purchase_retry")
        self.snapshot("compact-detail-restore")
        buy = self.scroll_to("purchase_buy")
        assert buy.get("enabled") == "false" and buy.get("clickable") == "true", "Checkout must remain disabled"
        assert self.label_matches(buy.get("text", ""), "purchase_buy_unavailable"), "Unexpected disabled checkout label"
        left, top, right, bottom = self.bounds(buy)
        assert min(right - left, bottom - top) >= 48 * self.density - 1, "Disabled buy target is clipped"
        self.report["touch_targets"]["compact/purchase_buy"] = {
            "id": "purchase_buy", "bounds": [left, top, right, bottom], "enabled": False,
            "width_dp": (right - left) / self.density, "height_dp": (bottom - top) / self.density,
            "label": buy.get("text"), "tapped": False,
        }
        self.snapshot("compact-detail-disabled-buy")
        self.tap(self.accessible_target("purchase_preview", record="compact/purchase_preview"))
        self.camera(PAID[0], trial=True)
        self.tap(self.style_target(initial_style))
        self.camera(initial_style)
        self.check("Compact detail status/restore/retry/disabled buy/preview are reachable; preview returns to trial")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.ycolor.team.phytoy.camera.android.gpapp")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), default="play")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("reports/ui_redesign/compact-smoke"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only explicit emulator-NNNN serials are allowed")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid activity name")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("Expected APK SHA-256 must be 64 hexadecimal characters")
    smoke = CompactSmoke(args)
    original_size = original_font = initial_style = media_before = hashes_before = permissions_before = locale_before = None
    initial_geometry = None
    size_changed = font_changed = launched = False
    try:
        assert smoke.adb("get-serialno").strip() == args.serial, "Connected serial does not match"
        qemu = {k: smoke.adb("shell", "getprop", k).strip() for k in ("ro.kernel.qemu", "ro.boot.qemu")}
        assert "1" in qemu.values(), "Device properties do not identify an emulator"
        smoke.report["emulator_properties"] = qemu
        sdk = int(smoke.adb("shell", "getprop", "ro.build.version.sdk").strip())
        assert sdk >= 30, "MediaStore invariants require API 30+"
        smoke.report["sdk"] = sdk
        original_size = size_state(smoke.adb("shell", "wm", "size"))
        densities = re.findall(r"(?:Physical|Override) density:\s*(\d+)", smoke.adb("shell", "wm", "density"))
        assert densities and int(densities[-1]) == 240, "Configure density 240 before running; this test does not change density"
        smoke.density = 1.5
        smoke.report["wm_size_original"] = original_size
        paths = [line[8:] for line in smoke.adb("shell", "pm", "path", args.package).splitlines() if line.startswith("package:")]
        assert paths, "App is not installed"
        apks = [{"path": p, "sha256": smoke.shell("sha256sum", p).split()[0]} for p in paths]
        assert all(re.fullmatch(r"[0-9a-f]{64}", a["sha256"]) for a in apks), "Invalid APK hash"
        base = next((a for a in apks if a["path"].endswith("/base.apk")), apks[0])
        smoke.report["installed_apks"] = apks
        assert base["sha256"] == args.expected_apk_sha256.lower(), "Installed APK hash mismatch"
        permissions_before = smoke.permissions()
        assert permissions_before.get("android.permission.CAMERA") == "true", "Grant CAMERA before running"
        locale_before = smoke.adb("shell", "getprop", "persist.sys.locale").strip()
        original_font = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
        assert original_font == "null" or re.fullmatch(r"\d+(?:\.\d+)?", original_font), "Unexpected original font scale"
        smoke.report["font_scale_original"] = original_font
        media_before = smoke.media("initial")
        assert media_before, "Seed an existing app JPEG; this test never captures"
        hashes_before = smoke.media_hashes(media_before, "initial")
        initial_geometry = smoke.geometry("initial-display")
        smoke.report["initial_geometry"] = list(initial_geometry)
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial_style = next((key for key in FREE if (n := smoke.find(key, nodes)) and n.get("selected") == "true"), None)
        assert initial_style, "The initial visible style must be free"
        smoke.report["initial_visible_style"] = initial_style
        smoke.phase = "640x320dp / 200%"
        size_changed = True
        smoke.adb("shell", "wm", "size", "960x480")
        assert size_state(smoke.adb("shell", "wm", "size"))["override"] == "960x480", "Compact override not applied"
        font_changed = True
        smoke.adb("shell", "settings", "put", "system", "font_scale", "2.0")
        assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == "2.0", "Font scale not applied"
        smoke.restart()
        assert smoke.geometry("compact-display") == (960, 480), "Display rotation does not expose 960x480; no rotation settings are changed"
        smoke.report["display"] = {"width_pixels": 960, "height_pixels": 480, "density_dpi": 240,
                                   "width_dp": 640, "height_dp": 320, "font_scale": 2.0}
        smoke.snapshot("compact-camera", smoke.camera(initial_style))
        smoke.adjustment("exposure_value", "2.0")
        smoke.adjustment("zoom_ratio", "2.0")
        smoke.details(initial_style)
        smoke.tap(smoke.accessible_target("open_settings", record="compact/open_settings"))
        smoke.snapshot("compact-settings", smoke.settle(("settings_back",), CAMERA_HIDDEN))
        smoke.tap(smoke.accessible_target("settings_back", record="compact/settings_back"))
        smoke.snapshot("compact-camera-after-settings", smoke.camera(initial_style))
        smoke.check("Compact settings Back returns to the selected free camera")
        smoke.report["passed"] = True
    except (Exception, KeyboardInterrupt) as exc:
        smoke.report["failure"] = f"{type(exc).__name__}: {exc}"
        (smoke.output / "failure-traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
        (smoke.output / "failure-ui.json").write_text(json.dumps(smoke.last_nodes, indent=2, ensure_ascii=False), encoding="utf-8")
        if launched:
            try:
                smoke.screenshot("failure")
            except Exception as evidence_error:
                smoke.report["failure_evidence_error"] = str(evidence_error)
    finally:
        cleanup_errors = []
        if size_changed and original_size is not None:
            try:
                smoke.adb("shell", "wm", "size", original_size["override"] or "reset")
                assert size_state(smoke.adb("shell", "wm", "size")) == original_size, "Original size override state not restored"
                smoke.report["wm_size_restored"] = True
            except Exception as exc:
                cleanup_errors.append("size restore: " + str(exc))
        if font_changed and original_font is not None:
            try:
                if original_font == "null":
                    smoke.adb("shell", "settings", "delete", "system", "font_scale")
                else:
                    smoke.adb("shell", "settings", "put", "system", "font_scale", original_font)
                assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == original_font, "Font not restored"
                smoke.report["font_scale_restored"] = True
            except Exception as exc:
                cleanup_errors.append("font restore: " + str(exc))
        if launched and initial_style:
            try:
                smoke.restart()
                smoke.camera()
                assert initial_geometry is not None, "Initial screenshot geometry unavailable"
                deadline = time.monotonic() + 15
                observations = []
                smoke.report["geometry_restore"] = {
                    "expected": list(initial_geometry), "timeout_seconds": 15,
                    "observations": observations,
                }
                while True:
                    remaining = deadline - time.monotonic()
                    assert remaining > 0, "Restored display did not reach initial screenshot geometry within 15 seconds"
                    observed = smoke.geometry("restored-display", timeout=remaining)
                    observations.append(list(observed))
                    if observed == initial_geometry:
                        break
                    time.sleep(min(.2, max(0, deadline - time.monotonic())))
                smoke.tap(smoke.style_target(initial_style))
                smoke.snapshot("restored-camera", smoke.camera(initial_style))
                smoke.report["initial_visible_style_restored"] = True
            except Exception as exc:
                cleanup_errors.append("style restore: " + str(exc))
        if media_before is not None:
            try:
                assert smoke.media("final") == media_before, "Media IDs changed"
                assert smoke.media_hashes(media_before, "final") == hashes_before, "JPEG bytes changed"
                smoke.report["media_ids_and_jpeg_bytes_unchanged"] = True
            except Exception as exc:
                cleanup_errors.append("media invariant: " + str(exc))
        if permissions_before is not None:
            try:
                assert smoke.permissions() == permissions_before, "Permission grants changed"
                smoke.report["permissions_unchanged"] = True
            except Exception as exc:
                cleanup_errors.append("permission invariant: " + str(exc))
        if locale_before is not None:
            try:
                assert smoke.adb("shell", "getprop", "persist.sys.locale").strip() == locale_before, "Locale changed"
                smoke.report["system_locale_unchanged"] = True
            except Exception as exc:
                cleanup_errors.append("locale invariant: " + str(exc))
        if smoke.dump_created:
            try:
                smoke.shell("rm", "-f", smoke.dump)
            except Exception as exc:
                cleanup_errors.append("scratch cleanup: " + str(exc))
        if cleanup_errors:
            smoke.report["cleanup_errors"] = cleanup_errors
            smoke.report["passed"] = False
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json"),
                      "not_covered": smoke.report["not_covered"]}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
