#!/usr/bin/env python3
"""Verify unconfigured store-specific UI on a hash-verified emulator.

No captures, purchases, permission grants, deletion or sharing. CAMERA must
already be granted. Font/locale/permissions/photos must remain unchanged.
"""
import argparse
import json
import re
import traceback
import xml.etree.ElementTree as ET
from pathlib import Path

from run_android_ui_redesign_smoke import RedesignSmoke as UiSmoke
from run_android_billing_smoke import FREE, PAID


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--package", required=True)
    parser.add_argument("--activity", default="com.phytoy.sample.MainActivity")
    parser.add_argument("--channel", choices=("play", "galaxy"), required=True)
    parser.add_argument("--expected-apk-sha256", required=True)
    parser.add_argument("--large-font-actions", action="store_true", help="Also verify fixed actions at 200%% font scale, then restore it")
    parser.add_argument("--adjustment-actions", action="store_true", help="Also check EV/zoom sliders at the original font scale and at 200%% when requested")
    parser.add_argument("--review-actions", action="store_true", help="Also exercise existing-photo review at 200%%; requires --large-font-actions and seed photos")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.review_actions and not args.large_font_actions:
        parser.error("--review-actions requires --large-font-actions")
    if not re.fullmatch(r"emulator-\d+", args.serial):
        parser.error("Only explicit emulator serials are allowed")
    if not re.fullmatch(r"[A-Za-z_]\w*(?:\.[A-Za-z_]\w*)+", args.package):
        parser.error("Invalid package")
    if not re.fullmatch(r"\.?[A-Za-z_$][\w.$]*", args.activity):
        parser.error("Invalid activity")
    if not re.fullmatch(r"[a-fA-F0-9]{64}", args.expected_apk_sha256):
        parser.error("Expected APK SHA-256 must be 64 hexadecimal characters")
    smoke = UiSmoke(args)
    smoke.report["scope"] = "0.17 store-specific unconfigured UI: trial, fixed actions, provider, restore and privacy"
    smoke.report["not_covered"] = ["configured checkout", "real purchase or restoration", "physical devices", "photo capture", "successful deletion/system consent", "pinch/multitouch", "other font scales"]
    if not args.review_actions:
        smoke.report["not_covered"].append("photo review")
    initial = media = permissions = locale = font = None
    launched = font_changed = False
    try:
        assert smoke.adb("get-serialno").strip() == args.serial
        assert any(smoke.adb("shell", "getprop", key).strip() == "1" for key in ("ro.kernel.qemu", "ro.boot.qemu"))
        assert int(smoke.adb("shell", "getprop", "ro.build.version.sdk").strip()) >= 30
        sizes = re.findall(r"(?:Physical|Override) size:\s*(\d+)x(\d+)", smoke.adb("shell", "wm", "size"))
        densities = re.findall(r"(?:Physical|Override) density:\s*(\d+)", smoke.adb("shell", "wm", "density"))
        assert sizes and densities
        smoke.width, smoke.height = map(int, sizes[-1])
        smoke.density = int(densities[-1]) / 160
        smoke.report["display"] = {"width_px": smoke.width, "height_px": smoke.height,
                                   "density_dpi": int(densities[-1]),
                                   "width_dp": smoke.width / smoke.density,
                                   "height_dp": smoke.height / smoke.density}
        paths = [line[8:] for line in smoke.adb("shell", "pm", "path", args.package).splitlines() if line.startswith("package:")]
        assert len(paths) == 1, "This test requires one standalone APK"
        digest = smoke.shell("sha256sum", paths[0]).split()[0]
        assert digest == args.expected_apk_sha256.lower(), "Installed APK hash differs"
        smoke.report["installed_apk_sha256"] = digest
        permissions = smoke.permissions()
        assert permissions.get("android.permission.CAMERA") == "true"
        locale = smoke.adb("shell", "getprop", "persist.sys.locale").strip()
        font = smoke.adb("shell", "settings", "get", "system", "font_scale").strip()
        media = smoke.media("initial")
        resource = "values-zh-rCN" if locale.startswith("zh") else "values"
        labels = {}
        root = Path(__file__).resolve().parents[1] / "android/sample/src"
        # Play keeps some provider copy in main; flavors override only the
        # strings that differ. Match Android's main-then-flavor merge order.
        for directory in (root / "main/res" / resource, root / args.channel / "res" / resource):
            for source in directory.glob("*.xml"):
                labels.update({n.attrib["name"]: n.text for n in ET.parse(source).getroot() if n.tag == "string"})
        smoke.restart()
        launched = True
        nodes = smoke.camera()
        initial = next(key for key in FREE if (n := smoke.find(key, nodes)) and n.get("selected") == "true")
        if args.adjustment_actions:
            smoke.phase = "EV/zoom at original font scale"
            smoke.adjustment("exposure_value", font)
            smoke.adjustment("zoom_ratio", font)
        smoke.phase = "store-specific trial and unconfigured purchase"
        smoke.tap(smoke.style_target(PAID[0]))
        smoke.camera(PAID[0], trial=True)
        smoke.tap(smoke.wait("camera_unlock_style"))
        nodes = smoke.purchase()
        buy = smoke.find("purchase_buy", nodes)
        assert buy and smoke.visible(buy), "The primary unlock action is absent from the fixed dock"
        smoke.inspect_control("purchase_buy", buy, record="normal/purchase_buy")
        buy_bounds = smoke.bounds(buy)
        smoke.accessible_target("purchase_preview", record="normal/purchase_preview")
        nodes = smoke.ui()
        assert smoke.bounds(smoke.find("purchase_buy", nodes)) == buy_bounds, "The primary action moved with the body scroll"
        smoke.snapshot("purchase-fixed-actions", nodes)
        smoke.unconfigured("unconfigured-purchase")
        smoke.check("Trial does not unlock photography; primary action stays fixed, body preview is reachable; unconfigured checkout disabled")
        smoke.tap(smoke.scroll_to("purchase_restore"))
        status = smoke.scroll_to("purchase_status", interactive=False)
        assert smoke.label_matches(status.get("text", ""), "billing_not_configured")
        smoke.check("Restore preserves the visible unconfigured result")
        for _ in range(15):
            nodes = smoke.ui()
            provider = next((n for n in nodes if n.get("text") == labels["purchase_payment_provider"]), None)
            if provider and smoke.visible(provider):
                break
            scroll = next((n for n in nodes if n.get("class") == "android.widget.ScrollView" and smoke.visible(n)), None)
            assert scroll, "Provider scroll container missing"
            smoke.swipe(scroll, to_start=False)
        else:
            raise AssertionError("The correct store's payment provider is not visible")
        assert any(n.get("text") == labels["purchase_scope"] for n in nodes), "Wrong account/restore scope"
        smoke.snapshot("store-provider-and-scope", nodes)
        smoke.check("Payment provider and purchase scope exactly match this store's localized resources")
        smoke.tap(smoke.wait("purchase_back"))
        smoke.camera(PAID[0], trial=True)
        smoke.tap(smoke.style_target(initial))
        smoke.camera(initial)
        smoke.phase = "store-specific offline privacy"
        smoke.tap(smoke.wait("open_settings"))
        smoke.tap(smoke.scroll_to("settings_privacy"))
        nodes = smoke.poll(lambda ns: any(smoke.label_matches(n.get("text", ""), "product_privacy_title") for n in ns), "Privacy missing")
        body = "\n".join(n.get("text", "") for n in nodes)
        assert labels["billing_store_name"] in body and labels["billing_account_name"] in body, "Privacy uses the wrong store/account"
        smoke.snapshot("offline-privacy", nodes)
        smoke.tap(smoke.wait("button1"))
        smoke.tap(smoke.wait("settings_back"))
        smoke.camera(initial)
        smoke.check("Offline privacy names the correct store and account")
        if args.large_font_actions:
            smoke.phase = "fixed actions at 200% font scale"
            font_changed = True
            smoke.adb("shell", "settings", "put", "system", "font_scale", "2.0")
            smoke.restart()
            smoke.camera()
            if args.adjustment_actions:
                smoke.adjustment("exposure_value", "2.0")
                smoke.adjustment("zoom_ratio", "2.0")
            smoke.tap(smoke.style_target(PAID[0]))
            smoke.camera(PAID[0], trial=True)
            smoke.tap(smoke.wait("camera_unlock_style"))
            nodes = smoke.purchase()
            buy = smoke.find("purchase_buy", nodes)
            assert buy and smoke.visible(buy), "Large-font primary action clipped"
            assert buy.get("enabled") == "false", "Unconfigured checkout enabled"
            smoke.inspect_control("purchase_buy", buy, record="font2/purchase_buy")
            buy_bounds = smoke.bounds(buy)
            preview = smoke.accessible_target("purchase_preview", record="font2/purchase_preview")
            nodes = smoke.ui()
            assert smoke.bounds(smoke.find("purchase_buy", nodes)) == buy_bounds, "Large-font primary action moved with the body"
            smoke.snapshot("font2-purchase-fixed-actions", nodes)
            smoke.tap(preview)
            smoke.camera(PAID[0], trial=True)
            smoke.tap(smoke.style_target(initial))
            smoke.camera(initial)
            smoke.check("200%: primary action stays fixed; body preview is fully reachable at >=48dp and returns to the trial")
            if args.review_actions:
                smoke.phase = "existing-photo review at 200% font scale"
                smoke.photos()
        smoke.report["passed"] = True
    except (Exception, KeyboardInterrupt) as exc:
        smoke.report["failure"] = f"{type(exc).__name__}: {exc}"
        (smoke.output / "failure-traceback.txt").write_text(traceback.format_exc())
        try:
            smoke.screenshot("failure")
        except Exception as evidence_error:
            smoke.report["failure_evidence_error"] = str(evidence_error)
    finally:
        errors = []
        if font_changed and font is not None:
            try:
                if font == "null":
                    smoke.adb("shell", "settings", "delete", "system", "font_scale")
                else:
                    smoke.adb("shell", "settings", "put", "system", "font_scale", font)
                assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == font
                smoke.report["font_scale_restored"] = True
            except Exception as exc:
                errors.append("font restore: " + str(exc))
        if launched and initial:
            try:
                smoke.restart()
                smoke.camera()
                smoke.tap(smoke.style_target(initial))
                smoke.camera(initial)
                smoke.report["initial_visible_style_restored"] = True
            except Exception as exc:
                errors.append("style restore: " + str(exc))
        try:
            if media is not None:
                assert smoke.media("final") == media
                smoke.report["photos_changed"] = False
            if permissions is not None:
                assert smoke.permissions() == permissions
                smoke.report["permissions_unchanged"] = True
            if locale is not None:
                assert smoke.adb("shell", "getprop", "persist.sys.locale").strip() == locale
                smoke.report["system_locale_unchanged"] = True
            if font is not None:
                assert smoke.adb("shell", "settings", "get", "system", "font_scale").strip() == font
                smoke.report["font_scale_unchanged"] = True
            if smoke.dump_created:
                smoke.shell("rm", "-f", smoke.dump)
        except Exception as exc:
            errors.append("final invariants: " + str(exc))
        if errors:
            smoke.report.update(cleanup_errors=errors, passed=False)
        smoke.save()
    print(json.dumps({"passed": smoke.report["passed"], "report": str(smoke.output / "evaluation.json")}))
    return 0 if smoke.report["passed"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
