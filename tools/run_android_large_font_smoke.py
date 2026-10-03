#!/usr/bin/env python3
"""Check an installed camera at 200% font scale on an Android device.

Requires an already granted camera permission and at least one saved gallery
photo. Does not take photos or change app preferences, permissions or locale.
Defaults to emulator-only; physical devices require explicit opt-in and serial.
Restores the original system font_scale in finally, including an unset value.
"""
from __future__ import annotations

import argparse
import json
import re
import shlex
import subprocess
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--allow-physical-device", action="store_true",
                        help="Allow changing and restoring font scale on the physical device selected by --serial")
    parser.add_argument("--expected-apk-sha256",
                        help="Require the installed base APK to match this SHA-256 before changing font scale")
    parser.add_argument("--package", default="com.phytoy.sample")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--output", type=Path, default=Path("reports/large_font_smoke"))
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("--package must be an Android application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("--activity must be an Android activity class name")
    if args.allow_physical_device and not args.serial:
        parser.error("--allow-physical-device requires an explicit --serial")
    if args.expected_apk_sha256 and not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("--expected-apk-sha256 must contain exactly 64 hexadecimal characters")
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb] + (["-s", args.serial] if args.serial else [])
    report: dict = {
        "passed": False,
        "package": args.package,
        "activity": args.activity,
        "allow_physical_device": args.allow_physical_device,
        "expected_apk_sha256": args.expected_apk_sha256.lower() if args.expected_apk_sha256 else None,
        "font_scale_target": 2.0,
        "checks": [],
        "artifacts": {},
        "touch_targets": {},
        "phase": "prerequisites",
        "taps": [],
        "photos_taken": 0,
        "app_preferences_changed": False,
        "permissions_changed": False,
        "locale_changed": False,
    }
    original_font_scale: str | None = None
    font_scale_changed = False
    test_launched = False
    dump_path = f"/sdcard/phytoy_large_font_qa_{time.time_ns()}.xml"
    dump_created = False
    density = 1.0
    screen_width = screen_height = 0
    last_nodes: list[dict[str, str]] = []

    def adb(*command: str, binary: bool = False, timeout: float = 35):
        result = subprocess.run(prefix + list(command), capture_output=True,
                                text=not binary, timeout=timeout)
        if result.returncode:
            detail = result.stderr.decode(errors="replace") if binary else result.stderr
            raise RuntimeError(f"ADB {command[0]} failed: {detail.strip() or 'device connection lost'}")
        return result.stdout

    def save_report() -> None:
        (args.output / "evaluation.json").write_text(
            json.dumps(report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def phase(name: str) -> None:
        report["phase"] = name
        save_report()

    def ui(*, timeout: float = 35) -> list[dict[str, str]]:
        nonlocal dump_created, last_nodes
        dump_created = True
        deadline = time.monotonic() + timeout
        adb("shell", "rm", "-f", dump_path, timeout=max(.05, deadline - time.monotonic()))
        result = adb("shell", "uiautomator", "dump", "--compressed", dump_path,
                     timeout=max(.05, deadline - time.monotonic()))
        assert "UI hierchary dumped to:" in result or "UI hierarchy dumped to:" in result, result
        xml = adb("exec-out", "cat", dump_path, timeout=max(.05, deadline - time.monotonic()))
        last_nodes = [node.attrib for node in ET.fromstring(xml).iter("node")]
        assert last_nodes, "Empty accessibility hierarchy"
        return last_nodes

    def find(identifier: str, nodes: list[dict[str, str]]) -> dict[str, str] | None:
        return next((node for node in nodes
                     if node.get("resource-id", "").endswith(":id/" + identifier)), None)

    def bounds(node: dict[str, str]) -> tuple[int, int, int, int]:
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        if not match:
            raise AssertionError(f"Invalid UI bounds: {node.get('bounds')}")
        return tuple(map(int, match.groups()))

    def visible(node: dict[str, str]) -> bool:
        left, top, right, bottom = bounds(node)
        return 0 <= left < right <= screen_width and 0 <= top < bottom <= screen_height

    def wait(identifier: str, *, enabled: bool = True, timeout: float = 30) -> dict[str, str]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = find(identifier, ui())
            if node and visible(node) and (not enabled or node.get("enabled") == "true"):
                return node
            time.sleep(0.3)
        raise AssertionError(f"Timed out waiting for visible {identifier}, enabled={enabled}")

    def target(identifier: str, node: dict[str, str], page: str) -> None:
        assert visible(node), f"{identifier} is outside the display"
        assert node.get("enabled") == "true", f"{identifier} is disabled"
        assert node.get("clickable") == "true", f"{identifier} is not exposed as clickable"
        assert node.get("text") or node.get("content-desc"), f"{identifier} lacks an accessible label"
        left, top, right, bottom = bounds(node)
        # Native dp conversion can truncate by one physical pixel.
        minimum_pixels = 48 * density - 1
        assert right - left >= minimum_pixels and bottom - top >= minimum_pixels, (
            f"{identifier} touch target is {(right-left)/density:.2f}×"
            f"{(bottom-top)/density:.2f}dp; expected at least 48×48dp")
        report["touch_targets"][f"{page}/{identifier}"] = {
            "bounds": [left, top, right, bottom],
            "width_dp": round((right - left) / density, 2),
            "height_dp": round((bottom - top) / density, 2),
            "clickable": True,
            "enabled": True,
            "accessible_label": node.get("text") or node.get("content-desc"),
        }

    def tap(node: dict[str, str]) -> None:
        assert visible(node), "Control has no visible touch target"
        assert node.get("clickable") == "true" and node.get("enabled") == "true", (
            "Cannot tap a disabled or nonclickable control")
        left, top, right, bottom = bounds(node)
        report["taps"].append({"phase": report["phase"], "id": node.get("resource-id"),
                               "point": [(left + right) // 2, (top + bottom) // 2],
                               "bounds": [left, top, right, bottom], "started_at_ns": time.time_ns()})
        save_report()
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def screenshot(name: str) -> None:
        path = args.output / (name + ".png")
        path.write_bytes(adb("exec-out", "screencap", "-p", binary=True))
        report["artifacts"][name + "_screenshot"] = path.name

    def snapshot(name: str, nodes: list[dict[str, str]] | None = None) -> list[dict[str, str]]:
        if nodes is None:
            nodes = ui()
        path = args.output / (name + "-ui.json")
        path.write_text(json.dumps(nodes, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        report["artifacts"][name + "_ui"] = path.name
        screenshot(name)
        return nodes

    def check(name: str) -> None:
        report["checks"].append({"name": name, "passed": True})
        save_report()

    def launch() -> None:
        adb("shell", "am", "start", "-W", "-n", args.package + "/" + args.activity)

    def scroll_to(identifier: str) -> dict[str, str]:
        def current(nodes: list[dict[str, str]]) -> dict[str, str] | None:
            node = find(identifier, nodes)
            if node and visible(node):
                left, top, right, bottom = bounds(node)
                if bottom - top >= 48 * density - 1:
                    return node
            return None

        def swipe(*, to_top: bool) -> None:
            nodes = ui()
            scroll = next((n for n in nodes if n.get("class") == "android.widget.ScrollView"
                           and visible(n)), None)
            assert scroll is not None, "Settings must expose its scroll container"
            left, top, right, bottom = bounds(scroll)
            x = (left + right) // 2
            height = bottom - top
            start, end = (.28, .80) if to_top else (.80, .28)
            adb("shell", "input", "swipe", str(x), str(top + int(height * start)),
                str(x), str(top + int(height * end)), "300")

        existing = current(ui())
        if existing is not None:
            return existing
        # Dialog focus can leave the scroll position below the desired control.
        for _ in range(4):
            swipe(to_top=True)
        for _ in range(12):
            node = current(ui())
            if node is not None:
                return node
            swipe(to_top=False)
        node = current(ui())
        if node is not None:
            return node
        raise AssertionError(f"Could not scroll to {identifier} at 200% font scale")

    def settle_page(required: tuple[str, ...], hidden: tuple[str, ...],
                    *, timeout: float = 15) -> list[dict[str, str]]:
        deadline = time.monotonic() + timeout
        missing: list[str] = []
        exposed: list[str] = []
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            if remaining < 3:
                break
            try:
                nodes = ui(timeout=min(12, remaining))
            except subprocess.TimeoutExpired as exc:
                raise AssertionError(f"Accessibility snapshot exceeded the {timeout}s page-settle deadline") from exc
            missing = [identifier for identifier in required
                       if (node := find(identifier, nodes)) is None or not visible(node)]
            exposed = [identifier for identifier in hidden if find(identifier, nodes) is not None]
            if not missing and not exposed:
                return nodes
            # Poll the composite state: accessibility caches can lag page visibility.
            time.sleep(min(.2, max(0, deadline - time.monotonic())))
        raise AssertionError(f"Page did not settle within {timeout}s; missing={missing}, exposed={exposed}")

    try:
        report["serial"] = adb("get-serialno").strip()
        if args.serial:
            assert report["serial"] == args.serial, "Connected device serial does not match --serial"
        emulator_properties = {
            prop: adb("shell", "getprop", prop).strip()
            for prop in ("ro.kernel.qemu", "ro.boot.qemu")
        }
        report["emulator_properties"] = emulator_properties
        is_emulator = "1" in emulator_properties.values()
        report["device_kind"] = "emulator" if is_emulator else "physical"
        assert is_emulator or args.allow_physical_device, (
            "This script changes system font scale; physical devices require --allow-physical-device and --serial")
        apk_paths = [line.removeprefix("package:")
                     for line in adb("shell", "pm", "path", args.package).splitlines()
                     if line.startswith("package:")]
        assert apk_paths, f"No installed APK found for {args.package}"
        report["installed_apks"] = []
        for apk_path in apk_paths:
            hash_output = adb("shell", shlex.join(["sha256sum", apk_path])).strip()
            match = re.match(r"^([0-9a-fA-F]{64})\s", hash_output)
            assert match, f"Could not hash installed APK: {apk_path}"
            report["installed_apks"].append({"path": apk_path, "sha256": match.group(1).lower()})
        base_apk = next((apk for apk in report["installed_apks"] if apk["path"].endswith("/base.apk")), None)
        if base_apk is None and len(report["installed_apks"]) == 1:
            base_apk = report["installed_apks"][0]
        assert base_apk is not None, "Could not identify the installed base APK"
        report["installed_apk_sha256"] = base_apk["sha256"]
        if args.expected_apk_sha256:
            assert base_apk["sha256"] == args.expected_apk_sha256.lower(), (
                f"Installed APK SHA-256 mismatch: expected {args.expected_apk_sha256.lower()}, got {base_apk['sha256']}")
        density_values = re.findall(r"(?:Physical|Override) density:\s*(\d+)", adb("shell", "wm", "density"))
        sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", adb("shell", "wm", "size"))
        assert density_values and sizes, "Could not determine device display size/density"
        density = int(density_values[-1]) / 160
        screen_width, screen_height = map(int, sizes[-1])
        report["display"] = {"width_pixels": screen_width, "height_pixels": screen_height,
                             "density_dpi": int(density_values[-1])}
        original_font_scale = adb("shell", "settings", "get", "system", "font_scale").strip()
        report["font_scale_original"] = original_font_scale
        # Mark before dispatch so a command that applied but timed out is restored.
        font_scale_changed = True
        adb("shell", "settings", "put", "system", "font_scale", "2.0")
        actual_font_scale = adb("shell", "settings", "get", "system", "font_scale").strip()
        assert float(actual_font_scale) == 2.0, "Device did not apply font_scale=2.0"
        report["font_scale_applied"] = actual_font_scale
        adb("shell", "am", "force-stop", args.package)
        launch()
        test_launched = True
        phase("camera actions")
        wait("shutter", timeout=40)
        nodes = snapshot("camera")
        for identifier in ("shutter", "open_settings", "switch_camera", "last_photo"):
            node = find(identifier, nodes)
            assert node is not None, f"Camera lacks {identifier} at 200% font scale"
            target(identifier, node, "camera")
        check("camera primary actions are visible, labelled and at least 48dp")

        phase("open settings")
        tap(wait("open_settings"))
        wait("settings_back")
        nodes = settle_page(("settings_back",), ("shutter", "switch_camera", "last_photo"))
        snapshot("settings", nodes)
        target("settings_back", wait("settings_back"), "settings")
        phase("settings privacy entry")
        privacy = scroll_to("settings_privacy")
        target("settings_privacy", privacy, "settings")
        snapshot("settings-privacy-entry")
        phase("open privacy dialog")
        tap(privacy)
        wait("button1")
        snapshot("privacy")
        phase("dismiss privacy dialog")
        tap(wait("button1"))
        settle_page(("settings_back",), ("button1", "shutter", "last_photo"))
        snapshot("settings-before-back")
        phase("settings back to camera")
        tap(wait("settings_back"))
        wait("shutter")
        settle_page(("shutter",), ("settings_back",))
        check("settings scroll reaches privacy and returns to the camera")

        phase("gallery to photo review")
        tap(wait("last_photo"))
        try:
            item = wait("gallery_item", timeout=30)
        except AssertionError as exc:
            snapshot("gallery")
            raise AssertionError("This smoke test needs an existing saved gallery photo; it never takes one") from exc
        nodes = settle_page(("gallery_back", "gallery_item"), ("shutter", "switch_camera", "open_settings"))
        snapshot("gallery", nodes)
        target("gallery_back", wait("gallery_back"), "gallery")
        target("gallery_item", item, "gallery")
        tap(item)
        photo = wait("review_photo")
        assert photo.get("content-desc"), "Review photo needs an accessible description"
        nodes = settle_page(("review_photo", "review_back", "review_continue"),
                            ("shutter", "gallery_back", "gallery_item", "open_settings"))
        snapshot("review", nodes)
        for identifier in ("review_back", "review_continue"):
            target(identifier, wait(identifier), "review")
        phase("review back to gallery")
        tap(wait("review_back"))
        wait("gallery_item")
        settle_page(("gallery_back", "gallery_item"), ("review_back", "review_continue", "shutter"))
        phase("gallery back to camera")
        tap(wait("gallery_back"))
        wait("shutter")
        settle_page(("shutter",), ("gallery_back", "review_back"))
        check("gallery photo review and both page back buttons work at 200% font scale")

        phase("open review for continue")
        tap(wait("last_photo"))
        tap(wait("gallery_item"))
        wait("review_photo")
        settle_page(("review_photo", "review_back", "review_continue"),
                    ("gallery_back", "gallery_item", "shutter"))
        phase("review continue to camera")
        tap(wait("review_continue"))
        wait("shutter")
        settle_page(("shutter",), ("review_back", "review_continue", "gallery_back"))
        check("review continue returns directly to the camera without taking a photo")
        report["passed"] = True
    except (Exception, KeyboardInterrupt) as exc:
        report["failure"] = f"{type(exc).__name__}: {exc}"
        (args.output / "failure-traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
        (args.output / "failure-last-ui.json").write_text(json.dumps(last_nodes, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        if test_launched:
            try:
                snapshot("failure")
            except Exception as artifact_error:
                report["failure_artifact_error"] = str(artifact_error)
    finally:
        if font_scale_changed and original_font_scale is not None:
            try:
                if original_font_scale == "null":
                    adb("shell", "settings", "delete", "system", "font_scale")
                else:
                    adb("shell", "settings", "put", "system", "font_scale", original_font_scale)
                restored = adb("shell", "settings", "get", "system", "font_scale").strip()
                assert restored == original_font_scale, (
                    f"font_scale restore mismatch: expected {original_font_scale}, got {restored}")
                report["font_scale_restored"] = True
                report["font_scale_after"] = restored
            except Exception as restore_error:
                report["passed"] = False
                report["font_scale_restored"] = False
                report["font_scale_restore_error"] = str(restore_error)
            if report.get("font_scale_restored") and test_launched:
                try:
                    # Recreate the app with its original configuration and close QA pages.
                    adb("shell", "am", "force-stop", args.package)
                    launch()
                except Exception as launch_error:
                    report["passed"] = False
                    report["post_restore_launch_error"] = str(launch_error)
        if dump_created:
            try:
                adb("shell", "rm", "-f", dump_path)
            except Exception as cleanup_error:
                report["dump_cleanup_error"] = str(cleanup_error)
        save_report()
    print(json.dumps(report, indent=2, ensure_ascii=False))
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
