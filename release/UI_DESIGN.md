# ToviCam UI implementation — 0.17

Updated 9 October 2026. The implementation now follows the page structures of the nine supplied PNG designs. The earlier black-and-gold pass and its reports remain historical evidence; current APK hashes, screenshots and scoped results live in `reports/ui_design_match_20261008/`.

`ToviTheme` uses near-black surfaces, dark gradient cards, gold emphasis, white body text and muted secondary text. `PageTopBar` centers the heading. Camera art is decoded from decorative regions of the supplied designs, while text, focus, sliders, ownership badges, prices and actions are native controls. See `UI_ASSETS.md` and `UI_REFACTOR_PLAN.md` for the asset provenance and page mapping.

## Page behavior

- Camera: compact style capsule, circular flash/settings, six-card artwork rail, white/gold shutter, last-photo thumbnail and lens arrows. Integer zoom uses the selected focal preset without a duplicate readout; tapping the selected preset opens continuous adjustment. Intermediate values show their actual multiplier. EV uses a bubble, sun and vertical slider beside the actual focus point; small/large-font windows use a horizontal control. All ranges and feedback use existing camera callbacks.
- Style details: Plastic uses a hero and large film-frame illustration; Street uses an immersive hero with an overlaid back control and three clearly disclosed design illustrations. The other detail pages have a native six-camera rail that opens each camera's detail with its actual entitlement. One primary unlock/use action stays in the dock. Preview/restore/retry, complete store status and optional real original/effect comparison remain in the scrolling body. No price or ownership is fabricated.
- Gallery: real local dates, three columns on ordinary phones, two for enlarged fonts, up to six on wider screens, rounded photos and style badges. A left-aligned Gallery title has Select/Done. Selection supports system sharing of one or multiple real app-owned URIs. The bottom navigation is one shared plate with a gold selected dot. Existing scan/cache/scroll-anchor and empty/retry behavior remain.
- Review: the photo is the primary surface; Back, actual position and More overlay it. A compact camera-art metadata card and actual parameter columns precede four circular Favorite/Share/Delete/Info actions and Continue shooting. More contains accessible previous/next/fit actions. Favorites are independent UI preferences. EXIF is read asynchronously without modifying a photo; absent values are omitted. Complete-image fit, pinch/double-tap zoom, bounded panning and fit-only horizontal photo navigation remain. Sharing and confirmed/system-authorized deletion retain their callbacks.
- Settings: centered title and grouped dark cards, compact rows, gold line icons and toggles, true right-aligned values and an optional help dialog. Shooting/Experience/App use existing capabilities; additional style/privacy/license/reset controls remain in More settings. GPS recording and new storage/rotation behavior are outside the existing capture contract.

## Boundaries and verification

Camera2/3A, parameter snapshots, capture crop/rotation/mirroring, image encoding and EXIF writing, PhotoStore/PhotoLibrary, SettingsStore, billing authorization, SDK and native engine/profile/shader remain unchanged. MainActivity modifications only bind presentation insets/fullscreen and camera-detail navigation/entitlement queries.

Controls retain at least 48dp actual touch targets; large fonts and compact windows reflow or scroll. Captured photos are never cropped to imitate a design. Background accessibility is hidden by modal pages; explicit parameter/zoom/photo-navigation controls remain available. Runtime smoke tools check the exact installed APK hash, task-owned emulator identity, real UI values and unchanged photo hashes/IDs, permissions, language and restored font/display settings. Tests open sharing only to return, cancel deletion, restore Favorite state and never capture, pay, send a photo or operate the attached physical phone.

Current build and screenshot evidence belongs to the exact hash in `reports/ui_design_match_20261008/build-summary.json` and its evaluation files. These tests do not certify physical capture quality, real store checkout/entitlement restoration, successful system deletion consent, complete pinch/multitouch, every locale/form factor or manual TalkBack. Final source/build/visual scope is recorded in that report, rather than inferred from touch bounds alone.

## UI polish — 0.17.6 / 24

10 October 2026. Follow-up to the release UI audit:

- Plastic's normal-font copy and camera illustration occupy separate horizontal regions. Narrow windows and large fonts stack the image and copy. Heroes fit their image boundaries; decorative tiles keep their fill policy.
- Street retains its camera edges and background margin, using a wider image-only source region below the design's baked back button. The title follows the image in normal layout instead of overlapping the camera.
- Settings keeps Shooting/Experience/App on the main page, with privacy and help ahead of About/More. Default camera, remember-last-camera, licenses and reset live on a secondary More settings page. Toolbar and system Back return to the main settings page first; closing the overlay still applies the existing host callback.
- Style switching fades the previous preview out for 100 ms, draws a 200 ms transition between the old and new frame boundaries, and fades the new preview in for 150 ms after its first presented frame. The overlay animates drawn bounds, so the TextureView receives only its final geometry change. Disabled system animations skip motion; pause/error/lens changes cancel pending UI callbacks and restore the selected style's final viewport.

The follow-up leaves camera requests, captured parameter snapshots, encoding/EXIF, storage, settings persistence, store authorization and engine sources unchanged. Build and scoped UI evidence are in `reports/ui_polish_0_17_6_20261010/`; the earlier 0.17.0 screenshots above remain historical evidence.
