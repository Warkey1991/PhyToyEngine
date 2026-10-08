#!/usr/bin/env python3
"""Regression for the redesigned ToviCam UI on an explicitly selected emulator.

Requires an already installed APK with a supplied SHA-256, CAMERA already
granted, an initially selected free camera, and two existing app photos. Never
captures, purchases, confirms deletion, grants permissions, chooses a share
recipient, writes private preferences, or operates a physical device. Font scale
and the initial visible free style are restored in finally. MediaStore IDs and
photo bytes, permissions, and locale must remain unchanged.

Uses visible UI and fresh UIAutomator dumps. Checks adjustments and trial/detail
at 100/200% fonts, then gallery/review at 200%. Controls may scroll into view;
interactive targets must still be fully visible and at least 48dp when tapped.
Does not claim purchase success, successful deletion, pinch/multitouch, capture
quality, complete settings coverage, or visual/pixel correctness.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import time
import traceback
from pathlib import Path

from run_android_017_ui_smoke import UiSmoke
from run_android_billing_smoke import CAMERA_HIDDEN, FREE, PAID


class RedesignSmoke(UiSmoke):
    def __init__(self, args):
        super().__init__(args)
        self.report.update({
            "scope": "UI redesign: directional EV/zoom, trial/detail, grouped gallery, nested settings, review gestures/actions",
            "not_covered": [
                "checkout or purchase success", "successful photo deletion or system delete consent",
                "pinch/multitouch", "capture timing, output quality, EXIF or engine correctness",
                "visual pixel correctness", "physical devices", "all paid cameras and entitlement states",
                "complete settings operations and privacy document content",
            ],
            "photos_taken": 0, "photos_deleted": 0, "shares_sent": 0,
        })

    def accessible_target(self, identifier, *, record=None):
        node = self.scroll_to(identifier)
        self.target(identifier, node, record=record)
        return node

    def scroll_to(self, identifier, *, interactive=True):
        """Search fresh body/dock geometry, down first, then up at a boundary.

        The compact purchase body and CTA rail can share their top edge. Review
        can have nested rails. Every local long scroll stays inside a visible,
        at-least-48dp region; slider/gesture helpers keep their original swipes.
        """
        def usable(nodes):
            node = self.find(identifier, nodes)
            if node is None or not self.visible(node):
                return None
            if interactive:
                left, top, right, bottom = self.bounds(node)
                if min(right - left, bottom - top) < 48 * self.density - 1:
                    return None
            return node

        found = usable(self.ui())
        if found is not None:
            return found
        search = {"phase": self.phase, "target": identifier, "budget_per_direction": 40, "steps": []}
        self.report.setdefault("scroll_searches", []).append(search)
        for to_start in (False, True):
            previous, unchanged = None, 0
            for step in range(40):
                nodes = self.ui()
                found = usable(nodes)
                if found is not None:
                    search["found"] = True
                    self.save()
                    return found
                all_nodes = self.ui(include_unimportant=True)
                scrolls = [node for node in all_nodes
                           if node.get("class", "").endswith("ScrollView")
                           and "Horizontal" not in node.get("class", "") and self.visible(node)]
                if identifier.startswith("adjustment_"):
                    panel = self.find("adjustment_panel", all_nodes)
                    assert panel is not None, "Adjustment panel geometry unavailable"
                    pl, pt, pr, pb = self.bounds(panel)
                    scrolls = [node for node in scrolls if (b := self.bounds(node))[0] >= pl and b[2] <= pr
                               and b[1] >= pt and b[3] <= pb]
                assert scrolls, f"No visible scroll region can reveal {identifier}"
                if identifier in ("purchase_buy", "purchase_preview"):
                    scroll = max(scrolls, key=lambda node: (self.bounds(node)[1], self.bounds(node)[0]))
                elif identifier.startswith("review_"):
                    scroll = max(scrolls, key=lambda node: (self.bounds(node)[2] - self.bounds(node)[0]) *
                                 (self.bounds(node)[3] - self.bounds(node)[1]))
                else:
                    scroll = min(scrolls, key=lambda node: (self.bounds(node)[1], self.bounds(node)[0]))
                # Use every exposed control, not only the requested ID, to
                # distinguish actual progress from reaching a scroll boundary.
                signature = tuple((node.get("resource-id"), node.get("text"), node.get("bounds")) for node in nodes)
                unchanged = unchanged + 1 if signature == previous else 0
                previous = signature
                if unchanged >= 2:
                    search["steps"].append({"to_start": to_start, "step": step, "boundary": True})
                    self.save()
                    break
                left, top, right, bottom = self.bounds(scroll)
                assert min(right - left, bottom - top) >= 48 * self.density - 1, (
                    f"Scroll region for {identifier} is clipped or below 48dp")
                start, end = (.2, .8) if to_start else (.8, .2)
                x = (left + right) // 2
                y1, y2 = top + int((bottom - top) * start), top + int((bottom - top) * end)
                gesture = {"phase": self.phase, "kind": "scroll_to", "target": identifier,
                           "container_bounds": [left, top, right, bottom], "from": [x, y1], "to": [x, y2]}
                self.report.setdefault("gestures", []).append(gesture)
                search["steps"].append({"to_start": to_start, "step": step, "bounds": gesture["container_bounds"]})
                self.report["last_action"] = f"scroll {identifier} {x},{y1} to {x},{y2}"
                self.save()
                self.adb("shell", "input", "swipe", str(x), str(y1), str(x), str(y2), "650")
        raise AssertionError(f"Could not scroll a complete target into view: {identifier}")

    def adjustment(self, identifier, font):
        before = self.number(identifier)
        self.target(identifier, self.wait(identifier), record=f"font{font}/{identifier}")
        self.tap(self.wait(identifier))
        slider = self.wait("adjustment_slider")
        assert slider.get("class") == "android.widget.SeekBar", "Native adjustment range is not exposed"
        left, top, right, bottom = self.bounds(slider)
        horizontal = right - left >= bottom - top
        self.swipe(slider, to_start=False, horizontal=horizontal)
        nodes = self.poll(lambda ns: (node := self.find(identifier, ns)) is not None and
                          abs(self.node_number(node) - before) > .01,
                          f"{identifier} did not change its actual camera value")
        changed = self.node_number(self.find(identifier, nodes))
        self.snapshot(f"font{font}-{identifier}-changed", nodes)
        if identifier == "exposure_value":
            self.tap(self.accessible_target("adjustment_reset"))
            self.poll(lambda ns: (node := self.find(identifier, ns)) is not None and
                      abs(self.node_number(node)) < .01, "EV reset did not return to zero")
        else:
            nodes = self.ui()
            panel = self.find("adjustment_panel", nodes)
            if panel is None:
                # The root frame owns no action; compressed trees may omit it.
                panel = self.find("adjustment_panel", self.ui(include_unimportant=True))
            assert panel is not None, "Zoom panel is missing"
            panel_bounds = self.bounds(panel)
            reset = next((node for node in nodes if node.get("clickable") == "true" and
                          re.fullmatch(r"1[.,]0×", node.get("text", "")) and self.visible(node) and
                          self.bounds(node)[0] >= panel_bounds[0] and self.bounds(node)[2] <= panel_bounds[2] and
                          self.bounds(node)[1] >= panel_bounds[1] and self.bounds(node)[3] <= panel_bounds[3]), None)
            assert reset is not None, "Hardware-supported 1× zoom shortcut is missing"
            self.tap(reset)
            self.poll(lambda ns: (node := self.find(identifier, ns)) is not None and
                      abs(self.node_number(node) - 1) < .01, "Zoom reset did not return to 1×")
        self.tap(self.accessible_target("adjustment_done"))
        self.poll(lambda ns: self.find("adjustment_slider", ns) is None, "Adjustment panel did not close")
        self.check(f"font {font}: {identifier} changes the camera value and resets",
                   before=before, changed=changed, slider_orientation="horizontal" if horizontal else "vertical")

    @staticmethod
    def node_number(node):
        value = re.search(r"[-+]?\d+(?:[.,]\d+)?", node.get("text", ""))
        assert value is not None, f"No real numeric value in {node}"
        return float(value.group().replace(",", "."))

    def trial_details(self, font, initial_style):
        self.tap(self.style_target(PAID[0]))
        self.snapshot(f"font{font}-trial", self.camera(PAID[0], trial=True))
        self.tap(self.accessible_target("camera_unlock_style", record=f"font{font}/unlock"))
        self.snapshot(f"font{font}-detail", self.purchase())
        # This dedicated regression build is deliberately unconfigured. The
        # existing hard safety guard rejects purchase taps even when disabled.
        status = self.scroll_to("purchase_status", interactive=False)
        assert self.label_matches(status.get("text", ""), "billing_not_configured"), (
            "UI smoke requires an unconfigured build; do not exercise checkout")
        buy = self.scroll_to("purchase_buy")
        assert buy.get("enabled") == "false", "Unconfigured checkout must be disabled"
        assert self.label_matches(buy.get("text", ""), "purchase_buy_unavailable"), "Unexpected checkout state"
        for identifier in ("purchase_preview", "purchase_restore"):
            self.accessible_target(identifier, record=f"font{font}/{identifier}")
        self.tap(self.accessible_target("purchase_back", record=f"font{font}/purchase_back"))
        self.camera(PAID[0], trial=True)
        self.tap(self.style_target(initial_style))
        self.camera(initial_style)
        self.check(f"font {font}: locked style trials directly and detail dismisses with disabled checkout")

    @staticmethod
    def position(nodes):
        matches = [re.fullmatch(r"\s*(\d+)\s*/\s*(\d+)\s*", node.get("text", "")) for node in nodes]
        found = next((match for match in matches if match is not None), None)
        assert found is not None, "The review has no real photo position/count"
        result = tuple(map(int, found.groups()))
        assert 1 <= result[0] <= result[1], f"Invalid review position: {result}"
        return result

    def review_ready(self, expected_position=None):
        def ready(nodes):
            node = self.find("review_photo", nodes)
            if node is None or not self.visible(node) or not node.get("content-desc"):
                return False
            if expected_position is not None and self.position(nodes) != expected_position:
                return False
            return not any(self.label_matches(item.get("text", ""), "photo_loading") for item in nodes)
        nodes = self.poll(ready, f"Photo did not load at position {expected_position}", timeout=40)
        assert all(self.find(identifier, nodes) is None for identifier in CAMERA_HIDDEN), "Camera leaked through the review modal"
        return nodes

    def image_gesture_target(self):
        nodes = self.review_ready()
        photo = self.find("review_photo", nodes)
        assert photo is not None and photo.get("resource-id", "").endswith(":id/review_photo")
        left, top, right, bottom = self.bounds(photo)
        assert min(right - left, bottom - top) >= 48 * self.density - 1, "Photo gesture area is too small"
        # Raw coordinate gestures are allowed only within this exact image.
        return photo

    def photo_info(self, name):
        self.tap(self.accessible_target("review_info", record=name + "/info"))
        nodes = self.poll(lambda ns: any(self.label_matches(node.get("text", ""), "review_metadata_title")
                                        for node in ns), "Photo information dialog did not open")
        text = "\n".join(node.get("text", "") for node in nodes)
        filenames = re.findall(r"PT_\d{8}_\d{6}_\d{3}\.[A-Za-z0-9]+", text)
        assert len(set(filenames)) == 1, "Information must identify the actual seeded photo filename"
        assert re.search(r"\d+\s*×\s*\d+", text), "Actual photo dimensions are missing"
        done = self.wait("button1")
        assert self.label_matches(done.get("text", ""), "review_metadata_close"), "Info close button is not identified"
        self.snapshot(name, nodes)
        self.tap(done)
        self.review_ready()
        return filenames[0]

    def photos(self):
        self.tap(self.wait("last_photo"))
        nodes = self.poll(lambda ns: self.find("gallery_back", ns) is not None and
                          any(node.get("resource-id", "").endswith(":id/gallery_item") and self.visible(node)
                              for node in ns), "Existing seeded photos did not appear", timeout=40)
        assert all(self.find(identifier, nodes) is None for identifier in CAMERA_HIDDEN), "Camera leaked through gallery modal"
        headings = [node.get("text", "") for node in nodes
                    if node.get("class") == "android.widget.TextView" and not node.get("resource-id") and
                    not node.get("clickable") == "true" and self.visible(node) and
                    re.search(r"(?:19|20)\d{2}", node.get("text", "")) and len(node.get("text", "")) < 80]
        assert headings, "No actual date heading is exposed above the gallery rows"
        self.snapshot("font2-gallery", nodes)
        settings_tab = self.wait("gallery_tab_settings")
        self.target("gallery_tab_settings", settings_tab, record="font2/gallery-settings-tab")
        self.tap(settings_tab)
        self.settle(("settings_back",), CAMERA_HIDDEN + ("gallery_back",))
        for identifier in ("settings_quality", "settings_haptics", "settings_restore_purchases"):
            self.accessible_target(identifier, record="font2/" + identifier)
            self.snapshot("font2-" + identifier)
        self.tap(self.wait("settings_back"))
        nodes = self.poll(lambda ns: self.find("gallery_back", ns) is not None and
                          self.find("settings_back", ns) is None, "Settings Back did not return to gallery")
        self.snapshot("font2-gallery-after-settings", nodes)
        self.check("200%: dated gallery rows and settings navigation preserve the gallery", date_headings=headings)
        item = next((node for node in nodes if node.get("resource-id", "").endswith(":id/gallery_item")
                     and self.visible(node)), None)
        assert item is not None, "Existing gallery item disappeared after settings"
        self.tap(item)
        nodes = self.review_ready()
        origin = self.position(nodes)
        assert origin[1] >= 2, "Two seeded photos are required for actual adjacent-photo regression"
        self.snapshot("font2-review", nodes)
        for identifier in ("review_previous", "review_next"):
            node = self.scroll_to(identifier)
            if node.get("enabled") == "true":
                self.target(identifier, node, record="font2/" + identifier)
            else:
                left, top, right, bottom = self.bounds(node)
                assert node.get("content-desc"), f"Disabled {identifier} has no accessible label"
                assert min(right - left, bottom - top) >= 48 * self.density - 1, f"Disabled {identifier} is below 48dp"
                self.report["touch_targets"]["font2/" + identifier] = {
                    "id": identifier, "enabled": False, "bounds": [left, top, right, bottom],
                    "width_dp": round((right - left) / self.density, 2),
                    "height_dp": round((bottom - top) / self.density, 2), "label": node["content-desc"],
                }
        origin_file = self.photo_info("font2-review-info")
        direction = 1 if origin[0] < origin[1] else -1
        neighbor = (origin[0] + direction, origin[1])
        self.swipe(self.image_gesture_target(), to_start=direction < 0, horizontal=True)
        self.review_ready(neighbor)
        neighbor_file = self.photo_info("font2-review-neighbor-info")
        assert neighbor_file != origin_file, "Swipe changed the counter but did not load a different photo"
        self.swipe(self.image_gesture_target(), to_start=direction > 0, horizontal=True)
        self.review_ready(origin)
        restored_file = self.photo_info("font2-review-origin-info")
        assert restored_file == origin_file, "Opposite swipe did not restore the original photo"
        self.check("200%: left/right image swipes load actual adjacent photos and return",
                   origin_position=origin, neighbor_position=neighbor, origin_file=origin_file, neighbor_file=neighbor_file)

        photo = self.image_gesture_target()
        left, top, right, bottom = self.bounds(photo)
        point = [str((left + right) // 2), str((top + bottom) // 2)]
        started = time.monotonic()
        self.adb("shell", "input", "tap", *point)
        time.sleep(.1)
        self.adb("shell", "input", "tap", *point)
        self.report["double_tap_dispatch_seconds"] = round(time.monotonic() - started, 3)
        self.wait("review_zoom_reset")
        self.snapshot("font2-review-zoom")
        self.swipe(self.image_gesture_target(), to_start=direction < 0, horizontal=True)
        nodes = self.review_ready(origin)
        assert self.find("review_zoom_reset", nodes) is not None, "Drag unexpectedly reset the enlarged image"
        assert self.photo_info("font2-review-zoom-drag-info") == origin_file, "Enlarged-image panning switched photos"
        self.tap(self.accessible_target("review_zoom_reset", record="font2/review_zoom_reset"))
        self.poll(lambda ns: self.find("review_zoom_reset", ns) is None, "Fit photo did not restore the unzoomed state")
        self.check("200%: double tap enlarges, horizontal drag preserves the photo, and fit resets")

        self.tap(self.accessible_target("review_delete", record="font2/review_delete"))
        cancel = self.wait("button2")
        assert self.label_matches(cancel.get("text", ""), "photo_delete_cancel"), "Delete cancel button is not identified"
        self.snapshot("font2-delete-cancel-dialog")
        self.tap(cancel)
        self.review_ready(origin)
        self.check("200%: confirmed deletion is never dispatched; cancel returns to the same photo")
        self.tap(self.accessible_target("review_share", record="font2/review_share"))
        deadline = time.monotonic() + 15
        resumed = ""
        while time.monotonic() < deadline:
            resumed = "\n".join(line for line in self.adb("shell", "dumpsys", "activity", "activities").splitlines()
                                if "mResumedActivity" in line or "topResumedActivity" in line)
            if re.search(r"ChooserActivity|ResolverActivity", resumed):
                break
            time.sleep(.2)
        assert re.search(r"ChooserActivity|ResolverActivity", resumed), "System share chooser did not open"
        self.report["share_foreground_activity"] = resumed
        self.snapshot("font2-share-chooser")
        self.adb("shell", "input", "keyevent", "KEYCODE_BACK")
        self.review_ready(origin)
        self.check("200%: share opens the chooser and Back returns without sending")
        self.tap(self.accessible_target("review_continue", record="font2/review_continue"))
        self.camera()
        self.snapshot("font2-continue-camera")
        self.check("200%: Continue shooting returns to the camera without taking a photo")

    def media_hashes(self, ids, stage):
        hashes = {}
        for identifier in ids:
            uri = f"content://media/external/images/media/{identifier}"
            data = self.adb("exec-out", "content", "read", "--uri", uri, binary=True)
            assert data.startswith(b"\xff\xd8"), f"Seeded JPEG {identifier} could not be read safely"
            hashes[str(identifier)] = hashlib.sha256(data).hexdigest()
        self.report[stage + "_photo_sha256"] = hashes
        self.save()
        return hashes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.ycolor.team.phytoy.camera.android.gpapp")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), default="play")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("reports/ui_redesign/smoke"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only explicit emulator-NNNN serials are allowed; physical devices are forbidden")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid activity name")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("Expected APK SHA-256 must be 64 hexadecimal characters")
    smoke = RedesignSmoke(args)
    font_original = initial_style = media_before = hashes_before = permissions_before = locale_before = None
    font_changed = launched = False
    try:
        assert smoke.adb("get-serialno").strip() == args.serial, "Connected serial does not match"
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
        assert base["sha256"] == args.expected_apk_sha256.lower(), "Installed APK does not match the expected build"
        permissions_before = smoke.permissions()
        assert permissions_before.get("android.permission.CAMERA") == "true", "Grant CAMERA before running this test"
        locale_before = smoke.adb("shell", "getprop", "persist.sys.locale").strip()
        font_original = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
        assert font_original == "null" or re.fullmatch(r"\d+(?:\.\d+)?", font_original), "Unexpected font scale"
        smoke.report["font_scale_original"] = font_original
        media_before = smoke.media("initial")
        assert len(media_before) >= 2, "Seed two existing app photos before running; this test never captures"
        hashes_before = smoke.media_hashes(media_before, "initial")
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial_style = next((key for key in FREE if (node := smoke.find(key, nodes)) and
                              node.get("selected") == "true"), None)
        assert initial_style is not None, "The initial visible camera must be free"
        smoke.report["initial_visible_style"] = initial_style
        for font in ("1.0", "2.0"):
            smoke.phase = "font " + font
            font_changed = True
            smoke.adb("shell", "settings", "put", "system", "font_scale", font)
            assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == font, "Font scale not applied"
            smoke.restart()
            smoke.snapshot("font" + font + "-camera", smoke.camera(initial_style))
            smoke.adjustment("exposure_value", font)
            smoke.adjustment("zoom_ratio", font)
            smoke.trial_details(font, initial_style)
        smoke.phase = "200% gallery and review"
        smoke.photos()
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
        if font_changed and font_original is not None:
            try:
                if font_original == "null":
                    smoke.adb("shell", "settings", "delete", "system", "font_scale")
                else:
                    smoke.adb("shell", "settings", "put", "system", "font_scale", font_original)
                after = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
                smoke.report["font_scale_restored"] = after == font_original
                assert after == font_original, "Original font scale not restored"
            except Exception as exc:
                cleanup_errors.append("font restore: " + str(exc))
        if launched and initial_style:
            try:
                smoke.restart()
                smoke.camera()
                smoke.tap(smoke.style_target(initial_style))
                smoke.camera(initial_style)
                smoke.report["initial_visible_style_restored"] = True
            except Exception as exc:
                cleanup_errors.append("style restore: " + str(exc))
        # Independent checks still run if another invariant or restoration fails.
        if media_before is not None:
            try:
                assert smoke.media("final") == media_before, "MediaStore photo IDs changed"
                smoke.report["photos_changed"] = False
                if hashes_before is not None:
                    assert smoke.media_hashes(media_before, "final") == hashes_before, "Existing photo bytes changed"
                    smoke.report["photo_bytes_unchanged"] = True
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
                cleanup_errors.append("scratch dump cleanup: " + str(exc))
        if cleanup_errors:
            smoke.report["cleanup_errors"] = cleanup_errors
            smoke.report["passed"] = False
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json")}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
