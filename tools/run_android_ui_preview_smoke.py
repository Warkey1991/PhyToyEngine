#!/usr/bin/env python3
"""Capture four normal-font UI screens on this task's emulator-5580 only.

Requires the already installed APK to match an explicit SHA-256, API 30+,
CAMERA already granted, font_scale already exactly 1.0, a free camera selected
on startup, and an existing app JPEG. Uses only fresh visible UI, with inherited
48dp target checks and forbidden shutter/purchase/delete-confirm taps. It never
changes font scale, settings, permissions or locale, captures a photo, enters
checkout, deletes a photo, opens sharing, or operates a physical device.

Route: camera -> gallery -> gallery Settings -> Back to gallery -> first visible
photo -> Continue shooting. This is navigation/screenshot evidence, not a full
UI regression, gesture test, capture test, or proof of pixel-level correctness.
Finally restores the initial visible free camera and independently verifies
MediaStore IDs/JPEG hashes, permissions, locale and font scale remain unchanged.
"""
from __future__ import annotations

import argparse
import json
import re
import traceback
from pathlib import Path

from run_android_billing_smoke import CAMERA_HIDDEN, FREE
from run_android_ui_redesign_smoke import RedesignSmoke


class PreviewSmoke(RedesignSmoke):
    def tap(self, node, **kwargs):
        identifier = node.get("resource-id", "").rsplit("/", 1)[-1]
        assert identifier not in ("review_share", "review_delete"), "Sharing/deletion are outside preview scope"
        assert not identifier.startswith("purchase_"), "Purchase-page actions are outside preview scope"
        assert not identifier.startswith("settings_") or identifier == "settings_back", (
            "Settings controls must remain unchanged during the preview")
        super().tap(node, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True, choices=("emulator-5580",))
    parser.add_argument("--package", default="com.ycolor.team.phytoy.camera.android.gpapp")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), default="play")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("reports/ui_redesign_20261007/normal-preview"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid activity name")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("Expected APK SHA-256 must be 64 hexadecimal characters")
    smoke = PreviewSmoke(args)
    smoke.report.update({
        "scope": "100% font screenshots and navigation only: camera, gallery, settings, existing-photo review",
        "font_mutations": 0, "setting_control_changes": 0, "share_chooser_opened": False,
        "not_covered": [
            "checkout or purchase success", "successful deletion or delete consent", "sharing",
            "photo capture, output quality, EXIF or engine correctness", "photo gestures or adjustment sliders",
            "200% font regression", "complete settings operations or privacy document contents",
            "physical devices", "visual pixel correctness",
        ],
    })
    initial_style = media_before = hashes_before = permissions_before = locale_before = font_before = None
    launched = False
    try:
        assert smoke.adb("get-serialno").strip() == args.serial, "Connected serial does not match this task's emulator"
        qemu = {key: smoke.adb("shell", "getprop", key).strip() for key in ("ro.kernel.qemu", "ro.boot.qemu")}
        assert "1" in qemu.values(), "Device properties do not identify an emulator"
        smoke.report["emulator_properties"] = qemu
        sdk = int(smoke.adb("shell", "getprop", "ro.build.version.sdk").strip())
        assert sdk >= 30, "MediaStore invariants require API 30+"
        smoke.report["sdk"] = sdk
        sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", smoke.adb("shell", "wm", "size"))
        densities = re.findall(r"(?:Physical|Override) density:\s*(\d+)", smoke.adb("shell", "wm", "density"))
        assert sizes and densities, "Display geometry unavailable"
        smoke.width, smoke.height = map(int, sizes[-1])
        smoke.density = int(densities[-1]) / 160
        smoke.report["display"] = {"width_pixels": smoke.width, "height_pixels": smoke.height,
                                   "density_dpi": int(densities[-1])}
        paths = [line[8:] for line in smoke.adb("shell", "pm", "path", args.package).splitlines()
                 if line.startswith("package:")]
        assert paths, "App is not installed"
        apks = [{"path": path, "sha256": smoke.shell("sha256sum", path).split()[0]} for path in paths]
        assert all(re.fullmatch(r"[0-9a-f]{64}", apk["sha256"]) for apk in apks), "Invalid APK hash"
        base = next((apk for apk in apks if apk["path"].endswith("/base.apk")), apks[0])
        smoke.report["installed_apks"] = apks
        smoke.report["installed_apk_sha256"] = base["sha256"]
        assert base["sha256"] == args.expected_apk_sha256.lower(), "Installed APK does not match the expected final build"
        permissions_before = smoke.permissions()
        assert permissions_before.get("android.permission.CAMERA") == "true", "CAMERA must already be granted"
        locale_before = smoke.adb("shell", "getprop", "persist.sys.locale").strip()
        font_before = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
        smoke.report["font_scale_original"] = font_before
        assert font_before == "1.0", "font_scale must already be 1.0; this script never changes it"
        media_before = smoke.media("initial")
        assert media_before, "An existing app JPEG is required; this script never captures one"
        hashes_before = smoke.media_hashes(media_before, "initial")

        smoke.phase = "normal camera"
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial_style = next((key for key in FREE if (node := smoke.find(key, nodes)) and
                              node.get("selected") == "true"), None)
        assert initial_style is not None, "The initial visible camera must be free"
        smoke.report["initial_visible_style"] = initial_style
        smoke.snapshot("camera-normal", nodes)
        smoke.tap(smoke.accessible_target("last_photo", record="normal/last_photo"))

        smoke.phase = "normal gallery"
        def gallery_ready(nodes):
            return visible_node(smoke, "gallery_back", nodes) and any(
                node.get("resource-id", "").endswith(":id/gallery_item") and smoke.visible(node)
                for node in nodes)
        nodes = smoke.poll(gallery_ready, "Existing app photo did not appear in gallery", timeout=40)
        assert all(smoke.find(identifier, nodes) is None for identifier in CAMERA_HIDDEN), "Camera leaked through gallery modal"
        smoke.snapshot("gallery-normal", nodes)
        smoke.tap(smoke.accessible_target("gallery_tab_settings", record="normal/gallery_tab_settings"))

        smoke.phase = "normal settings"
        nodes = smoke.settle(("settings_back",), CAMERA_HIDDEN + ("gallery_back",))
        smoke.snapshot("settings-normal", nodes)
        smoke.tap(smoke.accessible_target("settings_back", record="normal/settings_back"))
        nodes = smoke.poll(lambda ns: gallery_ready(ns) and smoke.find("settings_back", ns) is None,
                           "Settings Back did not return to gallery")
        smoke.check("100%: gallery Settings opens and Back returns to gallery without changing a setting")
        item = next((node for node in nodes if node.get("resource-id", "").endswith(":id/gallery_item")
                     and smoke.visible(node)), None)
        assert item is not None, "No existing gallery item remains visible"
        smoke.tap(item)

        smoke.phase = "normal review"
        nodes = smoke.review_ready()
        smoke.snapshot("review-normal", nodes)
        for identifier in ("review_share", "review_delete", "review_info"):
            node = smoke.find(identifier, nodes)
            assert node is not None, f"Normal review action {identifier} is missing"
            smoke.target(identifier, node, record="normal/" + identifier)
            left, top, right, bottom = smoke.bounds(node)
            assert bottom - top >= 68 * smoke.density - 1, f"Normal review action {identifier} is clipped"
        smoke.check("100%: Share/Delete/Info are fully visible without scrolling")
        smoke.report["review_position"] = list(smoke.position(nodes))
        smoke.tap(smoke.accessible_target("review_continue", record="normal/review_continue"))
        smoke.camera(initial_style)
        smoke.check("100%: existing-photo review opens and Continue shooting returns to the free camera")
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
        if launched and initial_style:
            try:
                smoke.restart()
                nodes = smoke.camera()
                if not (node := smoke.find(initial_style, nodes)) or node.get("selected") != "true":
                    smoke.tap(smoke.style_target(initial_style))
                smoke.camera(initial_style)
                smoke.report["initial_visible_style_restored"] = True
            except Exception as exc:
                cleanup_errors.append("style restore: " + str(exc))
        checks = (
            ("media", media_before is not None, lambda: verify_media(smoke, media_before, hashes_before)),
            ("permissions", permissions_before is not None, lambda: verify_equal(
                smoke.permissions(), permissions_before, "Permission grants changed")),
            ("locale", locale_before is not None, lambda: verify_equal(
                smoke.adb("shell", "getprop", "persist.sys.locale").strip(), locale_before, "Locale changed")),
            ("font", font_before is not None, lambda: verify_equal(
                smoke.adb("shell", "settings", "get", "system", "font_scale").strip(), font_before, "Font scale changed")),
        )
        for name, available, verify in checks:
            if not available:
                continue
            try:
                verify()
                smoke.report[name + "_unchanged"] = True
            except Exception as exc:
                cleanup_errors.append(name + " invariant: " + str(exc))
        if smoke.dump_created:
            try:
                smoke.shell("rm", "-f", smoke.dump)
            except Exception as exc:
                cleanup_errors.append("scratch dump cleanup: " + str(exc))
        if cleanup_errors:
            smoke.report["cleanup_errors"] = cleanup_errors
            smoke.report["passed"] = False
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json")}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


def visible_node(smoke, identifier, nodes):
    node = smoke.find(identifier, nodes)
    return node is not None and smoke.visible(node)


def verify_equal(actual, expected, message):
    assert actual == expected, message


def verify_media(smoke, media_before, hashes_before):
    verify_equal(smoke.media("final"), media_before, "MediaStore photo IDs changed")
    smoke.report["photos_changed"] = False
    if hashes_before is not None:
        verify_equal(smoke.media_hashes(media_before, "final"), hashes_before, "Existing photo bytes changed")
        smoke.report["photo_bytes_unchanged"] = True


if __name__ == "__main__":
    raise SystemExit(main())
