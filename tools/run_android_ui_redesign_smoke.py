#!/usr/bin/env python3
"""Regression for the redesigned ToviCam UI on an explicitly selected emulator.

Requires an already installed APK with a supplied SHA-256, CAMERA already
granted, an initially selected free camera, and two existing app photos. Never
captures, purchases, confirms deletion, grants permissions, chooses a share
recipient, directly writes private preferences, or operates a physical device. Font scale
and the initial visible free style are restored in finally. Favorite is changed
only through its visible control and its initial checked state is restored,
including a UI recovery attempt if the round trip fails. No private preferences
are read or written directly. MediaStore IDs and photo bytes, permissions, and
locale must remain unchanged.

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

GALLERY_TABS = ("gallery_tab_camera", "gallery_tab_gallery", "gallery_tab_settings")


class RedesignSmoke(UiSmoke):
    def __init__(self, args):
        super().__init__(args)
        self.report.update({
            "scope": "UI redesign: directional EV/zoom, trial/detail, grouped gallery, empty selection, nested settings, review menu/gestures/actions and Favorite round trip",
            "not_covered": [
                "checkout or purchase success", "successful photo deletion or system delete consent",
                "pinch/multitouch", "capture timing, output quality, EXIF or engine correctness",
                "visual pixel correctness", "physical devices", "all paid cameras and entitlement states",
                "complete settings operations and privacy document content",
                "sharing selected gallery photos", "byte-for-byte private preference preservation",
            ],
            "photos_taken": 0, "photos_deleted": 0, "shares_sent": 0,
            "direct_private_preference_writes": 0,
            "private_preference_write_scope": "No direct preference writes; Favorite is toggled through UI and its initial checked state is restored",
            "preference_restore_scope": "Restore the initial visible free camera and the tested photo's initial Favorite checked flag through UI; no byte-for-byte private preference claim",
        })
        self.pending_favorite = None

    def tap(self, node, **kwargs):
        assert not node.get("resource-id", "").endswith(":id/design_gallery_share_selected"), (
            "Dispatching selected-photo sharing is outside this regression")
        super().tap(node, **kwargs)

    def inspect_control(self, identifier, node, *, record):
        """Validate the actual accessible hit control, including disabled items."""
        if node.get("enabled") == "true":
            self.target(identifier, node, record=record)
            return
        assert node.get("enabled") == "false", f"{identifier} has no explicit enabled state"
        assert self.visible(node), f"Disabled {identifier} is not fully visible"
        assert node.get("clickable") == "true", f"Disabled {identifier} lost button semantics"
        label = node.get("content-desc") or node.get("text")
        assert label, f"Disabled {identifier} has no accessible label"
        left, top, right, bottom = self.bounds(node)
        assert min(right - left, bottom - top) >= 48 * self.density - 1, f"Disabled {identifier} is below 48dp"
        self.report["touch_targets"][record] = {
            "id": identifier, "enabled": False, "bounds": [left, top, right, bottom],
            "width_dp": round((right - left) / self.density, 2),
            "height_dp": round((bottom - top) / self.density, 2), "label": label,
        }

    def gallery_ready(self):
        def ready(nodes):
            return all((node := self.find(identifier, nodes)) is not None and self.visible(node)
                       for identifier in GALLERY_TABS) and any(
                node.get("resource-id", "").endswith(":id/gallery_item") and self.visible(node)
                for node in nodes) and self.find("settings_back", nodes) is None
        nodes = self.poll(ready, "Existing seeded photos and gallery tabs did not appear", timeout=40)
        assert all(self.find(identifier, nodes) is None for identifier in CAMERA_HIDDEN), (
            "Camera leaked through the gallery modal")
        return nodes

    def first_gallery_photo(self, nodes):
        item = next((node for node in nodes if node.get("resource-id", "").endswith(":id/gallery_item")
                     and self.visible(node)), None)
        assert item is not None, "No existing gallery item is fully visible"
        return item

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
                    # Preview belongs to the body for a locked camera and to
                    # the dock for an owned camera. Use the exposed target's
                    # actual container, including when its hit box is clipped.
                    target = self.find(identifier, nodes)
                    enclosing = []
                    if target is not None:
                        tl, tt, tr, tb = self.bounds(target)
                        enclosing = [node for node in scrolls if
                                     (b := self.bounds(node))[0] <= tl < tr <= b[2] and
                                     b[1] <= tt < tb <= b[3]]
                    if enclosing:
                        scroll = min(enclosing, key=lambda node:
                                     (self.bounds(node)[2] - self.bounds(node)[0]) *
                                     (self.bounds(node)[3] - self.bounds(node)[1]))
                    elif identifier == "purchase_buy":
                        scroll = max(scrolls, key=lambda node: (self.bounds(node)[1], self.bounds(node)[0]))
                    else:
                        scroll = min(scrolls, key=lambda node: (self.bounds(node)[1], self.bounds(node)[0]))
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
        nodes = self.ui()
        if identifier == "zoom_ratio":
            before = self.zoom_value(nodes)
            opener = self.zoom_opener(nodes)
        else:
            opener = self.wait(identifier)
            before = self.node_number(opener)
        self.target(identifier, opener, record=f"font{font}/{identifier}")
        self.tap(opener)
        slider = self.scroll_to("adjustment_slider")
        assert slider.get("class") == "android.widget.SeekBar", "Native adjustment range is not exposed"
        left, top, right, bottom = self.bounds(slider)
        assert self.visible(slider) and min(right - left, bottom - top) >= 48 * self.density - 1, (
            "Native adjustment touch area is clipped or below 48dp")
        self.report["touch_targets"][f"font{font}/{identifier}-slider"] = {
            "bounds": [left, top, right, bottom], "width_dp": (right - left) / self.density,
            "height_dp": (bottom - top) / self.density,
        }
        horizontal = right - left >= bottom - top
        self.swipe(slider, to_start=False, horizontal=horizontal)
        def value(nodes):
            if identifier == "zoom_ratio":
                return self.zoom_value(nodes)
            node = self.find(identifier, nodes)
            assert node is not None, f"Actual {identifier} readout disappeared"
            return self.node_number(node)
        nodes = self.poll(lambda ns: abs(value(ns) - before) > .01,
                          f"{identifier} did not change its actual camera value")
        changed = value(nodes)
        self.snapshot(f"font{font}-{identifier}-changed", nodes)
        if identifier == "exposure_value":
            self.tap(self.accessible_target("adjustment_reset"))
            self.poll(lambda ns: (node := self.find(identifier, ns)) is not None and
                      abs(self.node_number(node)) < .01, "EV reset did not return to zero")
        else:
            # The design keeps focal presets below the inline slider. Reset in
            # the panel uses the existing 1x callback, with a real native target.
            self.tap(self.accessible_target("adjustment_reset"))
            self.poll(lambda ns: abs(self.zoom_value(ns) - 1) < .01, "Zoom reset did not return to 1×")
        self.tap(self.accessible_target("adjustment_done"))
        self.poll(lambda ns: self.find("adjustment_slider", ns) is None, "Adjustment panel did not close")
        self.check(f"font {font}: {identifier} changes the camera value and resets",
                   before=before, changed=changed, slider_orientation="horizontal" if horizontal else "vertical")

    def selected_zoom_preset(self, nodes):
        candidates = []
        for node in nodes:
            if node.get("selected") != "true" or node.get("clickable") != "true" or not self.visible(node):
                continue
            match = re.fullmatch(r"(\d+(?:[.,]\d+)?)×", node.get("text", ""))
            if match is None:
                continue
            amount = float(match.group(1).replace(",", "."))
            labels = {label.replace("%1$.2f", f"{amount:.2f}") for label in self.strings["camera_adjust_zoom_value"]}
            labels |= {label.replace(f"{amount:.2f}", f"{amount:.2f}".replace(".", ",")) for label in labels}
            if node.get("content-desc", "") in labels:
                candidates.append((node, amount))
        assert len(candidates) <= 1, "Multiple real camera zoom presets are selected"
        return candidates[0] if candidates else None

    def zoom_value(self, nodes):
        node = self.find("zoom_ratio", nodes)
        if node is not None and self.visible(node):
            return self.node_number(node)
        preset = self.selected_zoom_preset(nodes)
        assert preset is not None, "Neither the actual zoom readout nor an accessible selected zoom preset is visible"
        return preset[1]

    def zoom_opener(self, nodes):
        node = self.find("zoom_ratio", nodes)
        if node is not None and self.visible(node):
            return node
        preset = self.selected_zoom_preset(nodes)
        assert preset is not None, "The camera has no accessible zoom slider opener"
        return preset[0]

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
        assert all(self.find(identifier, nodes) is None for identifier in
                   CAMERA_HIDDEN + GALLERY_TABS + ("design_gallery_select",)), (
            "Camera or gallery leaked through the review modal")
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

    def gallery_selection(self):
        self.gallery_ready()
        select = self.accessible_target("design_gallery_select", record="font2/gallery-select")
        assert self.label_matches(select.get("text", ""), "design_gallery_select"), "Gallery starts in selection mode"
        self.tap(select)
        def selecting(nodes):
            control = self.find("design_gallery_select", nodes)
            return control is not None and self.label_matches(control.get("text", ""), "design_gallery_done") and \
                (share := self.find("design_gallery_share_selected", nodes)) is not None and self.visible(share)
        nodes = self.poll(selecting, "Select did not open the gallery selection mode")
        assert all(self.find(identifier, nodes) is None for identifier in GALLERY_TABS), (
            "Navigation remains exposed underneath selection controls")
        zero_labels = {label.replace("%1$d", "0") for label in self.strings["design_gallery_selected_count"]}
        assert any(node.get("text", "") in zero_labels for node in nodes), "Selection count does not start at zero"
        items = [node for node in nodes if node.get("resource-id", "").endswith(":id/gallery_item") and self.visible(node)]
        assert items and all(node.get("checkable") == "true" and node.get("checked") == "false" for node in items), (
            "Selection mode must expose actual unchecked photo controls")
        share = self.scroll_to("design_gallery_share_selected")
        assert share.get("enabled") == "false", "Zero selected photos must disable sharing"
        self.inspect_control("design_gallery_share_selected", share, record="font2/gallery-empty-selection-share")
        self.snapshot("font2-gallery-empty-selection")
        done = self.accessible_target("design_gallery_select", record="font2/gallery-selection-done")
        assert self.label_matches(done.get("text", ""), "design_gallery_done"), "Selection exit is not Done"
        self.tap(done)
        nodes = self.gallery_ready()
        assert self.find("design_gallery_share_selected", nodes) is None, "Selection sharing leaked after Done"
        assert self.label_matches(self.find("design_gallery_select", nodes).get("text", ""), "design_gallery_select"), (
            "Done did not restore Select")
        self.check("200%: Select opens zero-selection mode and Done exits without selecting or sharing photos")
        return nodes

    def review_menu(self, name, expected_position, *, zoomed=None):
        self.review_ready(expected_position)
        self.tap(self.accessible_target("review_more", record=name + "/more"))
        self.poll(lambda ns: any(self.label_matches(node.get("text", ""), "design_review_more_title")
                                 for node in ns), "More photo actions did not open")
        enabled = {}
        for identifier, label in (("review_previous", "photo_previous"), ("review_next", "photo_next"),
                                  ("review_zoom_reset", "photo_zoom_reset")):
            node = self.scroll_to(identifier)
            assert self.label_matches(node.get("text", ""), label), f"Menu item {identifier} has the wrong label"
            self.inspect_control(identifier, node, record=name + "/" + identifier)
            enabled[identifier] = node.get("enabled") == "true"
        assert enabled["review_previous"] == (expected_position[0] > 1), "Previous does not match the actual photo position"
        assert enabled["review_next"] == (expected_position[0] < expected_position[1]), "Next does not match the actual photo count"
        if zoomed is not None:
            assert enabled["review_zoom_reset"] == zoomed, "Fit photo does not reflect the actual zoom state"
        self.snapshot(name)
        return enabled

    def close_review_menu(self, expected_position):
        done = self.accessible_target("button2")
        assert self.label_matches(done.get("text", ""), "review_metadata_close"), "More menu exit is not Done"
        self.tap(done)
        self.menu_dismissed()
        return self.review_ready(expected_position)

    def menu_dismissed(self):
        self.poll(lambda ns: all(self.find(identifier, ns) is None for identifier in
                                ("review_previous", "review_next", "review_zoom_reset")) and
                  not any(self.label_matches(node.get("text", ""), "design_review_more_title") for node in ns),
                  "More photo actions did not close")

    def menu_navigate(self, direction, position, name):
        assert direction in (-1, 1)
        result = (position[0] + direction, position[1])
        assert 1 <= result[0] <= result[1], "Menu navigation would exceed the actual photo list"
        self.review_menu(name, position, zoomed=False)
        identifier = "review_previous" if direction < 0 else "review_next"
        self.tap(self.accessible_target(identifier, record=name + "/navigate"))
        self.menu_dismissed()
        self.review_ready(result)
        return result

    def favorite_state(self, node):
        assert node.get("checkable") == "true" and node.get("class") == "android.widget.CheckBox", (
            "Favorite is missing its checkable accessibility semantics")
        assert node.get("checked") in ("true", "false") and node.get("selected") == node.get("checked"), (
            "Favorite checked/selected states disagree")
        checked = node["checked"] == "true"
        assert self.label_matches(node.get("content-desc", ""),
                                  "design_review_favorite_remove" if checked else "design_review_favorite_add"), (
            "Favorite action label does not match its current checked state")
        return checked

    def favorite_roundtrip(self, position, filename):
        self.review_ready(position)
        node = self.accessible_target("review_favorite", record="font2/favorite-before")
        initial = self.favorite_state(node)
        self.pending_favorite = {"position": position, "filename": filename, "checked": initial}
        self.report["favorite_initial"] = dict(self.pending_favorite)
        self.save()
        try:
            self.tap(node)
            nodes = self.poll(lambda ns: (current := self.find("review_favorite", ns)) is not None and
                              self.favorite_state(current) != initial,
                              "Favorite did not change its actual checked state")
            assert self.position(nodes) == position, "Favorite switched the current photo"
            self.snapshot("font2-favorite-changed", nodes)
        finally:
            self.restore_favorite()
        self.check("200%: Favorite toggles its real accessible state and restores the original photo favorite flag",
                   photo=filename, initial_checked=initial, final_checked=initial)

    def restore_favorite(self, *, reopen=False):
        pending = self.pending_favorite
        if pending is None:
            return
        self.phase = "Favorite restore"
        position = tuple(pending["position"])
        if reopen:
            # Recovery uses the same safe UI route. It never reads or writes
            # app preferences directly, and identifies the actual photo again.
            self.restart()
            self.camera()
            self.tap(self.accessible_target("last_photo"))
            self.tap(self.first_gallery_photo(self.gallery_ready()))
            current = self.position(self.review_ready())
            assert current[1] == position[1], "Photo count changed before Favorite recovery"
            for step in range(position[1]):
                if current == position:
                    break
                current = self.menu_navigate(1 if current[0] < position[0] else -1, current,
                                             f"favorite-recovery-{step}")
            assert current == position, "Could not return to the original Favorite photo"
        self.review_ready(position)
        assert self.photo_info("favorite-restore-info") == pending["filename"], (
            "Favorite recovery reached a different photo; refuse to change it")
        node = self.accessible_target("review_favorite", record="favorite/restore")
        if self.favorite_state(node) != pending["checked"]:
            self.tap(node)
        nodes = self.poll(lambda ns: (current := self.find("review_favorite", ns)) is not None and
                          self.favorite_state(current) == pending["checked"], "Initial Favorite state was not restored")
        assert self.position(nodes) == position, "Favorite recovery changed the review position"
        self.report["favorite_initial_state_restored"] = True
        self.pending_favorite = None
        self.save()

    def photos(self):
        self.tap(self.wait("last_photo"))
        nodes = self.gallery_ready()
        headings = [node.get("text", "") for node in nodes
                    if node.get("class") == "android.widget.TextView" and not node.get("resource-id") and
                    not node.get("clickable") == "true" and self.visible(node) and
                    re.search(r"(?:19|20)\d{2}", node.get("text", "")) and len(node.get("text", "")) < 80]
        assert headings, "No actual date heading is exposed above the gallery rows"
        self.snapshot("font2-gallery", nodes)
        nodes = self.gallery_selection()
        settings_tab = self.wait("gallery_tab_settings")
        self.target("gallery_tab_settings", settings_tab, record="font2/gallery-settings-tab")
        self.tap(settings_tab)
        self.settle(("settings_back",), CAMERA_HIDDEN + GALLERY_TABS + ("design_gallery_select",))
        for identifier in ("settings_quality", "settings_haptics", "settings_restore_purchases"):
            self.accessible_target(identifier, record="font2/" + identifier)
            self.snapshot("font2-" + identifier)
        self.tap(self.wait("settings_back"))
        nodes = self.gallery_ready()
        self.snapshot("font2-gallery-after-settings", nodes)
        for identifier in GALLERY_TABS:
            node = self.find(identifier, nodes)
            self.target(identifier, node, record="font2/" + identifier)
            assert (node.get("selected") == "true") == (identifier == "gallery_tab_gallery"), (
                "Gallery bottom navigation exposes the wrong selected page")
        self.tap(self.accessible_target("gallery_tab_camera", record="font2/gallery-camera-tab"))
        self.camera()
        self.tap(self.accessible_target("last_photo"))
        nodes = self.gallery_ready()
        self.check("200%: dated gallery rows, Settings Back and the Camera tab preserve gallery/camera navigation",
                   date_headings=headings)
        self.tap(self.first_gallery_photo(nodes))
        nodes = self.review_ready()
        origin = self.position(nodes)
        assert origin[1] >= 2, "Two seeded photos are required for actual adjacent-photo regression"
        self.snapshot("font2-review", nodes)
        origin_file = self.photo_info("font2-review-info")
        direction = 1 if origin[0] < origin[1] else -1
        neighbor = (origin[0] + direction, origin[1])
        self.menu_navigate(direction, origin, "font2-more-origin")
        menu_neighbor_file = self.photo_info("font2-menu-neighbor-info")
        assert menu_neighbor_file != origin_file, "Menu navigation changed the counter but not the photo"
        self.menu_navigate(-direction, neighbor, "font2-more-neighbor")
        assert self.photo_info("font2-menu-origin-info") == origin_file, "Menu return did not load the original photo"
        self.check("200%: More exposes accessible Previous/Next/Fit controls and menu navigation loads adjacent photos",
                   origin_position=origin, neighbor_position=neighbor, neighbor_file=menu_neighbor_file)
        self.swipe(self.image_gesture_target(), to_start=direction < 0, horizontal=True)
        self.review_ready(neighbor)
        neighbor_file = self.photo_info("font2-review-neighbor-info")
        assert neighbor_file != origin_file, "Swipe changed the counter but did not load a different photo"
        assert neighbor_file == menu_neighbor_file, "Swipe and menu navigation load different photos at the same position"
        self.swipe(self.image_gesture_target(), to_start=direction > 0, horizontal=True)
        self.review_ready(origin)
        restored_file = self.photo_info("font2-review-origin-info")
        assert restored_file == origin_file, "Opposite swipe did not restore the original photo"
        self.check("200%: left/right image swipes load actual adjacent photos and return",
                   origin_position=origin, neighbor_position=neighbor, origin_file=origin_file, neighbor_file=neighbor_file)

        self.favorite_roundtrip(origin, origin_file)
        self.phase = "200% gallery and review"

        photo = self.image_gesture_target()
        left, top, right, bottom = self.bounds(photo)
        point = [str((left + right) // 2), str((top + bottom) // 2)]
        started = time.monotonic()
        self.adb("shell", "input", "tap", *point)
        time.sleep(.1)
        self.adb("shell", "input", "tap", *point)
        self.report["double_tap_dispatch_seconds"] = round(time.monotonic() - started, 3)
        self.review_menu("font2-more-zoomed", origin, zoomed=True)
        self.close_review_menu(origin)
        self.snapshot("font2-review-zoom")
        self.swipe(self.image_gesture_target(), to_start=direction < 0, horizontal=True)
        nodes = self.review_ready(origin)
        assert self.photo_info("font2-review-zoom-drag-info") == origin_file, "Enlarged-image panning switched photos"
        self.review_menu("font2-more-after-zoom-drag", origin, zoomed=True)
        self.tap(self.accessible_target("review_zoom_reset", record="font2/review_zoom_reset"))
        self.menu_dismissed()
        self.review_menu("font2-more-after-fit", origin, zoomed=False)
        self.close_review_menu(origin)
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
        if smoke.pending_favorite is not None:
            try:
                smoke.restore_favorite(reopen=True)
            except Exception as exc:
                cleanup_errors.append("Favorite UI restore: " + str(exc))
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
