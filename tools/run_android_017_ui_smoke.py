#!/usr/bin/env python3
"""Emulator-only 0.17 UI smoke against an already installed, hash-verified APK.

Requires CAMERA already granted and at least one existing app photo. Uses only
visible UI, never taps a shutter/purchase/delete-confirm button, never grants a
permission, and never sends a share. Temporarily changes font scale to 100/200%,
restoring its original value and the initial free style in finally. Existing
MediaStore IDs, permission grants and locale must remain unchanged. Reuses the
bounded, fresh UIAutomator dumps/evidence helpers of run_android_billing_smoke.
Does not test checkout, successful deletion, pinch/multitouch, or pixel quality.
"""
from __future__ import annotations

import argparse
import json
import re
import time
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

from run_android_billing_smoke import CAMERA_HIDDEN, FREE, PAID, Smoke


class UiSmoke(Smoke):
    def __init__(self, args):
        super().__init__(args)
        variant = Path(__file__).resolve().parents[1] / "android/sample/src" / args.channel / "res"
        for locale in ("values", "values-zh-rCN"):
            for path in (variant / locale).glob("*.xml"):
                for node in ET.parse(path).getroot():
                    if node.tag == "string" and node.text:
                        self.strings.setdefault(node.attrib["name"], set()).add(node.text)
        self.report.update({
            "store_channel": args.channel,
            "scope": "0.17 emulator UI: adjustments, trial-first unlock, 200% fonts, privacy, existing photos",
            "photos_taken": 0, "photos_deleted": 0, "shares_sent": 0,
            "not_covered": ["checkout or purchase success", "successful photo deletion or system delete consent",
                            "pinch/multitouch", "visual pixel correctness", "physical devices"],
        })

    def ui(self, **kwargs):
        # Retry only a transient failure to collect a fresh tree. Never reuse a
        # stale dump or turn an unmet UI expectation into a pass.
        for attempt in range(3):
            try:
                return super().ui(**kwargs)
            except AssertionError as exc:
                if not str(exc).startswith("UIAutomator did not write a fresh hierarchy:"):
                    raise
                self.report.setdefault("hierarchy_collection_retries", []).append({
                    "phase": self.phase, "attempt": attempt + 1, "failure": str(exc),
                })
                self.save()
                if attempt == 2:
                    raise
                time.sleep(.5)

    def tap(self, node, **kwargs):
        identifier = node.get("resource-id", "").rsplit("/", 1)[-1]
        assert identifier not in ("shutter", "purchase_buy"), "Capture/checkout taps are forbidden"
        assert not self.label_matches(node.get("text", ""), "photo_delete_confirm"), "Delete confirmation is forbidden"
        super().tap(node, **kwargs)

    def camera(self, style=None, *, trial=False):
        def ready(nodes):
            shutter = self.find("shutter", nodes)
            selected = [key for key in FREE + PAID if (n := self.find(key, nodes)) and n.get("selected") == "true"]
            unlock = self.find("camera_unlock_style", nodes)
            return shutter is not None and shutter.get("enabled") == "true" and len(selected) == 1 \
                and (style is None or selected == [style]) and self.find("purchase_page", nodes) is None \
                and ((unlock is not None and self.visible(unlock)) == trial) \
                and (self.label_matches(shutter.get("content-desc", ""), "billing_unlock_to_shoot") == trial)
        return self.poll(ready, f"Camera did not reach style={style}, trial={trial}", timeout=60)

    def number(self, identifier):
        text = self.wait(identifier).get("text", "")
        value = re.search(r"[-+]?\d+(?:[.,]\d+)?", text)
        assert value, f"No numeric value in {identifier}: {text}"
        return float(value.group().replace(",", "."))

    def adjustment(self, identifier, font):
        self.tap(self.wait(identifier))
        slider = self.wait("adjustment_slider")
        done = self.wait("adjustment_done")
        assert slider.get("class") == "android.widget.SeekBar", "Adjustment slider is not exposed"
        self.target(identifier, self.wait(identifier), record=f"font{font}/{identifier}")
        self.swipe(slider, to_start=False, horizontal=True)
        self.snapshot(f"font{font}-{identifier}-panel")
        if identifier == "exposure_value":
            self.tap(self.wait("adjustment_reset"))
            assert abs(self.number(identifier)) < .01, "EV reset did not return to zero"
        else:
            # Zoom's reset is its 1× shortcut, rather than an unsupported lens preset.
            slider_bottom = self.bounds(slider)[3]
            nodes = self.ui()
            reset = next((n for n in nodes if n.get("clickable") == "true" and
                          re.fullmatch(r"1[.,]0×", n.get("text", "")) and
                          self.bounds(n)[1] >= slider_bottom and self.bounds(n)[3] <= self.bounds(done)[3]
                          and self.visible(n)), None)
            assert reset, "Hardware-supported 1× zoom shortcut is missing"
            self.tap(reset)
            assert abs(self.number(identifier) - 1) < .01, "Zoom shortcut did not restore 1×"
        self.tap(self.wait("adjustment_done"))
        self.poll(lambda ns: self.find("adjustment_slider", ns) is None, "Adjustment panel did not close")
        self.check(f"font {font}: {identifier} opens slider and restores default")

    def privacy(self):
        self.tap(self.wait("open_settings"))
        self.tap(self.scroll_to("settings_privacy"))
        nodes = self.poll(lambda ns: any(self.label_matches(n.get("text", ""), "product_privacy_title")
                                        for n in ns), "Privacy title is not accessible")
        assert any(len(n.get("text", "")) > 160 for n in nodes), "Privacy policy body is missing"
        self.snapshot("font2-privacy", nodes)
        self.tap(self.wait("button1"))
        self.tap(self.wait("settings_back"))
        self.camera()
        self.check("200%: settings privacy title and policy body are accessible")

    def photos(self):
        self.tap(self.wait("last_photo"))
        self.wait("gallery_back")
        nodes = self.poll(lambda ns: any(n.get("resource-id", "").endswith(":id/gallery_item")
                                        and self.visible(n) for n in ns),
                          "No existing app photo appeared in the gallery", timeout=40)
        item = next((n for n in nodes if n.get("resource-id", "").endswith(":id/gallery_item")
                     and self.visible(n)), None)
        assert item, "Seed at least one existing app photo before running; this script never captures"
        self.snapshot("font2-gallery", nodes)
        self.tap(item)
        photo = self.wait("review_photo", timeout=40)
        controls = ("review_previous", "review_next", "review_share", "review_delete", "review_continue")
        nodes = self.ui()
        for identifier in controls:
            node = self.find(identifier, nodes)
            assert node and self.visible(node), f"{identifier} is missing or clipped at 200% fonts"
            if identifier not in ("review_previous", "review_next") or node.get("enabled") == "true":
                self.target(identifier, node, record="font2/" + identifier)
            else:
                assert node.get("content-desc"), f"Disabled {identifier} needs an accessible label"
                left, top, right, bottom = self.bounds(node)
                assert min(right-left, bottom-top) >= 48 * self.density - 1, f"Disabled {identifier} target is below 48dp"
        self.snapshot("font2-review", nodes)
        # Two rapid taps on the image only; no app action or system share target.
        left, top, right, bottom = self.bounds(photo)
        point = [str((left + right) // 2), str((top + bottom) // 2)]
        start = time.monotonic()
        self.adb("shell", "input", "tap", *point)
        # Stay above GestureDetector's minimum double-tap interval as well as
        # below its maximum; back-to-back ADB taps can be too fast to qualify.
        time.sleep(.1)
        self.adb("shell", "input", "tap", *point)
        self.report["double_tap_dispatch_seconds"] = round(time.monotonic() - start, 3)
        self.wait("review_zoom_reset")
        self.snapshot("font2-review-zoom")
        self.tap(self.wait("review_zoom_reset"))
        self.poll(lambda ns: self.find("review_zoom_reset", ns) is None, "Photo fit/reset did not clear zoom")
        self.check("200%: photo double tap zoom and fit/reset work")
        nodes = self.ui()
        direction = next((key for key in ("review_next", "review_previous") if
                          (n := self.find(key, nodes)) and n.get("enabled") == "true"), None)
        if direction:
            before = self.wait("review_photo").get("content-desc")
            self.tap(self.wait(direction))
            self.wait("review_share", timeout=40)
            opposite = "review_previous" if direction == "review_next" else "review_next"
            self.wait(opposite)
            self.snapshot("font2-review-neighbor")
            self.check("200%: a neighboring existing photo opens", direction=direction,
                       previous_description=before)
            self.tap(self.wait(opposite))
            self.wait("review_share", timeout=40)
        else:
            self.report["not_covered"].append("actual previous/next navigation: only one photo available")
            self.check("200%: previous/next controls remain visible and disabled at a single-photo boundary")
        self.tap(self.wait("review_delete"))
        cancel = self.wait("button2")
        assert self.label_matches(cancel.get("text", ""), "photo_delete_cancel"), "Delete dialog cancel is not identified"
        self.snapshot("font2-delete-confirmation")
        self.tap(cancel)
        self.wait("review_share")
        self.check("200%: delete confirmation can be canceled without deletion")
        self.tap(self.wait("review_share"))
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
        self.wait("review_share")
        self.check("200%: share opens the system chooser and Back returns without sending")
        self.tap(self.wait("review_continue"))
        self.camera()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.ycolor.team.phytoy.camera.android.gpapp")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), default="play")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--output", type=Path, default=Path("reports/release_candidate_0_17/ui-smoke"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only explicit emulator-NNNN serials are allowed; physical devices are forbidden")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid activity name")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("Expected APK SHA-256 must be 64 hexadecimal characters")
    smoke = UiSmoke(args)
    font_original = initial_style = media_before = permissions_before = locale_before = None
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
        paths = [line[8:] for line in smoke.adb("shell", "pm", "path", args.package).splitlines() if line.startswith("package:")]
        assert paths, "App is not installed"
        apks = [{"path": path, "sha256": smoke.shell("sha256sum", path).split()[0]} for path in paths]
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
        assert media_before, "Seed an app photo before running; this test never creates one"
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial_style = next(key for key in FREE if (node := smoke.find(key, nodes)) and node.get("selected") == "true")
        smoke.report["initial_visible_style"] = initial_style
        for font in ("1.0", "2.0"):
            smoke.phase = "font " + font
            font_changed = True
            smoke.adb("shell", "settings", "put", "system", "font_scale", font)
            assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == font, "Font scale not applied"
            smoke.restart()
            smoke.camera()
            smoke.adjustment("exposure_value", font)
            smoke.adjustment("zoom_ratio", font)
            smoke.tap(smoke.style_target(PAID[0]))
            smoke.snapshot("font" + font + "-trial", smoke.camera(PAID[0], trial=True))
            smoke.target("camera_unlock_style", smoke.wait("camera_unlock_style"), record="font" + font + "/unlock")
            smoke.tap(smoke.wait("camera_unlock_style"))
            smoke.snapshot("font" + font + "-purchase", smoke.purchase())
            for identifier in ("purchase_back", "purchase_preview", "purchase_restore"):
                smoke.target(identifier, smoke.scroll_to(identifier), record="font" + font + "/" + identifier)
            smoke.tap(smoke.wait("purchase_back"))
            smoke.camera(PAID[0], trial=True)
            smoke.tap(smoke.style_target(initial_style))
            smoke.camera(initial_style)
            smoke.check(f"font {font}: locked style previews directly; explicit unlock opens a dismissible purchase page")
        smoke.phase = "four unconfigured paid cameras"
        for style in PAID:
            smoke.tap(smoke.style_target(style))
            smoke.camera(style, trial=True)
            smoke.tap(smoke.wait("camera_unlock_style"))
            smoke.unconfigured("unconfigured-" + style)
            smoke.tap(smoke.wait("purchase_back"))
            smoke.camera(style, trial=True)
            smoke.check("Unconfigured " + style + ": free preview, locked capture and disabled checkout")
        smoke.tap(smoke.style_target(initial_style))
        smoke.camera(initial_style)
        smoke.privacy()
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
        try:
            if media_before is not None:
                assert smoke.media("final") == media_before, "MediaStore photo IDs changed"
                smoke.report["photos_changed"] = False
            if permissions_before is not None:
                assert smoke.permissions() == permissions_before, "Permission grants changed"
                smoke.report["permissions_unchanged"] = True
            if locale_before is not None:
                assert smoke.adb("shell", "getprop", "persist.sys.locale").strip() == locale_before, "Locale changed"
                smoke.report["system_locale_unchanged"] = True
            if smoke.dump_created:
                smoke.shell("rm", "-f", smoke.dump)
        except Exception as exc:
            cleanup_errors.append("final invariants: " + str(exc))
        if cleanup_errors:
            smoke.report["cleanup_errors"] = cleanup_errors
            smoke.report["passed"] = False
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json")}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
