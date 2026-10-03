#!/usr/bin/env python3
"""Exercise an installed release-equivalent camera using stable UI resource IDs.

No app data or saved photos are deleted. Camera permission is temporarily revoked
and restored. Run on a dedicated test device; output includes screen captures.
"""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial")
    parser.add_argument("--package", default="com.phytoy.sample")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--output", type=Path, default=Path("reports/product_release_smoke"))
    parser.add_argument("--lifecycle-cycles", type=int, default=5)
    parser.add_argument("--check-library-settings", action="store_true")
    parser.add_argument("--skip-captures", action="store_true",
                        help="Reuse prior captures when checking library/settings; does not verify six styles")
    parser.add_argument("--free-cameras-only", action="store_true",
                        help="Capture only the two free camera styles, leaving paid checkout to license testing")
    args = parser.parse_args()
    if not 1 <= args.lifecycle_cycles <= 100:
        parser.error("--lifecycle-cycles must be in 1..100")
    args.output.mkdir(parents=True, exist_ok=True)
    prefix = [args.adb] + (["-s", args.serial] if args.serial else [])
    report: dict = {"passed": False, "package": args.package, "checks": [], "styles": [],
                    "six_style_capture_skipped": args.skip_captures or args.free_cameras_only,
                    "free_cameras_only": args.free_cameras_only}
    settings_restore: dict[str, bool | str] | None = None

    def adb(*command: str, binary: bool = False):
        result = subprocess.run(prefix + list(command), capture_output=True,
                                text=not binary, timeout=35)
        if result.returncode:
            detail = result.stderr.decode(errors="replace") if binary else result.stderr
            raise RuntimeError(f"ADB {command[0]} failed: {detail.strip() or 'device connection lost'}")
        return result.stdout

    def ui(*, include_unimportant: bool = False) -> list[dict[str, str]]:
        # The uncompressed dump opts into INCLUDE_NOT_IMPORTANT_VIEWS and
        # deliberately exposes controls hidden from accessibility by a modal.
        options = [] if include_unimportant else ["--compressed"]
        adb("shell", "uiautomator", "dump", *options, "/sdcard/phytoy_product_qa.xml")
        xml = adb("exec-out", "cat", "/sdcard/phytoy_product_qa.xml")
        return [node.attrib for node in ET.fromstring(xml).iter("node")]

    def find(identifier: str, nodes: list[dict[str, str]]) -> dict[str, str] | None:
        return next((n for n in nodes if n.get("resource-id", "").endswith(":id/" + identifier)), None)

    def wait(identifier: str, *, enabled: bool = False, timeout: float = 30) -> dict[str, str]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            node = find(identifier, ui())
            if node and (not enabled or node.get("enabled") == "true"):
                return node
            time.sleep(0.3)
        raise AssertionError(f"Timed out waiting for {identifier}, enabled={enabled}")

    def tap(node: dict[str, str]) -> None:
        left, top, right, bottom = map(int, re.findall(r"\d+", node["bounds"]))
        if right <= left or bottom <= top:
            raise AssertionError("Control has no visible touch target")
        adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))

    def screenshot(name: str) -> None:
        (args.output / (name + ".png")).write_bytes(adb("exec-out", "screencap", "-p", binary=True))

    def launch() -> None:
        adb("shell", "am", "start", "-W", "-n", args.package + "/" + args.activity)

    def check(name: str) -> None:
        report["checks"].append({"name": name, "passed": True})
        (args.output / "evaluation.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")

    def settle_review() -> None:
        deadline = time.monotonic() + 15
        while True:
            nodes = ui()
            if all(find(identifier, nodes) is not None for identifier in
                   ("review_photo", "review_back", "review_continue")) and all(
                       find(identifier, nodes) is None for identifier in
                       ("gallery_back", "gallery_item", "shutter")):
                return
            assert time.monotonic() < deadline, "Review modal did not settle with its navigation controls"

    def capture(name: str) -> None:
        tap(wait("shutter", enabled=True))
        photo = wait("review_photo", timeout=40)
        assert photo.get("content-desc"), "Saved photo needs an accessible description"
        # Accessibility events invalidate UIAutomator's cache asynchronously.
        # Require a settled modal tree, and retain a failure if the background
        # remains reachable after the bounded transition window.
        deadline = time.monotonic() + 5
        while True:
            nodes = ui()
            if find("review_photo", nodes) and find("shutter", nodes) is None:
                break
            assert time.monotonic() < deadline, "Review must isolate camera controls from accessibility"
        screenshot(name)
        tap(wait("review_continue", enabled=True))
        wait("shutter", enabled=True)

    def settings_control(identifier: str) -> dict[str, str]:
        control = find(identifier, ui())
        if control:
            return control
        # A dialog/focus restoration can leave the scroll view below a requested
        # row. Start from its top before searching downward, on any screen size.
        for _ in range(4):
            scroll = next(n for n in ui() if n.get("class") == "android.widget.ScrollView")
            left, top, right, bottom = map(int, re.findall(r"\d+", scroll["bounds"]))
            x = (left + right) // 2
            inset = max(30, (bottom - top) // 8)
            adb("shell", "input", "swipe", str(x), str(top + inset),
                str(x), str(bottom - inset), "250")
        for _ in range(12):
            nodes = ui()
            control = find(identifier, nodes)
            if control:
                return control
            scroll = next(n for n in nodes if n.get("class") == "android.widget.ScrollView")
            left, top, right, bottom = map(int, re.findall(r"\d+", scroll["bounds"]))
            x = (left + right) // 2
            height = bottom - top
            # Short, slow drags keep adjacent viewports overlapping. Fast
            # flings can jump over a row between two accessibility dumps.
            adb("shell", "input", "swipe", str(x), str(top + height * 2 // 3),
                str(x), str(top + height // 3), "650")
        raise AssertionError(f"Could not scroll to {identifier}")


    try:
        base_apk = next(line[8:] for line in adb("shell", "pm", "path", args.package).splitlines()
                        if line.startswith("package:") and line.endswith("/base.apk"))
        report["installed_apk_sha256"] = adb("shell", "sha256sum", base_apk).split()[0]
        adb("shell", "pm", "grant", args.package, "android.permission.CAMERA")
        adb("shell", "am", "force-stop", args.package)
        launch()
        wait("shutter", enabled=True)
        screenshot("camera")
        check("camera opens and shutter becomes ready")

        assert find("about_privacy", ui()) is None, "Privacy must live in settings"
        tap(wait("open_settings", enabled=True))
        tap(settings_control("settings_privacy"))
        assert len(ui()) > 0
        screenshot("privacy")
        tap(wait("button1", enabled=True))
        tap(wait("settings_back", enabled=True))
        wait("shutter", enabled=True)
        check("local privacy text is accessible")

        for cycle in range(args.lifecycle_cycles):
            adb("shell", "input", "keyevent", "KEYCODE_HOME")
            time.sleep(0.5)
            launch()
            wait("shutter", enabled=True)
        check(f"{args.lifecycle_cycles} background/resume cycles")

        if not args.skip_captures:
            tap(wait("switch_camera", enabled=True))
            wait("shutter", enabled=True)
            capture("front-photo-review")
            tap(wait("switch_camera", enabled=True))
            wait("shutter", enabled=True)
            check("front and back camera with a saved front photo")

            # Restore the left edge even when the app remembered a style near the end.
            for _ in range(3):
                rail = next(n for n in ui(include_unimportant=True) if n.get("class") == "android.widget.HorizontalScrollView")
                left, top, right, bottom = map(int, re.findall(r"\d+", rail["bounds"]))
                edge = max(60, (right - left) // 5)
                adb("shell", "input", "swipe", str(left + edge), str((top + bottom) // 2),
                    str(right - edge), str((top + bottom) // 2), "250")
            styles = ("style_dh_color", "style_dh_mono") if args.free_cameras_only else (
                "style_dh_color", "style_dh_mono", "style_digital", "style_plastic", "style_street", "style_fisheye")
            for style in styles:
                target = None
                for _ in range(5):
                    nodes = ui()
                    target = find(style, nodes)
                    if target:
                        break
                    rail = next(n for n in ui(include_unimportant=True) if n.get("class") == "android.widget.HorizontalScrollView")
                    left, top, right, bottom = map(int, re.findall(r"\d+", rail["bounds"]))
                    edge = max(60, (right - left) // 5)
                    adb("shell", "input", "swipe", str(right - edge), str((top + bottom) // 2),
                        str(left + edge), str((top + bottom) // 2), "250")
                assert target, f"Could not reach {style}"
                tap(target)
                wait("shutter", enabled=True)
                capture(style + "-review")
                report["styles"].append({"id": style, "saved_and_reviewed": True})
            check("both free profiles switch, save, review and return to shooting" if args.free_cameras_only
                  else "all six profiles switch, save, review and return to shooting")

        if args.check_library_settings:
            def gallery_count() -> int:
                count = wait("gallery_count")
                match = re.search(r"\d+", count.get("text", ""))
                assert match, "Gallery needs an accessible photo count"
                return int(match.group())

            tap(wait("last_photo", enabled=True))
            tap(wait("gallery_item", enabled=True))
            wait("review_photo")
            settle_review()
            assert find("gallery_back", ui()) is None, "Photo review must isolate the gallery"
            tap(wait("review_back", enabled=True))
            wait("gallery_item")
            count_before = gallery_count()
            minimum_photos = 1 if args.skip_captures else (3 if args.free_cameras_only else 7)
            assert count_before >= minimum_photos, "Gallery must retain the photos captured in this test scope"
            screenshot("gallery")
            tap(wait("gallery_item", enabled=True))
            wait("review_photo")
            settle_review()
            screenshot("gallery-review-continue")
            tap(wait("review_continue", enabled=True))
            wait("shutter", enabled=True)
            assert find("gallery_back", ui()) is None
            check("photo list retains captures, opens a photo and supports both return paths")

            tap(wait("open_settings", enabled=True))
            wait("settings_quality")
            screenshot("settings-camera")
            tap(wait("settings_quality", enabled=True))
            options = [n for n in ui() if n.get("class") == "android.widget.CheckedTextView"]
            original_quality = next(n["text"] for n in options if n.get("checked") == "true")
            assert len(options) == 3, "All three quality choices must be visible"
            compact_label = options[2]["text"]
            settings_restore = {"settings_quality": original_quality}
            tap(options[2])
            original_grid = settings_control("settings_grid").get("checked") == "true"
            original_review = settings_control("settings_review").get("checked") == "true"
            settings_restore.update({"settings_grid": original_grid, "settings_review": original_review})
            # Force known test values, then restore the original values below.
            tap(wait("settings_back", enabled=True))
            tap(wait("open_settings", enabled=True))
            grid = settings_control("settings_grid")
            if grid.get("checked") != "true": tap(grid)
            review_option = settings_control("settings_review")
            if review_option.get("checked") != "false": tap(review_option)
            screenshot("settings-shooting")
            tap(wait("settings_back", enabled=True))
            adb("shell", "am", "force-stop", args.package)
            launch()
            wait("shutter", enabled=True)
            screenshot("camera-grid")
            tap(wait("open_settings", enabled=True))
            assert compact_label in wait("settings_quality").get("text", "")
            assert settings_control("settings_grid").get("checked") == "true"
            assert settings_control("settings_review").get("checked") == "false"
            tap(wait("settings_back", enabled=True))
            tap(wait("shutter", enabled=True))
            time.sleep(1)
            wait("shutter", enabled=True, timeout=40)
            assert find("review_photo", ui()) is None, "Review-off must keep the camera open"
            tap(wait("last_photo", enabled=True))
            wait("gallery_item")
            assert gallery_count() > count_before, "Review-off capture must still enter the photo list"
            tap(wait("gallery_back", enabled=True))
            wait("shutter", enabled=True)
            tap(wait("open_settings", enabled=True))
            tap(wait("settings_quality", enabled=True))
            tap(next(n for n in ui() if n.get("class") == "android.widget.CheckedTextView"
                     and n.get("text") == original_quality))
            grid = settings_control("settings_grid")
            if (grid.get("checked") == "true") != original_grid: tap(grid)
            review_option = settings_control("settings_review")
            if (review_option.get("checked") == "true") != original_review: tap(review_option)
            tap(wait("settings_back", enabled=True))
            wait("shutter", enabled=True)
            settings_restore = None
            check("settings persist across process restart and review-off captures remain in the gallery")

        adb("shell", "pm", "revoke", args.package, "android.permission.CAMERA")
        # Permission revocation kills the app asynchronously. Do not race the
        # previous process/activity by starting a new one before it is gone.
        adb("shell", "am", "force-stop", args.package)
        deadline = time.monotonic() + 10
        while args.package in adb("shell", "ps", "-A", "-o", "NAME").splitlines():
            assert time.monotonic() < deadline, "Revoked camera process did not terminate"
            time.sleep(0.2)
        launch()
        # A fresh install may first display the local explanation. Declining it
        # must also leave a visible route back to permission settings.
        explanation_decline = find("button2", ui())
        if explanation_decline:
            tap(explanation_decline)
        wait("camera_recovery", enabled=True)
        shutter = find("shutter", ui())
        assert shutter and shutter.get("enabled") == "false"
        screenshot("permission-recovery")
        check("denied camera permission has a recovery action and disabled shutter")
        adb("shell", "pm", "grant", args.package, "android.permission.CAMERA")
        adb("shell", "am", "force-stop", args.package)
        launch()
        wait("shutter", enabled=True)
        check("camera recovers after permission is restored")
        report["passed"] = True
    except Exception as exc:
        report["failure"] = f"{type(exc).__name__}: {exc}"
        try:
            screenshot("failure")
            (args.output / "failure-ui.json").write_text(json.dumps(ui(), indent=2, ensure_ascii=False))
        except Exception:
            pass
        raise
    finally:
        if settings_restore is not None:
            try:
                adb("shell", "am", "force-stop", args.package)
                launch()
                tap(wait("open_settings", enabled=True, timeout=10))
                for identifier, value in settings_restore.items():
                    if identifier == "settings_quality":
                        tap(wait(identifier, enabled=True))
                        tap(next(n for n in ui() if n.get("class") == "android.widget.CheckedTextView"
                                 and n.get("text") == value))
                        continue
                    control = settings_control(identifier)
                    if (control.get("checked") == "true") != value: tap(control)
                tap(wait("settings_back", enabled=True))
            except Exception as exc:
                report["settings_restore_failure"] = str(exc)
        # Leave the user's camera permission usable even when a QA assertion fails.
        subprocess.run(prefix + ["shell", "pm", "grant", args.package, "android.permission.CAMERA"],
                       capture_output=True, timeout=20)
        (args.output / "evaluation.json").write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    print(json.dumps(report, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
