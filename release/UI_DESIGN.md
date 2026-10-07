# UI design and verification — 0.17

Native Views share PageTopBar, a dark surface and warm gold actions. The selected camera remains the main heading. Privacy, licenses and support live in settings.

## Flows

- Camera: tap EV/zoom pills to adjust supported hardware values or reset. Keep grid/tap-focus/pinch controls. During capture, a stable control snapshot drives preview/3A/still; conflicting actions stay disabled until complete.
- Camera lifecycle: a shared single-worker queue orders native create/close, Camera2 configuration and resource retirement across Activity recreation. Main cancels and detaches immediately without joining. Generation, resource and engine identity checks reject late callbacks; ordinary Pause retains the Texture, while destruction releases it after native shutdown.
- Style rail: free cameras shoot immediately. Locked cameras open free live trials directly; explicit Unlock or locked shutter opens introduction/price. Trial styles are not persisted. No automatic capture or checkout follows entry.
- Purchase: compact camera art, description, matching original/processed illustrations, store-supplied price, explicit purchase, preview, restore/retry and store scope. Purchase/preview actions stay visible at the bottom while details scroll. Below 600 dp with font scale at least 150%, actions stack to avoid splitting words; normal/wide layouts use equally tall buttons in a row. Illustrations use an artificial scene and shipped reference profiles; actual photos depend on device/light. No fallback fake price.
- Gallery: cached thumbnails and newest-first local photos, bounded background scans, 3–6 responsive columns, loading/empty/retry. Deletion cancels stale scans and removes the photo/index.
- Review: bounded pinch/double-tap zoom up to 4×, reset, previous/next, share/delete and primary Continue shooting. Back returns to gallery. Delete first confirms, then requests system consent when required; failures retain the photo. Sharing grants temporary read access only to the selected content URI.
- Settings: short hints, separate detailed help, persistent purchase/restore result, reset confirmation and configured publisher contact/public policy.

## Layout and accessibility

Controls have at least 48 dp targets, accessible labels and state. Text uses sp and grows/wraps; purchase/settings scroll. Portrait uses a bottom camera rail; landscape uses a side toolbar with a bottom style rail. Review separates navigation from Share/Delete at large fonts; its narrow side action region can scroll while Continue shooting stays fixed when space permits. In a landscape window below 420 dp high at 150% or larger fonts, the entire action column scrolls so that the primary action cannot squeeze the tools below a complete touch target. Large-font Share/Delete each occupy a full row in a narrow side region. The first review zoom hint hides after three seconds or zooming. Insets avoid system bars/cutouts. Preview/saved crop follows the selected aspect and orientation; review fits the image initially and bounds pan when zoomed.

Background controls are removed from the accessibility tree while a modal page is shown. Pages use pane/window-change announcements and restore ordinary keyboard focus without forcing screen-reader focus. The photo exposes accessible zoom actions so gestures are not the only route. Camera/review state survives rotation, with decoding cancelled/restarted safely.

## Validation

Use tools/run_android_017_ui_smoke.py on an isolated emulator with explicit serial, package and installed APK SHA-256. It requires permission and app-owned seed photos. Checks cover EV/zoom, direct trials/explicit unlock, 100/200% fonts, privacy, photo double tap/reset, neighboring photos, share chooser + Back, delete confirmation + Cancel, 48 dp targets and unchanged MediaStore IDs/permission/locale with font restoration. It never captures, pays, sends a share or confirms deletion.

The channel smoke verifies store-specific provider/scope/privacy and disabled checkout, optionally EV/zoom, fixed actions and existing-photo review at 200% fonts. The capture smoke checks 640×360/320 dp landscape camera targets at 200%, Home/Resume in the same process and front/back switching when available, creates one disposable mono photo and checks review rotation. It validates the initial photos' non-mono EXIF, the new mono EXIF and the new ID's fresh save-log URI before confirming deletion; settings and initial photo IDs must be restored. These tests operate only on an explicit hash-verified task-owned emulator. Reports retain screenshots, failures and exact hashes in reports/release_candidate_0_17. Visually inspect screenshots: bounds alone cannot certify every glyph.

Five JVM lifecycle tests cover nonblocking submission during blocked work, close-before-create ordering, cancellation before install, cancellation before UI publication, and one-time release during fallback/retirement. Retain the earlier ANR diagnostics separately from final regression evidence; drawing/system pressure in that stack does not prove a native deadlock.

Real physical capture quality, pinch/multitouch, successful system consent, complete manual TalkBack, foldables, sustained thermal behavior and store checkout require final device/store tests. Earlier 0.15/0.16 UI reports refer to different builds/flows.

[Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility), [Android adaptive layouts](https://developer.android.com/about/versions/16/behavior-changes-16#adaptive-layouts).
