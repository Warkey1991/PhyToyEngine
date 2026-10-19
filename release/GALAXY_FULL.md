# Pomi Camera — Galaxy Store Full Edition

This separate edition includes all camera styles from first launch, without in-app purchases, subscriptions, account login or restoration. All current styles can capture, save and be selected as the default camera offline. Future styles use the same bundled entitlement policy.

| Setting | Value |
| --- | --- |
| Gradle flavor | `galaxyFull` |
| Application ID | `com.ycolor.team.phytoy.camera.android.galaxyfull` |
| Launcher name | Pomi Camera |
| Proposed English store title | Pomi - Retro Digital Camera |
| Store channel metadata | `galaxyFull` |
| In-app purchases | None |
| Network permission | None |
| Signature | Existing shared release keystore |
| Publisher | Yuanjin Song |
| Support and privacy email | warkey1991@gmail.com |
| Privacy policy | https://sites.google.com/view/samsungmarketapp/home |

The package ID can be overridden with `phytoyGalaxyFullApplicationId` or `PHYTOY_GALAXY_FULL_APPLICATION_ID` before initial publication. It must differ from the existing Play and Galaxy IAP editions. Once published, retain it for updates. This build is a separate app and can coexist with either earlier edition.

## Build

From `android/`:

```sh
./gradlew --no-configuration-cache packageGalaxyFull \
  -PpackageStoreReady=true -PphytoyVersionName=0.17.8 -PphytoyVersionCode=3
```

The command uses the existing private `android/keystore.properties`, builds a signed APK and AAB, runs release Lint, checks signatures, native libraries, 16 KB alignment and profile assets, rejects billing/network permissions and payment SDK classes, and archives artifacts under `release-artifacts/packages/galaxyFull/`.

No Play billing public key or Samsung IAP configuration is needed for this edition. Publisher, support email and HTTPS policy configuration still apply. The local packaging gate checks artifact properties; it does not certify Seller Portal approval or device compatibility.

This edition uses its own `phytoyGalaxyFullPublisherName`, `phytoyGalaxyFullSupportEmail` and `phytoyGalaxyFullPrivacyPolicyUrl` Gradle properties (or corresponding `PHYTOY_GALAXY_FULL_*` environment variables). These override the shared BuildConfig fields only for `galaxyFull`, so About, privacy and support-email actions show this edition's identity. Strict packaging validates the identity belonging to the selected channel.

## Branding and user-facing copy

The icon uses the existing yellow camera artwork with the Tovi wordmark removed and the camera centered, as requested. Its launcher assets and market icon are separate flavor assets; the other editions keep their existing icons. Artwork and generation details are in `android/market/galaxy-full/`.

English, Simplified Chinese, Japanese, Korean, German, French, Spanish, Portuguese, Indonesian, Hindi and Thai overlays update the app name, accessibility label, camera permission explanation, About, support hint and offline privacy copy. Purchase and restore rows are hidden. The purchase screen entry point is disabled; no payment store connection is created. Photos written by this edition use Pomi Camera in their EXIF software metadata.

## Seller Portal

Create a new app record for this application ID and upload the signed APK. Do not configure IAP products for this app. Whether the app download itself is free or paid is a Seller Portal business setting, independent of this package's bundled access; no download price has been inferred or changed here.

Use listing copy stating that all camera styles are included, with no in-app purchases or subscriptions. Do not reuse the existing IAP edition's pricing/restore copy. Update the public privacy policy and store screenshots to match this edition before submitting.

The supplied policy URL was reachable through the browser on 2026-10-19. It currently contains general device-data, personalized advertising and Firebase language, which differs from this edition's no-advertising, no-network behavior. The in-app policy describes the actual full-edition behavior. Configuring the URL in the app does not publish or edit that external website.
