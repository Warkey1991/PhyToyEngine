# UI design and verification

The camera, photo list, photo review and settings follow Material Design navigation and Android accessibility principles. The implementation uses native Views and a shared `PageTopBar`; it does not claim Google certification or depend on a Compose migration.

## Structure

- Camera: app name/current style on the left; flash and settings on the right. Privacy and license information are only in settings. Camera controls remain outside the saved-image boundary.
- Photo list: newest first, photo count, three-column virtualized grid, explicit loading/empty/retry states. A photo opens review; Back returns to the list, while Continue shooting returns to the camera.
- Review: shared back/title/details bar, image fitted without changing its saved crop, one primary Continue shooting action.
- Settings: shared back/title bar, grouped camera/shooting/information preferences, independent About/Privacy/License entries, explicit reset confirmation. Dialogs keep the page's scroll position; reopening settings starts at the top.

## Tokens and behavior

| Token | Value / behavior |
| --- | --- |
| Surface | `#111318` |
| Primary | `#F2B84B` |
| On surface | `#F1F1F1` |
| Secondary text | `#C4C7C5` |
| Page title / subtitle | 22 sp / 12 sp |
| Body / main action | at least 12 sp / 14 sp; gallery corner metadata uses 11 sp with a complete accessibility label |
| Interactive target | at least 48 dp |
| App bar | 64 dp minimum; subtitle bars start at 88 dp; height grows with text |
| Camera style caption | 12 sp; height is at least 24 dp and otherwise the rounded-up actual `Paint.fontSpacing` converted to dp, plus 4 dp |
| Camera footer | `178 dp + styleCaptionHeightDp`; preview bottom inset uses that same calculated footer height |
| Review action footer | 88 dp minimum; button is at least 48 dp and its wrapping text/padding can increase the footer height |

Active text and icons use accessible contrast. Checked switches have a native checked state; selected styles also have a selection marker. Flash has an icon variation and an accessible mode description. Decorative graphics are excluded from the accessibility tree. Full-screen pages hide background controls and restore focus on exit. All image loading is bounded and performed off the UI thread.

Insets keep actions clear of system bars and cutouts. The shared back arrow and margins follow layout direction. Text uses sp and page content scrolls or measures naturally at large font sizes. The camera frame remains controlled by the selected photograph aspect.

The camera caption height follows the current display's scaled 12 sp font metrics. The caption and footer grow together, and `MainActivity` reserves the matching bottom space for the preview. This keeps the style caption above the controls and prevents the footer from covering the photograph viewport at large font sizes. Gallery and review use a wrapping app bar followed by content that fills the remaining height; review's primary action also grows with its text.

## Verification scope

`tools/run_android_product_smoke.py --check-library-settings` checks navigation, the accessible modal tree, capture/gallery retention, preference persistence and review-off capture. Compressed UIAutomator dumps are intentional: uncompressed dumps request views excluded from accessibility. Test swipes stay away from system back-gesture edges.

`tools/run_android_large_font_smoke.py` checks the installed app at system `font_scale=2.0`. It defaults to an emulator; an authorized physical test phone requires `--serial` and `--allow-physical-device`. `--expected-apk-sha256` pins the candidate before changing font scale. It requires camera permission to be granted and an existing gallery photo; it takes no new photos and does not change app preferences, permissions or locale. It restores the original font scale in `finally`. It verifies visible, labelled, enabled/clickable 48 dp targets for the four primary camera actions, settings Back/Privacy, gallery Back/item and review Back/Continue. It also exercises settings scrolling and the privacy dialog, gallery-to-review navigation, both Back paths, Continue shooting, and settled modal trees with background controls excluded. Screenshots and compressed node dumps accompany these checks.

The historical 0.15 large-font run is recorded in [`reports/release_candidate_0_15/emulator16k/large-font-final/evaluation.json`](../reports/release_candidate_0_15/emulator16k/large-font-final/evaluation.json): `passed=true` on emulator `emulator-5580`, a 540 × 960 px display at 240 dpi, with font scale applied at 2.0 and confirmed restored to 1.0. The run covers camera, settings, gallery and review; it recorded zero new photos. The camera screenshot was also visually checked for an unobscured style caption. Target bounds and labels are automated checks; screenshots require visual review and do not prove that every glyph is free of clipping.

Final reports under `reports/release_candidate_0_15/` distinguish physical and emulator results. The large-font run does not test capture quality or every camera profile at 200% text size. Manual TalkBack interaction and reading order, additional devices, display-size settings, landscape and large-screen/foldable behavior remain production acceptance work. These results are not Google certification or a complete TalkBack/accessibility pass.

## Primary references

- [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility): scalable text, body sizing, contrast, 48 dp touch targets, semantics and alternatives to gestures.
- [Android app bars](https://developer.android.com/develop/ui/views/components/appbar): consistent navigation, title and action structure.
- [Material app bars](https://m3.material.io/components/app-bars/overview).


## Visual refinement and camera purchases (0.16)

EV and zoom share the same 48 dp pill geometry; resolution is secondary metadata. The style rail fades only at edges with off-screen content. Settings keep title, current value and help inside one native control. Gallery images have compact corner badges, and review subtitles contain only size and saved state.

Four paid cameras have visible locks. Tapping opens a scrollable camera introduction with original illustrated artwork, an exact Play price (when available), a one-time purchase explanation, preview, restore and retry. A free live trial replaces the camera caption with “Preview only · unlock to shoot”. The shutter opens the introduction while access is locked; the capture entry point independently enforces it. Trial styles are not stored as startup choices. Only explicit purchase starts a Play sheet; no photo is taken automatically after payment. Header/back, all actions and help grow with font scale. Background accessibility is isolated and restored across nested Settings → Purchase navigation.

Client and emulator validation for 0.16 is recorded separately under `reports/release_candidate_0_16`; earlier 0.15 screenshots do not certify these changed screens. Real Play payment states need license-tester verification listed in PLAY_BILLING.md.
