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
| Body / main action | at least 12 sp / 14 sp |
| Interactive target | at least 48 dp |
| App bar | 64 dp minimum; subtitle bars start at 88 dp; height grows with text |

Active text and icons use accessible contrast. Checked switches have a native checked state; selected styles also have a selection marker. Flash has an icon variation and an accessible mode description. Decorative graphics are excluded from the accessibility tree. Full-screen pages hide background controls and restore focus on exit. All image loading is bounded and performed off the UI thread.

Insets keep actions clear of system bars and cutouts. The shared back arrow and margins follow layout direction. Text uses sp and page content scrolls or measures naturally at large font sizes. The camera frame remains controlled by the selected photograph aspect.

## Verification scope

`tools/run_android_product_smoke.py --check-library-settings` checks navigation, the accessible modal tree, capture/gallery retention, preference persistence and review-off capture. Compressed UIAutomator dumps are intentional: uncompressed dumps request views excluded from accessibility. Test swipes stay away from system back-gesture edges.

Final reports under `reports/release_candidate_0_15/` distinguish physical and emulator results. Manual TalkBack interaction, additional devices and large-screen/foldable behavior remain production acceptance work; a screenshot or tree check alone is not a full accessibility certification.

## Primary references

- [Android accessibility](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility): scalable text, body sizing, contrast, 48 dp touch targets, semantics and alternatives to gestures.
- [Android app bars](https://developer.android.com/develop/ui/compose/components/app-bars): consistent navigation, title and action structure.
- [Material app bars](https://m3.material.io/components/app-bars/overview).
