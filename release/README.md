# ToviCam 0.17 release candidate

Version **0.17.0 / 18**, engine **0.3.1**, C ABI v2. Image processing remains local; Android API 26+, arm64, Vulkan 1.1.

| Edition | Permanent application ID | Payment SDK |
| --- | --- | --- |
| Google Play | `com.ycolor.team.phytoy.camera.android.gpapp` | Play Billing 9.1.0 |
| Galaxy Store | `com.ycolor.team.phytoy.camera.android.galaxyapp` | Samsung IAP 6.5.2 |

Purchases are separate and do not automatically transfer between stores.

## Improvements

- EV/zoom panels, supported hardware ranges, reset and quick zoom choices. Capture snapshots controls; sound and flash follow the exposure callback.
- Camera creation, Camera2 configuration and native destruction run on a shared serial background queue. Main invalidates and detaches retired sessions immediately; generation/resource/engine checks reject stale results. A destroyed Texture is released only after its native owner closes.
- Selecting locked cameras opens free viewfinder trials immediately. Explicit Unlock/locked shutter opens purchase details, with matching original/processed illustrations from shipped profiles. The artificial source and photographic limits are labelled.
- Review adds bounded pinch/double-tap zoom, reset, previous/next, sharing and confirmed deletion, loading/cancellation/retry. Sharing grants access only to the selected photograph. System deletion consent is handled.
- Compact settings with detailed help, visible purchase/restore results, optional public support/contact. Camera/review adapt to short/wide windows and preserve state across rotation. Purchase actions stay fixed; narrow large-font buttons use wider rows, and the first review zoom hint hides after three seconds.
- Independent store billing, permissions and notices. Samsung receipts are verified, acknowledged and cached with Android Keystore protection. Missing billing configuration keeps paid capture locked.
- Build inputs for publisher, email and public privacy URL; strict production gates for publisher, billing and signing configuration.

## Validation scope

Current evidence is in `reports/release_candidate_0_17/` and must pin final artifact/source hashes. Emulator checks do not certify physical-camera quality, Samsung checkout, store restoration, thermal behavior or a signed submission.

The earlier candidate's ANR diagnostic is retained. Its stack showed Main drawing under high guest CPU/memory pressure, which does not establish a native deadlock. Moving synchronous native lifecycle work off Main addresses a separate identified blocking risk; final regression results must be recorded against the rebuilt candidate.

The 0.15/0.16 reports are historical. Their development package, old purchase flow and different hashes do not certify 0.17. Some historical UI runs failed accessibility-dump collection. The unchanged engine has historical 139 Python/2 CTest passes; its full-chain physical CPU/GPU comparison retains two 1-DN sensor differences. Conditioned ISP passes did not resolve that discrepancy.

## Required before upload

1. Supply publisher/store identity, support/privacy email and a stable public HTTPS policy URL. Generate [the policy](privacy-policy.html) from the app's text using `python3 tools/prepare_privacy_policy.py --publisher 'Actual publisher' --email 'actual@example.com'` with real values, then host/verify it without login. The committed output uses `--draft` and is not ready for publication. Build with `phytoyRequirePublisherConfigured=true`.
2. Configure four permanent unlocks in each store and complete real purchase/cancel/pending/restore/acknowledge/refund/network tests. Read [Play setup](PLAY_BILLING.md) and [Galaxy setup](GALAXY_BILLING.md). Automated checks do not make real purchases.
3. Provide release signing outside the repository; audit each final signed APK/AAB, retain mappings/native symbols and record upload/delivered-app certificates. Galaxy updates retain the Galaxy signing identity.
4. Complete store listing, actual-app screenshots, privacy/data declarations, audience and rating. Existing listing/assets are drafts; synthetic style illustrations do not prove physical photographic quality.
5. Test final signed builds on physical devices: preview/save consistency, front/back lenses, capture/background race, denied permission, low storage, camera contention, manual TalkBack, minimum API, another GPU/vendor, foldable/multi-window, unplugged 30-minute session, and real 16 KB runtime.
6. Complete account-specific production/testing access. Applicable new personal Play accounts require 12 testers opted in continuously for 14 days. Galaxy requires commercial seller status and applicable Android Developer Verification for the final package/signing identity.

[Build commands](ANDROID_BUILD.md) produce separate candidates. Benchmark APKs are development-signed test artifacts. Rebuild/audit/test production artifacts after all public inputs and signing changes.

Official references: [Play target API](https://developer.android.com/google/play/requirements/target-sdk), [Play testing](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en), [Play privacy](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en), [16 KB support](https://developer.android.com/guide/practices/page-sizes), [Samsung seller preparation](https://developer.samsung.com/galaxy-store/prepare.html), [Samsung privacy/distribution](https://developer.samsung.com/galaxy-store/distribution-guide.html), [Samsung ADV notice](https://seller.samsungapps.com/notice/getNoticeDetail.as?csNoticeID=0000011990).
