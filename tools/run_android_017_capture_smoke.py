#!/usr/bin/env python3
"""Hash-verified emulator-only camera geometry and one disposable mono photo.

Requires CAMERA already granted, automatic after-capture review enabled, no
existing mono JPEG (verified from EXIF), and explicit --allow-disposable-photo.
Checks 200% fonts at 640x360/640x320dp before
capturing exactly once, rotates that direct review, and deletes only that photo
through its confirmation dialog. Never enters purchase or system delete consent.
Restores font/rotation/wm overrides and the initial visible free style in finally.
On failure, preserve evidence and any new photo; never guess a cleanup target.
"""
from __future__ import annotations

import argparse
import hashlib
import itertools
import json
import re
import struct
import time
import traceback
from pathlib import Path

from run_android_017_ui_smoke import UiSmoke
from run_android_billing_smoke import FREE


CONTROLS = ("shutter", "open_settings", "flash_mode", "last_photo",
            "switch_camera", "exposure_value", "zoom_ratio")
MONO = "style_dh_mono"
MONO_CODE = "DH/M"
STYLE_CODES = ("DH2", MONO_CODE, "D01", "P82", "S84", "F05")
SYSTEM_KEYS = ("font_scale", "accelerometer_rotation", "user_rotation")


def jpeg_style_exif(jpeg):
    """Read only the three identity tags written by PhotoStore; no dependencies."""
    assert jpeg[:2] == b"\xff\xd8", "MediaStore did not return a JPEG"
    position = 2
    tiff = None
    while position < len(jpeg):
        assert jpeg[position] == 0xFF, "Invalid JPEG marker"
        while position < len(jpeg) and jpeg[position] == 0xFF:
            position += 1
        assert position < len(jpeg), "Truncated JPEG marker"
        marker = jpeg[position]
        position += 1
        if marker in (0xDA, 0xD9):
            break
        if marker == 0x01 or 0xD0 <= marker <= 0xD7:
            continue
        assert position + 2 <= len(jpeg), "Truncated JPEG segment"
        length = int.from_bytes(jpeg[position:position+2], "big")
        assert length >= 2 and position + length <= len(jpeg), "Invalid JPEG segment length"
        payload = jpeg[position+2:position+length]
        if marker == 0xE1 and payload.startswith(b"Exif\0\0"):
            assert tiff is None, "Ambiguous duplicate JPEG EXIF"
            tiff = payload[6:]
        position += length
    assert tiff is not None and len(tiff) >= 8, "JPEG has no usable EXIF"
    assert tiff[:2] in (b"II", b"MM"), "Unsupported TIFF byte order"
    endian = "little" if tiff[:2] == b"II" else "big"

    def number(offset, size):
        assert 0 <= offset <= len(tiff)-size, "EXIF offset outside segment"
        return int.from_bytes(tiff[offset:offset+size], endian)

    def directory(offset):
        count = number(offset, 2)
        assert offset + 2 + count * 12 + 4 <= len(tiff), "Truncated EXIF directory"
        result = {}
        for index in range(count):
            entry = offset + 2 + index * 12
            tag, kind, count = number(entry, 2), number(entry+2, 2), number(entry+4, 4)
            if tag not in (0x010E, 0x0131, 0x8769, 0x9286):
                continue
            assert tag not in result, "Duplicate EXIF identity tag"
            if tag == 0x8769:
                assert kind == 4 and count == 1, "Invalid EXIF subdirectory pointer"
                result[tag] = number(entry+8, 4)
            else:
                assert kind in (2, 7) and count > 0, "Invalid EXIF identity value"
                value_offset = entry+8 if count <= 4 else number(entry+8, 4)
                assert 0 <= value_offset <= len(tiff)-count, "Truncated EXIF identity value"
                result[tag] = tiff[value_offset:value_offset+count]
        return result

    assert number(2, 2) == 42, "Invalid TIFF header"
    tags = directory(number(4, 4))
    assert 0x8769 in tags, "Missing EXIF comment subdirectory"
    tags.update(directory(tags[0x8769]))
    values = {}
    for tag, name in ((0x010E, "image_description"), (0x0131, "software"), (0x9286, "user_comment")):
        value = tags.get(tag)
        assert isinstance(value, bytes), "Missing EXIF identity tag: " + name
        if tag == 0x9286 and value.startswith(b"ASCII\0\0\0"):
            value = value[8:]
        values[name] = value.rstrip(b"\0").decode("ascii")
    codes = re.findall(r"(?:^|;\s*)Style=([^;]+)(?:;|$)", values["user_comment"])
    assert len(codes) == 1 and codes[0] in STYLE_CODES, "Unknown or ambiguous EXIF camera style"
    code = codes[0]
    for name in ("image_description", "software"):
        assert re.search(r"(?:^|\s)" + re.escape(code) + r"\s+\S+", values[name]), "EXIF style tags disagree: " + name
    return {"style_code": code, **values}


class CaptureSmoke(UiSmoke):
    def __init__(self, args):
        super().__init__(args)
        self.initial_ids = None
        self.new_id = None
        self.initial_exif = {}
        self.capture_log_before = set()
        self.report.update({
            "scope": "0.17 emulator: 200% landscape camera geometry, one disposable DH mono capture, rotation, delete",
            "allow_disposable_photo": args.allow_disposable_photo,
            "expected_apk_sha256": args.expected_apk_sha256.lower(),
            "capture_taps": 0, "delete_confirm_taps": 0,
            "not_covered": ["physical devices", "purchases", "system delete consent",
                            "sharing or sending", "pixel quality", "pinch/multitouch"],
        })

    def dimensions(self, expected=None, name=None):
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline:
            png = self.adb("exec-out", "screencap", "-p", binary=True)
            assert png[:8] == b"\x89PNG\r\n\x1a\n", "Screenshot is not PNG"
            self.width, self.height = struct.unpack(">II", png[16:24])
            if expected is None or (self.width, self.height) == expected:
                if name:
                    path = self.output / (name + "-display.png")
                    path.write_bytes(png)
                    self.report["artifacts"][name + "_display"] = path.name
                return
            time.sleep(.25)
        raise AssertionError(f"Display {(self.width, self.height)} did not reach {expected}")

    def geometry(self, height_dp):
        self.phase = f"camera 640x{height_dp}dp, font 200%"
        self.adb("shell", "wm", "size", f"{height_dp * 3}x1920")
        self.dimensions((1920, height_dp * 3), f"camera-640x{height_dp}")
        nodes = self.camera(MONO)
        rectangles = {}
        for identifier in CONTROLS:
            node = self.find(identifier, nodes)
            assert node is not None and self.visible(node), f"{identifier} missing or clipped"
            left, top, right, bottom = self.bounds(node)
            assert min(right-left, bottom-top) >= 48 * self.density - 1, f"{identifier} below 48dp"
            assert node.get("text") or node.get("content-desc"), f"{identifier} lacks a label"
            rectangles[identifier] = [left, top, right, bottom]
            self.report["touch_targets"][f"640x{height_dp}/{identifier}"] = {
                "bounds": rectangles[identifier], "enabled": node.get("enabled"),
                "width_dp": round((right-left) / self.density, 2),
                "height_dp": round((bottom-top) / self.density, 2),
            }
        overlaps = []
        for (a, ar), (b, br) in itertools.combinations(rectangles.items(), 2):
            if min(ar[2], br[2]) > max(ar[0], br[0]) and min(ar[3], br[3]) > max(ar[1], br[1]):
                overlaps.append([a, b])
        self.snapshot(f"camera-640x{height_dp}-font2", nodes)
        self.report.setdefault("geometry", []).append({"size_dp": [640, height_dp],
                                                       "bounds": rectangles, "overlaps": overlaps})
        self.save()
        assert not overlaps, "Camera touch targets overlap: " + repr(overlaps)
        self.check(f"640x{height_dp}dp/200%: seven camera targets >=48dp, no overlap")

    def disposable_tap(self, node, action):
        """The only permitted exceptions to UiSmoke's capture/delete tap guard."""
        assert self.args.allow_disposable_photo and self.initial_ids is not None
        identifier = node.get("resource-id", "").rsplit("/", 1)[-1]
        assert node.get("package") == self.args.package, "Disposable action is outside the tested app"
        if action == "capture":
            assert identifier == "shutter" and self.report["capture_taps"] == 0
            nodes = self.camera(MONO)
            fresh = self.find("shutter", nodes)
            assert fresh and self.bounds(fresh) == self.bounds(node), "Stale shutter target"
            assert self.label_matches(fresh.get("content-desc", ""), "shutter_description")
            assert self.media("before-capture") == self.initial_ids, "Media changed before capture"
            assert set(self.initial_exif) == set(self.initial_ids), "Initial JPEG identity evidence is incomplete"
            assert all(e["style_code"] != MONO_CODE for e in self.initial_exif.values()), "An initial mono photo exists"
            pid = self.shell("pidof", self.args.package).strip()
            assert re.fullmatch(r"\d+", pid), "Expected exactly one app process"
            self.report["capture_pid"] = int(pid)
            self.report["capture_device_epoch"] = int(self.shell("date", "+%s").strip())
            before_log = self.shell("logcat", "-d", "-v", "epoch", "-s", "PhyToySample:I", "*:S")
            self.capture_log_before = set(before_log.splitlines())
            (self.output / "before-capture-logcat.txt").write_text(before_log, encoding="utf-8")
            self.report["artifacts"]["before_capture_logcat"] = "before-capture-logcat.txt"
        else:
            assert action == "delete" and identifier == "button1"
            assert self.label_matches(node.get("text", ""), "photo_delete_confirm")
            assert self.new_id is not None and self.new_id not in self.initial_ids
            assert self.media("before-delete") == tuple(sorted(self.initial_ids + (self.new_id,))), "Unexpected MediaStore delta"
            assert self.report.get("direct_new_photo_review_verified"), "Deletion target chain was not verified"
            assert self.report.get("unique_mono_identity_verified"), "Unique mono JPEG/log identity was not verified"
            assert self.report["new_photo_exif"]["style_code"] == MONO_CODE
            assert self.report["saved_log_evidence"]["media_id"] == self.new_id
            fresh = self.find("button1", self.ui())
            assert fresh and fresh.get("package") == self.args.package and self.bounds(fresh) == self.bounds(node), "Stale or external delete confirmation"
            assert self.label_matches(fresh.get("text", ""), "photo_delete_confirm")
        self.target(identifier, node)
        left, top, right, bottom = self.bounds(node)
        self.report.setdefault("taps", []).append({"phase": self.phase, "id": identifier,
            "disposable_action": action, "new_photo_id": self.new_id,
            "bounds": [left, top, right, bottom], "started_at_ns": time.time_ns()})
        self.save()
        self.adb("shell", "input", "tap", str((left+right)//2), str((top+bottom)//2))
        self.report["capture_taps" if action == "capture" else "delete_confirm_taps"] = 1
        self.save()

    def photo_exif(self, media_id, stage):
        uri = f"content://media/external/images/media/{media_id}"
        jpeg = self.adb("exec-out", "content", "read", "--uri", uri, binary=True)
        evidence = {"media_id": media_id, "uri": uri, "jpeg_bytes": len(jpeg),
                    "jpeg_sha256": hashlib.sha256(jpeg).hexdigest()}
        path = self.output / f"{stage}-{media_id}-exif.json"
        try:
            evidence.update(jpeg_style_exif(jpeg))
        except (AssertionError, UnicodeDecodeError) as error:
            evidence["exif_error"] = str(error)
            raise
        finally:
            path.write_text(json.dumps(evidence, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
            self.report["artifacts"][f"{stage}_{media_id}_exif"] = path.name
            self.save()
        return evidence

    def saved_log(self):
        deadline = time.monotonic() + 20
        pattern = re.compile(r"^\s*(\d+(?:\.\d+)?)\s+(\d+)\s+\d+\s+I\s+PhyToySample\s*:\s+(\S+) photo saved: (content://media/(?:external|external_primary)/images/media/(\d+))\s*$")
        while time.monotonic() < deadline:
            raw = self.shell("logcat", "-d", "-v", "epoch", "-s", "PhyToySample:I", "*:S")
            (self.output / "after-capture-logcat.txt").write_text(raw, encoding="utf-8")
            matches = []
            for line in raw.splitlines():
                match = pattern.fullmatch(line)
                if match and line not in self.capture_log_before and int(match[2]) == self.report["capture_pid"] and float(match[1]) >= self.report["capture_device_epoch"]:
                    matches.append({"epoch": match[1], "pid": int(match[2]), "style_code": match[3],
                                    "uri": match[4], "media_id": int(match[5]), "line": line})
            assert len(matches) <= 1, "More than one capture save event appeared"
            if matches:
                evidence = matches[0]
                assert evidence["style_code"] == MONO_CODE and evidence["media_id"] == self.new_id, "Capture log URI/style does not match the only new JPEG"
                self.report["artifacts"]["after_capture_logcat"] = "after-capture-logcat.txt"
                self.report["saved_log_evidence"] = evidence
                self.save()
                return
            time.sleep(.25)
        raise AssertionError("No fresh mono photo saved URI event for this capture process")

    def media_until(self, expected, stage, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            ids = self.media(stage)
            assert set(self.initial_ids).issubset(ids), "An existing photo was deleted"
            assert len(set(ids) - set(self.initial_ids)) <= 1, "More than one new photo appeared"
            if expected(ids):
                return ids
            time.sleep(.5)
        raise AssertionError("MediaStore did not reach expected state: " + stage)

    def mono_review(self):
        photo = self.wait("review_photo", timeout=90)
        labels = self.strings["style_harinezumi_2pp_mono"]
        description = photo.get("content-desc", "")
        assert any(label in description for label in labels), "Review is not the new mono capture"
        assert any(n.get("text") in labels for n in self.ui()), "Mono review title is missing"
        # Short landscape action columns may scroll; an action is ready only
        # when its entire >=48dp target is visible and enabled.
        for identifier in ("review_previous", "review_next", "review_share", "review_delete", "review_continue"):
            node = self.scroll_to(identifier)
            left, top, right, bottom = self.bounds(node)
            assert min(right-left, bottom-top) >= 48 * self.density - 1, f"{identifier} clipped below 48dp"
            assert node.get("text") or node.get("content-desc"), f"{identifier} lacks a label"
            if identifier not in ("review_previous", "review_next"):
                assert node.get("enabled") == "true", f"{identifier} disabled"
            self.report["touch_targets"][f"review-{self.width}x{self.height}/{identifier}"] = {"bounds": [left, top, right, bottom]}
        self.check("Each review action can be brought fully into view at >=48dp", display_px=[self.width,self.height])
        return description


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.ycolor.team.phytoy.camera.android.gpapp")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), default="play")
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--allow-disposable-photo", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("reports/release_candidate_0_17/capture-smoke"))
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only explicit emulator-NNNN serials are allowed")
    if not args.allow_disposable_photo:
        parser.error("One photo will be captured and deleted; explicit --allow-disposable-photo is required")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid activity")
    if not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("Expected APK SHA-256 must be 64 hexadecimal characters")
    smoke = CaptureSmoke(args)
    original = permissions = locale = initial_style = None
    changed = launched = False
    try:
        assert smoke.adb("get-serialno").strip() == args.serial
        qemu = {k: smoke.adb("shell", "getprop", k).strip() for k in ("ro.kernel.qemu", "ro.boot.qemu")}
        assert "1" in qemu.values(), "Device is not an emulator"
        smoke.report["emulator_properties"] = qemu
        assert int(smoke.adb("shell", "getprop", "ro.build.version.sdk").strip()) >= 30
        paths = [s[8:] for s in smoke.adb("shell", "pm", "path", args.package).splitlines() if s.startswith("package:")]
        assert paths, "App is not installed"
        hashes = [{"path": p, "sha256": smoke.shell("sha256sum", p).split()[0]} for p in paths]
        base = next((h for h in hashes if h["path"].endswith("/base.apk")), hashes[0])
        smoke.report["installed_apks"] = hashes
        smoke.report["installed_apk_sha256"] = base["sha256"]
        assert base["sha256"] == args.expected_apk_sha256.lower(), "Installed APK hash mismatch"
        permissions = smoke.permissions()
        assert permissions.get("android.permission.CAMERA") == "true", "Grant CAMERA before this test"
        locale = smoke.adb("shell", "getprop", "persist.sys.locale").strip()
        size_raw = smoke.adb("shell", "wm", "size")
        density_raw = smoke.adb("shell", "wm", "density")
        original = {k: smoke.adb("shell", "settings", "get", "system", k).strip() for k in SYSTEM_KEYS}
        assert all(v == "null" or re.fullmatch(r"\d+(?:\.\d+)?", v) for v in original.values())
        original["wm_size"] = (re.findall(r"Override size:\s*(\d+x\d+)", size_raw) or [None])[-1]
        original["wm_density"] = (re.findall(r"Override density:\s*(\d+)", density_raw) or [None])[-1]
        smoke.report["original_system_state"] = original.copy()
        smoke.initial_ids = smoke.media("initial")
        for media_id in smoke.initial_ids:
            smoke.initial_exif[media_id] = smoke.photo_exif(media_id, "initial")
            smoke.report["initial_photo_exif"] = list(smoke.initial_exif.values())
            smoke.save()
            assert smoke.initial_exif[media_id]["style_code"] != MONO_CODE, "Initial mono JPEG would make deletion identity ambiguous"
        smoke.report["initial_photo_exif"] = list(smoke.initial_exif.values())
        smoke.check("Every initial JPEG has a recognized non-mono EXIF camera style")
        smoke.dimensions()
        densities = re.findall(r"(?:Physical|Override) density:\s*(\d+)", density_raw)
        assert densities, "Display density unavailable"
        smoke.density = int(densities[-1]) / 160
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial_style = next(s for s in FREE if (n := smoke.find(s, nodes)) and n.get("selected") == "true")
        changed = True
        smoke.adb("shell", "settings", "put", "system", "font_scale", "2.0")
        smoke.adb("shell", "settings", "put", "system", "accelerometer_rotation", "0")
        smoke.adb("shell", "settings", "put", "system", "user_rotation", "1")
        for key, value in (("font_scale", "2.0"), ("accelerometer_rotation", "0"), ("user_rotation", "1")):
            assert smoke.adb("shell", "settings", "get", "system", key).strip() == value, f"{key} not applied"
        smoke.adb("shell", "wm", "density", "480")
        smoke.density = 3.0
        smoke.adb("shell", "wm", "size", "1080x1920")
        smoke.dimensions((1920, 1080))
        smoke.restart()
        smoke.camera()
        smoke.tap(smoke.style_target(MONO))
        smoke.geometry(360)
        smoke.geometry(320)
        smoke.phase = "camera lifecycle after Home and lens changes"
        before_pid = smoke.adb("shell", "pidof", args.package).strip()
        assert before_pid, "App PID missing before Home"
        smoke.adb("shell", "input", "keyevent", "KEYCODE_HOME")
        smoke.adb("shell", "am", "start", "-W", "-n", f"{args.package}/{args.activity}")
        smoke.camera(MONO)
        assert smoke.adb("shell", "pidof", args.package).strip() == before_pid, "Home/Resume restarted the process"
        smoke.snapshot("camera-after-home-resume")
        smoke.check("Home/Resume reopens the mono camera in the same process")
        lens = smoke.wait("switch_camera", enabled=False)
        if lens.get("enabled") == "true":
            descriptions = []
            for _ in range(2):
                smoke.tap(smoke.wait("switch_camera"))
                smoke.camera(MONO)
                descriptions.append(smoke.wait("switch_camera").get("content-desc"))
            assert len(set(descriptions)) == 2, "Lens state did not change and return"
            smoke.snapshot("camera-after-front-back-switch")
            smoke.check("Front/back switching returns to a ready mono camera", lens_actions=descriptions)
        else:
            smoke.report["not_covered"].append("front/back switching: unavailable on this emulator")
        smoke.phase = "one disposable mono capture"
        smoke.disposable_tap(smoke.wait("shutter"), "capture")
        ids = smoke.media_until(lambda xs: len(xs) == len(smoke.initial_ids)+1, "after-capture")
        delta = set(ids) - set(smoke.initial_ids)
        assert len(delta) == 1, "Capture did not create exactly one new ID"
        smoke.new_id = delta.pop()
        smoke.report["photos_taken"] = 1
        smoke.report["new_photo_id"] = smoke.new_id
        # A queried ID may still be IS_PENDING. The saved callback follows EXIF
        # verification and publication, so read the JPEG only after that event.
        smoke.saved_log()
        smoke.report["new_photo_exif"] = smoke.photo_exif(smoke.new_id, "new")
        assert smoke.report["new_photo_exif"]["style_code"] == MONO_CODE, "The only new JPEG is not mono"
        smoke.report["unique_mono_identity_verified"] = True
        smoke.check("Only new JPEG is mono; fresh saved log URI matches its MediaStore ID")
        before_description = smoke.mono_review()
        smoke.snapshot("new-mono-review-landscape")
        smoke.adb("shell", "settings", "put", "system", "user_rotation", "0")
        smoke.dimensions((960, 1920))
        assert smoke.mono_review() == before_description, "Rotation changed the reviewed mono photo"
        smoke.snapshot("new-mono-review-portrait")
        smoke.report["direct_new_photo_review_verified"] = True
        smoke.check("Only new MediaStore ID +1; direct mono review and title survive rotation", new_id=smoke.new_id)
        smoke.phase = "delete only newly captured mono photo"
        smoke.tap(smoke.wait("review_delete"))
        confirm = smoke.wait("button1")
        smoke.snapshot("new-mono-delete-confirmation")
        smoke.disposable_tap(confirm, "delete")
        smoke.media_until(lambda xs: xs == smoke.initial_ids, "after-delete")
        smoke.report["photos_deleted"] = 1
        smoke.check("Disposable photo deleted; initial app photo ID set exactly restored", new_id=smoke.new_id)
        smoke.report["passed"] = True
    except (Exception, KeyboardInterrupt) as exc:
        smoke.report["failure"] = f"{type(exc).__name__}: {exc}"
        (smoke.output / "failure-traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
        (smoke.output / "failure-ui.json").write_text(json.dumps(smoke.last_nodes, indent=2, ensure_ascii=False), encoding="utf-8")
        try:
            smoke.screenshot("failure")
        except Exception as error:
            smoke.report["failure_evidence_error"] = str(error)
    finally:
        errors = []
        if changed and original:
            # Independent restore attempts: one failure never skips other state.
            operations = [("wm_size", ("wm", "size", original["wm_size"] or "reset")),
                          ("wm_density", ("wm", "density", original["wm_density"] or "reset"))]
            operations += [(k, ("settings", "delete", "system", k) if original[k] == "null" else
                             ("settings", "put", "system", k, original[k])) for k in SYSTEM_KEYS]
            for key, command in operations:
                try:
                    smoke.adb("shell", *command)
                except Exception as error:
                    errors.append(f"restore {key}: {error}")
            for key in SYSTEM_KEYS:
                try:
                    assert smoke.adb("shell", "settings", "get", "system", key).strip() == original[key]
                except Exception as error:
                    errors.append(f"verify {key}: {error}")
            for key, command, pattern in (("wm_size", "size", r"Override size:\s*(\d+x\d+)"),
                                           ("wm_density", "density", r"Override density:\s*(\d+)")):
                try:
                    current = (re.findall(pattern, smoke.adb("shell", "wm", command)) or [None])[-1]
                    assert current == original[key], f"{current} != {original[key]}"
                except Exception as error:
                    errors.append(f"verify {key}: {error}")
        if launched and initial_style:
            try:
                smoke.dimensions()
                density = re.findall(r"(?:Physical|Override) density:\s*(\d+)", smoke.adb("shell", "wm", "density"))
                smoke.density = int(density[-1]) / 160
                smoke.restart()
                smoke.camera()
                smoke.tap(smoke.style_target(initial_style))
                smoke.camera(initial_style)
                smoke.report["initial_visible_style_restored"] = True
            except Exception as error:
                errors.append("restore style: " + str(error))
        if smoke.initial_ids is not None:
            try:
                final = smoke.media("final")
                smoke.report["final_photo_ids_match_initial"] = final == smoke.initial_ids
                assert final == smoke.initial_ids, "Final photo IDs differ; preserve new ID in report for manual review"
            except Exception as error:
                errors.append("final media invariant: " + str(error))
        if permissions is not None:
            try:
                assert smoke.permissions() == permissions, "Permission grants changed"
                smoke.report["permissions_unchanged"] = True
            except Exception as error:
                errors.append("final permissions invariant: " + str(error))
        if locale is not None:
            try:
                assert smoke.adb("shell", "getprop", "persist.sys.locale").strip() == locale, "Locale changed"
                smoke.report["system_locale_unchanged"] = True
            except Exception as error:
                errors.append("final locale invariant: " + str(error))
        if smoke.dump_created:
            try:
                smoke.shell("rm", "-f", smoke.dump)
            except Exception as error:
                errors.append("scratch XML cleanup: " + str(error))
        if errors:
            smoke.report["cleanup_errors"] = errors
            smoke.report["passed"] = False
        smoke.report["system_state_mutated"] = changed
        smoke.report["system_state_restored"] = not any("restore " in e or "verify " in e for e in errors)
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json")}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
