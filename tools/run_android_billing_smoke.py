#!/usr/bin/env python3
"""Exercise an installed, unconfigured billing RC on a dedicated Android device.

Requires CAMERA already granted. Never taps a purchase button, grants permissions,
writes private preferences, changes locale, or creates/deletes gallery photos.
Free style selections are temporary and the initial visible free style is restored
through UI. This cannot recover an inaccessible remembered paid value from an older
build, so the report distinguishes UI restoration from byte-for-byte preferences.
The final purchase-page checks use font_scale=2.0 and restore its exact original
system value in finally. This is not a Play checkout or successful-purchase test.
Physical devices require an explicit --allow-physical-device opt-in.
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


FREE = ("style_dh_color", "style_dh_mono")
PAID = ("style_digital", "style_plastic", "style_street", "style_fisheye")
STYLE_RESOURCES = dict(zip(FREE + PAID, (
    "style_harinezumi_2pp", "style_harinezumi_2pp_mono", "style_digital_01",
    "style_plastic_82", "style_street_84", "style_fisheye_05",
)))
CAMERA_HIDDEN = ("shutter", "open_settings", "switch_camera", "last_photo")


class Smoke:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.prefix = [args.adb, "-s", args.serial]
        self.output = args.output
        self.output.mkdir(parents=True, exist_ok=True)
        self.dump = f"/sdcard/phytoy_billing_qa_{time.time_ns()}.xml"
        self.dump_created = False
        self.last_nodes: list[dict[str, str]] = []
        self.width = self.height = 0
        self.density = 1.0
        self.phase = "prerequisites"
        self.report: dict = {
            "passed": False, "package": args.package, "serial": args.serial,
            "run_started_at_ns": time.time_ns(),
            "scope": "Installed unconfigured RC; no Play checkout or fake purchase success",
            "checks": [], "styles": [], "artifacts": {}, "touch_targets": {},
            "media_history": [], "purchase_button_taps": 0,
            "permission_mutations": 0, "private_preference_writes": 0,
            "locale_mutations": 0, "successful_purchase_tested": False,
            "preference_restore_scope": "Restore initial visible free style via UI; do not claim byte-for-byte private preference preservation",
        }
        resources = Path(__file__).resolve().parents[1] / "android/sample/src/main/res"
        self.strings: dict[str, set[str]] = {}
        for locale in ("values", "values-zh-rCN"):
            for path in (resources / locale).glob("*.xml"):
                for node in ET.parse(path).getroot():
                    if node.tag == "string" and node.text:
                        self.strings.setdefault(node.attrib["name"], set()).add(node.text)

    def adb(self, *command: str, binary: bool = False, timeout: float = 35):
        result = subprocess.run(self.prefix + list(command), capture_output=True,
                                text=not binary, timeout=timeout)
        if result.returncode:
            detail = result.stderr.decode(errors="replace") if binary else result.stderr
            raise RuntimeError(f"ADB {command[0]} failed: {detail.strip() or 'device unavailable'}")
        return result.stdout

    def shell(self, *tokens: str, timeout: float = 35) -> str:
        return self.adb("shell", shlex.join(tokens), timeout=timeout)

    def save(self) -> None:
        self.report["phase"] = self.phase
        (self.output / "evaluation.json").write_text(
            json.dumps(self.report, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")

    def check(self, name: str, **evidence) -> None:
        self.report["checks"].append({"name": name, "passed": True, **evidence})
        self.save()

    def label_matches(self, value: str, resource: str) -> bool:
        labels = self.strings.get(resource)
        assert labels, f"Missing source labels for {resource}; run this script from the matching repository"
        return value in labels

    def ui(self, *, timeout: float = 12, include_unimportant: bool = False) -> list[dict[str, str]]:
        deadline = time.monotonic() + timeout
        self.dump_created = True
        # Remove only this run's scratch XML: a failed dump must never reuse an old tree.
        self.shell("rm", "-f", self.dump, timeout=max(.1, deadline - time.monotonic()))
        options = [] if include_unimportant else ["--compressed"]
        result = self.adb("shell", "uiautomator", "dump", *options, self.dump,
                          timeout=max(.1, deadline - time.monotonic()))
        if not include_unimportant:
            (self.output / "last-ui-dump.txt").write_text(result, encoding="utf-8")
        assert "UI hierchary dumped to:" in result or "UI hierarchy dumped to:" in result, (
            "UIAutomator did not write a fresh hierarchy: " + result.strip())
        xml = self.adb("exec-out", "cat", self.dump, timeout=max(.1, deadline - time.monotonic()))
        if not include_unimportant:
            (self.output / "last-ui.xml").write_text(xml, encoding="utf-8")
        nodes = [node.attrib for node in ET.fromstring(xml).iter("node")]
        if not include_unimportant:
            self.last_nodes = nodes
        assert nodes, "Empty accessibility hierarchy"
        assert not any(n.get("package") == "com.android.vending" for n in nodes), (
            "Unexpected Google Play window; this script must never enter checkout")
        if self.find("purchase_page", nodes) is not None:
            text = "\n".join(n.get("text", "") + " " + n.get("content-desc", "") for n in nodes)
            assert not re.search(r"\$\s*9[.,]99\b", text), "A fake $9.99 fallback price is visible"
        return nodes

    @staticmethod
    def find(identifier: str, nodes: list[dict[str, str]]) -> dict[str, str] | None:
        return next((n for n in nodes if n.get("resource-id", "").endswith(":id/" + identifier)), None)

    @staticmethod
    def bounds(node: dict[str, str]) -> tuple[int, int, int, int]:
        match = re.fullmatch(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]", node.get("bounds", ""))
        assert match, f"Invalid bounds: {node.get('bounds')}"
        return tuple(map(int, match.groups()))

    def visible(self, node: dict[str, str]) -> bool:
        left, top, right, bottom = self.bounds(node)
        return 0 <= left < right <= self.width and 0 <= top < bottom <= self.height

    def target(self, identifier: str, node: dict[str, str], *, record: str | None = None,
               list_item: bool = False) -> None:
        assert self.visible(node), f"{identifier} is outside the display"
        assert node.get("enabled") == "true" and (node.get("clickable") == "true" or list_item), (
            f"{identifier} is not enabled and clickable")
        label = node.get("text") or node.get("content-desc")
        assert label, f"{identifier} lacks an accessible label"
        left, top, right, bottom = self.bounds(node)
        assert min(right - left, bottom - top) >= 48 * self.density - 1, (
            f"{identifier} clipped or below 48dp: {(right-left)/self.density:.2f}×{(bottom-top)/self.density:.2f}dp")
        if record:
            self.report["touch_targets"][record] = {
                "id": identifier, "bounds": [left, top, right, bottom],
                "width_dp": round((right - left) / self.density, 2),
                "height_dp": round((bottom - top) / self.density, 2), "label": label,
            }

    def poll(self, condition, message: str, *, timeout: float = 40) -> list[dict[str, str]]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            remaining = deadline - time.monotonic()
            # A normal dump takes roughly two seconds on the test phone. Do not
            # start a final dump with a tiny budget and obscure the unmet UI
            # expectation with a subprocess timeout.
            if remaining < 3:
                break
            try:
                nodes = self.ui(timeout=min(12, remaining))
            except subprocess.TimeoutExpired as exc:
                raise AssertionError(message + "; accessibility dump timed out") from exc
            if condition(nodes):
                return nodes
            time.sleep(min(.2, max(0, deadline - time.monotonic())))
        exposed = sorted({n.get("resource-id", "").rsplit("/", 1)[-1]
                          for n in self.last_nodes if n.get("resource-id")})
        raise AssertionError(message + "; last visible IDs=" + ",".join(exposed))

    def wait(self, identifier: str, *, enabled: bool = True, timeout: float = 40) -> dict[str, str]:
        def ready(nodes):
            node = self.find(identifier, nodes)
            return node is not None and self.visible(node) and (not enabled or node.get("enabled") == "true")
        return self.find(identifier, self.poll(ready, f"Timed out waiting for {identifier}", timeout=timeout))

    def settle(self, required: tuple[str, ...], hidden: tuple[str, ...]) -> list[dict[str, str]]:
        return self.poll(lambda nodes: all((n := self.find(identifier, nodes)) is not None and self.visible(n)
                                          for identifier in required)
                         and all(self.find(identifier, nodes) is None for identifier in hidden),
                         f"Modal tree did not settle: required={required}, hidden={hidden}", timeout=15)

    def snapshot(self, name: str, nodes: list[dict[str, str]] | None = None) -> None:
        if nodes is None:
            nodes = self.ui()
        path = self.output / (name + "-ui.json")
        path.write_text(json.dumps(nodes, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
        self.report["artifacts"][name + "_ui"] = path.name
        self.screenshot(name)
        self.save()

    def screenshot(self, name: str) -> None:
        path = self.output / (name + ".png")
        path.write_bytes(self.adb("exec-out", "screencap", "-p", binary=True))
        self.report["artifacts"][name + "_screenshot"] = path.name

    def tap(self, node: dict[str, str], *, trial_shutter: bool = False) -> None:
        identifier = node.get("resource-id", "").rsplit("/", 1)[-1]
        assert identifier != "purchase_buy", "Purchase taps are forbidden, even when disabled"
        if identifier == "shutter":
            assert trial_shutter and self.label_matches(node.get("content-desc", ""), "billing_unlock_to_shoot"), (
                "Only an explicitly verified trial shutter may be tapped; never capture on a free camera")
        # AlertDialog's native ListView owns click handling on some Android versions.
        list_item = node.get("class") == "android.widget.CheckedTextView" and node.get("checkable") == "true"
        self.target(identifier or node.get("class", "control"), node, list_item=list_item)
        left, top, right, bottom = self.bounds(node)
        self.report["last_action"] = "tap " + identifier
        action = {"phase": self.phase, "id": identifier,
                  "bounds": [left, top, right, bottom],
                  "point": [(left + right) // 2, (top + bottom) // 2],
                  "started_at_ns": time.time_ns()}
        self.report.setdefault("taps", []).append(action)
        self.save()
        self.adb("shell", "input", "tap", str((left + right) // 2), str((top + bottom) // 2))
        action["completed_at_ns"] = time.time_ns()
        self.save()

    def swipe(self, container: dict[str, str], *, to_start: bool, horizontal: bool = False) -> None:
        left, top, right, bottom = self.bounds(container)
        if horizontal:
            # Stay within the rail and away from system back-gesture edges.
            start, end = (.28, .72) if to_start else (.72, .28)
            x1, x2 = left + int((right-left) * start), left + int((right-left) * end)
            y1 = y2 = (top + bottom) // 2
        else:
            start, end = (.35, .65) if to_start else (.65, .35)
            x1 = x2 = (left + right) // 2
            y1, y2 = top + int((bottom-top) * start), top + int((bottom-top) * end)
        self.report["last_action"] = f"swipe {'horizontal' if horizontal else 'vertical'} {x1},{y1} to {x2},{y2}"
        self.report.setdefault("gestures", []).append({"phase": self.phase, "container_bounds": [left, top, right, bottom],
                                                      "from": [x1, y1], "to": [x2, y2]})
        self.save()
        self.adb("shell", "input", "swipe", str(x1), str(y1), str(x2), str(y2), "650")

    def scroll_to(self, identifier: str, *, interactive: bool = True) -> dict[str, str]:
        def usable(nodes):
            node = self.find(identifier, nodes)
            if node is None or not self.visible(node):
                return None
            if interactive:
                left, top, right, bottom = self.bounds(node)
                if min(right-left, bottom-top) < 48 * self.density - 1:
                    return None
            return node
        node = usable(self.ui())
        if node is not None:
            return node
        # Reset upward before searching so previous dialog focus/scroll does not skip rows.
        for to_start, attempts in ((True, 18), (False, 24)):
            last_bounds = None
            unchanged = 0
            for _ in range(attempts):
                nodes = self.ui()
                node = usable(nodes)
                if node is not None:
                    return node
                scroll = next((n for n in nodes if n.get("class") == "android.widget.ScrollView" and self.visible(n)), None)
                assert scroll, f"No scroll container while looking for {identifier}"
                # Use the visible child signature to detect the end, not the ScrollView bounds.
                signature = tuple((n.get("resource-id"), n.get("text"), n.get("bounds")) for n in nodes
                                  if n.get("class") != "android.widget.ScrollView")
                unchanged = unchanged + 1 if signature == last_bounds else 0
                last_bounds = signature
                if unchanged >= 2:
                    break
                self.swipe(scroll, to_start=to_start)
        raise AssertionError(f"Could not scroll to {identifier} in {self.phase}")

    def scroll_action(self, identifier: str) -> dict[str, str]:
        node = self.scroll_to(identifier)
        # Billing refresh disables actions briefly. Wait for the actual enabled
        # state rather than treating a correctly disabled control as a UI failure.
        if node.get("enabled") != "true":
            node = self.wait(identifier)
        return node

    def style_target(self, identifier: str) -> dict[str, str]:
        order = FREE + PAID
        wanted = order.index(identifier)
        for _ in range(24):
            nodes = self.ui()
            node = self.find(identifier, nodes)
            if node is not None and self.visible(node):
                left, top, right, bottom = self.bounds(node)
                if min(right-left, bottom-top) >= 48 * self.density - 1:
                    return node
            rail = next((n for n in nodes if n.get("class") == "android.widget.HorizontalScrollView"), None)
            if rail is None:
                # Uncompressed is used only for noninteractive container geometry.
                rail = next((n for n in self.ui(include_unimportant=True)
                             if n.get("class") == "android.widget.HorizontalScrollView"), None)
            assert rail and self.visible(rail), "Camera style rail is missing"
            visible_indices = [index for index, style in enumerate(order)
                               if (chip := self.find(style, nodes)) is not None and self.visible(chip)]
            if node is not None:
                to_start = self.bounds(node)[0] <= self.bounds(rail)[0]
            else:
                assert visible_indices, "No style chips exposed in the camera rail"
                to_start = wanted < min(visible_indices)
            self.swipe(rail, to_start=to_start, horizontal=True)
        raise AssertionError(f"Could not reveal style chip {identifier}")

    def camera(self, style: str | None = None, *, trial: bool = False) -> list[dict[str, str]]:
        def ready(nodes):
            shutter = self.find("shutter", nodes)
            selected = [identifier for identifier in FREE + PAID
                        if (n := self.find(identifier, nodes)) is not None and n.get("selected") == "true"]
            trial_text = any(self.label_matches(n.get("text", ""), "billing_preview_only") for n in nodes)
            trial_shutter = shutter is not None and self.label_matches(shutter.get("content-desc", ""), "billing_unlock_to_shoot")
            return shutter is not None and shutter.get("enabled") == "true" and len(selected) == 1 \
                and (style is None or selected == [style]) and trial_text == trial and trial_shutter == trial \
                and self.find("purchase_page", nodes) is None
        return self.poll(ready, f"Camera did not reach style={style}, trial={trial}", timeout=60)

    def purchase(self) -> list[dict[str, str]]:
        self.wait("purchase_back")
        return self.settle(("purchase_page", "purchase_back"), CAMERA_HIDDEN + ("settings_back", "gallery_back"))

    def unconfigured(self, name: str) -> None:
        self.purchase()
        status = self.scroll_to("purchase_status", interactive=False)
        assert self.label_matches(status.get("text", ""), "billing_not_configured"), (
            "This test requires an unconfigured RC with no purchase verification key")
        buy = self.scroll_to("purchase_buy")
        assert buy.get("enabled") == "false", "An unconfigured build must not permit purchase"
        assert self.label_matches(buy.get("text", ""), "purchase_buy_unavailable"), "Unexpected buy label"
        nodes = self.ui()
        text = "\n".join(n.get("text", "") + " " + n.get("content-desc", "") for n in nodes)
        assert not re.search(r"\$\s*9[.,]99\b", text), "A fake $9.99 fallback price is visible"
        assert not any(self.label_matches(n.get("text", ""), "purchase_owned") for n in nodes), (
            "A paid camera was incorrectly unlocked")
        self.snapshot(name, nodes)

    def media(self, stage: str) -> tuple[int, ...]:
        selection = f"relative_path='Pictures/PhyToy/' AND _display_name LIKE 'PT_%' AND owner_package_name='{self.args.package}'"
        raw = self.shell("content", "query", "--uri", "content://media/external/images/media",
                         "--projection", "_id:_display_name", "--where", selection)
        assert not re.search(r"(?i)error|exception|permission denial", raw), "MediaStore query failed: " + raw
        ids = tuple(sorted(int(value) for value in re.findall(r"\b_id=(\d+)\b", raw)))
        assert ids or "No result found" in raw, "Unrecognized MediaStore query output: " + raw
        artifact = self.output / (stage + "-mediastore.txt")
        artifact.write_text(raw, encoding="utf-8")
        self.report["media_history"].append({"stage": stage, "count": len(ids), "ids": list(ids), "artifact": artifact.name})
        self.save()
        return ids

    def restart(self) -> None:
        self.adb("shell", "am", "force-stop", self.args.package)
        self.adb("shell", "am", "start", "-W", "-n", self.args.package + "/" + self.args.activity)

    def permissions(self) -> dict[str, str]:
        return dict(re.findall(r"([\w.]+): granted=(true|false)", self.adb("shell", "dumpsys", "package", self.args.package)))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", default="com.phytoy.sample")
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--output", type=Path, default=Path("reports/billing_smoke"))
    parser.add_argument("--expected-apk-sha256")
    parser.add_argument("--allow-physical-device", action="store_true",
                        help="Explicitly permit an authorized test phone; includes reversible 200%% font checks")
    args = parser.parse_args()
    if not re.fullmatch(r"emulator-\d+", args.serial) and not args.allow_physical_device:
        parser.error("Physical devices require explicit --allow-physical-device")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid Android application ID")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid Android activity name")
    if args.expected_apk_sha256 and not re.fullmatch(r"[0-9a-fA-F]{64}", args.expected_apk_sha256):
        parser.error("--expected-apk-sha256 must be 64 hexadecimal characters")
    smoke = Smoke(args)
    font_original = None
    font_changed = launched = False
    initial_style = None
    media_before = None
    permissions_before = None
    locale_before = None
    default_before = None
    try:
        assert smoke.adb("get-serialno").strip() == args.serial, "Connected serial does not match"
        qemu = {p: smoke.adb("shell", "getprop", p).strip() for p in ("ro.kernel.qemu", "ro.boot.qemu")}
        assert "1" in qemu.values() or args.allow_physical_device, "Target is not an Android emulator"
        smoke.report["emulator_properties"] = qemu
        smoke.report["physical_device_opt_in"] = args.allow_physical_device
        smoke.report["device_model"] = smoke.adb("shell", "getprop", "ro.product.model").strip()
        sdk = int(smoke.adb("shell", "getprop", "ro.build.version.sdk").strip())
        assert sdk >= 30, "Own-album MediaStore verification requires Android 11/API 30 or later"
        smoke.report["sdk"] = sdk
        sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", smoke.adb("shell", "wm", "size"))
        densities = re.findall(r"(?:Physical|Override) density:\s*(\d+)", smoke.adb("shell", "wm", "density"))
        assert sizes and densities, "Cannot determine device display geometry"
        smoke.width, smoke.height = map(int, sizes[-1])
        smoke.density = int(densities[-1]) / 160
        smoke.report["display"] = {"width_pixels": smoke.width, "height_pixels": smoke.height, "density_dpi": int(densities[-1])}
        paths = [line[8:] for line in smoke.adb("shell", "pm", "path", args.package).splitlines() if line.startswith("package:")]
        assert paths, "App is not installed"
        apks = [{"path": path, "sha256": smoke.shell("sha256sum", path).split()[0]} for path in paths]
        base = next((apk for apk in apks if apk["path"].endswith("/base.apk")), apks[0])
        assert all(re.fullmatch(r"[0-9a-f]{64}", apk["sha256"]) for apk in apks), "Invalid installed APK hash"
        smoke.report["installed_apks"] = apks
        smoke.report["installed_apk_sha256"] = base["sha256"]
        if args.expected_apk_sha256:
            assert base["sha256"] == args.expected_apk_sha256.lower(), "Installed APK differs from expected RC"
        permissions_before = smoke.permissions()
        assert permissions_before.get("android.permission.CAMERA") == "true", "Grant CAMERA before running; this script will not change permissions"
        locale_before = smoke.adb("shell", "getprop", "persist.sys.locale").strip()
        font_original = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
        assert font_original == "null" or re.fullmatch(r"\d+(?:\.\d+)?", font_original), "Unexpected font_scale value"
        smoke.report["font_scale_original"] = font_original
        media_before = smoke.media("initial")
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial_style = next(identifier for identifier in FREE + PAID if (n := smoke.find(identifier, nodes)) and n.get("selected") == "true")
        assert initial_style in FREE, "Unconfigured startup must fall back to a free camera"
        smoke.report["initial_visible_style"] = initial_style
        smoke.snapshot("initial-camera", nodes)

        smoke.phase = "free cameras"
        for identifier in FREE:
            smoke.tap(smoke.style_target(identifier))
            smoke.snapshot("free-" + identifier, smoke.camera(identifier))
        smoke.check("both free cameras select without trial caption or unlock shutter", styles=list(FREE))

        smoke.phase = "settings locked default and restore"
        smoke.tap(smoke.wait("open_settings"))
        smoke.settle(("settings_back",), CAMERA_HIDDEN)
        default = smoke.scroll_to("settings_default_style")
        default_before = default.get("text", "")
        smoke.report["default_style_before"] = default_before
        smoke.tap(default)
        choices = smoke.poll(lambda n: any(node.get("class") == "android.widget.CheckedTextView" for node in n), "Default style choices missing")
        names = smoke.strings[STYLE_RESOURCES[PAID[0]]]
        locked = next((n for n in choices if n.get("class") == "android.widget.CheckedTextView" and any(name in n.get("text", "") for name in names)), None)
        assert locked, "Default style dialog has no locked paid camera option"
        smoke.tap(locked)
        smoke.unconfigured("settings-locked-default")
        smoke.tap(smoke.wait("purchase_back"))
        smoke.settle(("settings_back",), CAMERA_HIDDEN + ("purchase_back",))
        smoke.snapshot("settings-after-purchase-back")
        assert smoke.scroll_to("settings_default_style").get("text", "") == default_before, "Locked default changed settings"
        smoke.snapshot("settings-default-unchanged")
        restore = smoke.scroll_to("settings_restore_purchases")
        smoke.target("settings_restore_purchases", restore, record="settings/restore")
        smoke.tap(restore)
        time.sleep(.2)  # Capture the native error Toast before its short display expires.
        smoke.screenshot("settings-restore-error-toast")
        smoke.snapshot("settings-after-restore")
        smoke.tap(smoke.wait("settings_back"))
        smoke.camera(FREE[1])
        smoke.check("locked default opens purchase without writing default; settings restore is reachable", default_unchanged=True)

        for identifier in PAID:
            smoke.phase = "paid trial " + identifier
            smoke.tap(smoke.style_target(identifier))
            smoke.unconfigured(identifier + "-purchase")
            restore = smoke.scroll_action("purchase_restore")
            smoke.target("purchase_restore", restore, record=identifier + "/restore")
            smoke.tap(restore)
            smoke.unconfigured(identifier + "-restore-not-configured")
            smoke.tap(smoke.scroll_action("purchase_preview"))
            smoke.snapshot(identifier + "-trial", smoke.camera(identifier, trial=True))
            smoke.tap(smoke.wait("shutter"), trial_shutter=True)
            smoke.unconfigured(identifier + "-blocked-shutter")
            assert smoke.media(identifier + "-blocked-shutter") == media_before, "Trial shutter created or removed an app photo"
            smoke.tap(smoke.wait("purchase_back"))
            smoke.camera(identifier, trial=True)
            smoke.restart()
            nodes = smoke.camera()
            assert any((n := smoke.find(style, nodes)) and n.get("selected") == "true" for style in FREE), "Trial survived restart as a paid camera"
            smoke.report["styles"].append({"id": identifier, "buy_disabled": True, "preview_only": True,
                                          "restore_did_not_unlock": True, "shutter_blocked": True,
                                          "media_unchanged": True, "back_kept_trial": True, "restart_free": True})
            smoke.check(identifier + " remains locked through preview, restore, shutter, Back and restart")

        smoke.phase = "persisted default verification"
        smoke.tap(smoke.wait("open_settings"))
        assert smoke.scroll_to("settings_default_style").get("text", "") == default_before, "Locked default was persisted after restart"
        smoke.tap(smoke.wait("settings_back"))
        smoke.camera()
        smoke.check("locked settings default remains unchanged after process restart")

        smoke.phase = "200 percent purchase page"
        font_changed = True  # Restore even if the command applies but its response times out.
        smoke.adb("shell", "settings", "put", "system", "font_scale", "2.0")
        assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == "2.0", "Font scale did not apply"
        smoke.restart()
        smoke.camera()
        smoke.tap(smoke.style_target(PAID[0]))
        smoke.purchase()
        smoke.snapshot("large-font-purchase-top")
        for identifier in ("purchase_back", "purchase_preview", "purchase_restore", "purchase_retry"):
            # Search the scroll content; an offscreen Retry is absent from a compressed dump.
            node = smoke.scroll_action(identifier)
            smoke.target(identifier, node, record="font2/" + identifier)
            smoke.snapshot("large-font-" + identifier)
        smoke.tap(smoke.scroll_action("purchase_restore"))
        smoke.unconfigured("large-font-restore-not-configured")
        smoke.tap(smoke.scroll_action("purchase_retry"))
        smoke.unconfigured("large-font-retry-unconfigured")
        smoke.tap(smoke.scroll_action("purchase_preview"))
        smoke.camera(PAID[0], trial=True)
        smoke.tap(smoke.wait("shutter"), trial_shutter=True)
        smoke.unconfigured("large-font-blocked-shutter")
        assert smoke.media("large-font-blocked-shutter") == media_before, "Large-font trial shutter changed app photos"
        smoke.tap(smoke.wait("purchase_back"))
        smoke.camera(PAID[0], trial=True)
        smoke.check("200% purchase page scrolls to four labelled 48dp actions; restore/retry do not unlock and trial cannot save")
        smoke.report["passed"] = True
    except (Exception, KeyboardInterrupt) as exc:
        smoke.report["failure"] = f"{type(exc).__name__}: {exc}"
        (smoke.output / "failure-traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
        # Preserve the actual failing snapshot before fetching a potentially settled newer tree.
        (smoke.output / "failure-ui.json").write_text(json.dumps(smoke.last_nodes, indent=2, ensure_ascii=False), encoding="utf-8")
        if launched:
            try:
                smoke.screenshot("failure")
                smoke.snapshot("failure-current")
            except Exception as evidence_error:
                smoke.report["failure_evidence_error"] = str(evidence_error)
    finally:
        cleanup_errors = []
        if launched:
            try:
                # Retain only this run's app navigation diagnostics, with no
                # receipt, checkout or unrelated device log contents.
                raw = smoke.adb("logcat", "-d", "-v", "epoch", "-t", "4000", "-s", "PhyToySample:I")
                started = smoke.report["run_started_at_ns"] / 1_000_000_000
                lines = [line for line in raw.splitlines()
                         if (match := re.match(r"^\s*(\d+\.\d+)\s", line))
                         and float(match.group(1)) >= started
                         and ("Capture action " in line or "Camera purchase page " in line)]
                path = smoke.output / "app-navigation-log.txt"
                path.write_text("\n".join(lines) + "\n", encoding="utf-8")
                smoke.report["artifacts"]["app_navigation_log"] = path.name
            except Exception as exc:
                smoke.report["navigation_log_error"] = str(exc)
        if font_changed and font_original is not None:
            try:
                if font_original == "null":
                    smoke.adb("shell", "settings", "delete", "system", "font_scale")
                else:
                    smoke.adb("shell", "settings", "put", "system", "font_scale", font_original)
                after = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
                smoke.report["font_scale_after"] = after
                smoke.report["font_scale_restored"] = after == font_original
                assert after == font_original, "Original system font scale was not restored"
            except Exception as exc:
                cleanup_errors.append("font restore: " + str(exc))
        if launched and initial_style in FREE:
            try:
                smoke.restart()
                smoke.camera()
                smoke.tap(smoke.style_target(initial_style))
                smoke.camera(initial_style)
                smoke.report["initial_visible_style_restored"] = True
                smoke.snapshot("restored-camera")
            except Exception as exc:
                cleanup_errors.append("visible style restore: " + str(exc))
        try:
            if media_before is not None:
                assert smoke.media("final") == media_before, "App photo records changed during the smoke test"
                smoke.report["photos_changed"] = False
            if permissions_before is not None:
                assert smoke.permissions() == permissions_before, "Permission grants changed"
                smoke.report["permissions_unchanged"] = True
            if locale_before is not None:
                assert smoke.adb("shell", "getprop", "persist.sys.locale").strip() == locale_before, "System locale changed"
                smoke.report["system_locale_unchanged"] = True
            if smoke.dump_created:
                smoke.shell("rm", "-f", smoke.dump)
        except Exception as exc:
            cleanup_errors.append("final state verification: " + str(exc))
        if cleanup_errors:
            smoke.report["cleanup_errors"] = cleanup_errors
            smoke.report["passed"] = False
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json")}, ensure_ascii=False))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
