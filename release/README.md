# Google Play release candidate

Version: **0.16.0-rc1**, version code **17**. This is a reviewable candidate, not a completed store submission. The engine is version **0.3.1**, retaining C ABI v2. Image processing remains local on supported arm64/Vulkan 1.1 devices; optional camera purchases and restoration use Google Play. Engine source is unchanged from 0.15.

## Implemented

- Refined camera EV/zoom controls and style-rail fades, compact settings entries, gallery corner badges and simplified photo-review subtitles.
- Google Play Billing 9.1.0: two free cameras and four separate non-consumable unlocks, free live trials with capture gating, localized Play prices, pending/cancel/restore/acknowledgment handling, signed local receipts and authoritative-query revocation. Requested US price is USD 9.99 per paid camera. See [purchase setup and remaining payment tests](PLAY_BILLING.md).

- Unified stable ADC half-up rounding across CPU, Python and Vulkan, with exact-boundary regressions. See [numeric contract](../docs/ENGINE_NUMERIC_CONTRACT.md).
- Shared denoise→sharpen order in CPU, Python and Vulkan; aligned Gaussian truncation, explicit unsupported-Gaussian errors, packed shared memory below Vulkan's 32KB guaranteed minimum.
- Bounded 8192-pixel-per-axis / 16,777,216-pixel image inputs, checked padded strides and C ABI error boundaries; stable per-thread error snapshots.
- High-resolution processed viewfinder, common supported host ISP modes for preview/still, adaptive processing cadence and thermal protection.
- Foreground camera lifecycle, permission explanation/recovery, camera restart action, stalled-viewfinder detection, safe stale callback checks, capture-state controls and bounded camera-thread shutdown.
- MediaStore publication verification and failure cleanup, bounded photo/review bitmaps, EXIF, thumbnail and modal review.
- Local photo list: newest-first, own-album migration, virtualized three-column grid, bounded background thumbnails and photo-to-list/camera navigation.
- Persisted camera settings: default/last style, resolution ceiling, grid, sound, haptics, optional immediate review and preference reset; browsing suspends camera repeating requests.
- Redesigned camera header, shared page bars, six styles, 48 dp touch targets, button roles, TalkBack exposure/zoom actions, multilingual text, adaptive/monochrome icons and offline privacy information. Camera captions and footer grow with font metrics; privacy and license entries live in settings. See [UI design and verification](UI_DESIGN.md).
- Optimized release/benchmark builds, native JNI keep rules, external signing credentials, target SDK 37, Vulkan device filters, reproducible packaging checks and CI.

## Evidence and remaining device coverage

Current UI/Billing validation is recorded in the [0.16 validation summary](../reports/release_candidate_0_16/validation.json), with exact artifact hashes and report scopes. The [0.15 validation summary](../reports/release_candidate_0_15/validation.json) remains historical evidence for the unchanged engine and previous camera release, described below. Those physical-device passes do not certify the new purchase integration. Real Play checkout has not been tested in this unconfigured candidate.

The final 0.16 release-equivalent R8 benchmark on SM-S9210 (API 36) passes **nine product checks** and **eight unconfigured billing UI checks**. Product coverage includes front/back photography, both free styles saved/reviewed, both gallery return paths, three foreground/background cycles, settings persistence and denied/restored camera permission. Billing covers all four locked styles, preview-only capture gates, restore/default persistence, process restart and 200% purchase-page controls. Billing checks record no photo changes, no checkout taps and exact font restoration; product checks deliberately retain their new test photos. All current reports must match the benchmark SHA-256 recorded in the validation summary. Older 0.16 attempts and superseded APK results remain separately identified; they are not combined into a final pass.

The final Android build passes **15 JUnit tests** (9 entitlement-ledger and 6 receipt-signature cases). Release lint records **0 errors and 63 warnings** in the [current report](../reports/release_candidate_0_16/lint-results-release.txt). These tests verify client boundaries, not real Google Play transactions. The engine tests below are historical 0.15 evidence for engine 0.3.1, not a fresh 0.16 engine run.

The completed engine changes pass **139 Python tests and 2/2 CTest checks**, including C ABI exception containment, allocation-safe diagnostic storage and error snapshots across threads. The compiled ISP shader uses 23,196 bytes of workgroup memory, below the 32,768-byte minimum budget. Diagnostic allocation failure was injected into error storage; the tests do not exhaustively inject allocation failures throughout the CPU/Vulkan pipelines.

The [0.16 APK/AAB packaging audit](../reports/release_candidate_0_16/packaging.json) **passed**, including native 16KB alignment, but the artifacts are **unsigned** and retain the development ID `com.phytoy.sample`, so **store_ready remains false**. Each report identifies its own build; 0.15 hashes are not the 0.16 artifacts. A production ID or signing change requires a new build, artifact audit and device checks against the actual submission hashes.

The historical 0.15 installed release-equivalent R8 benchmark on SM-S9210 **passes all nine product checks**: startup, settings-only privacy access, five background/resume cycles, front/back cameras, all six styles saved/reviewed, gallery return paths, settings persistence across process restart, review-off saving and denied/restored camera permission. Its installed APK hash matches the recorded final benchmark artifact. Original preferences and camera permission were restored; photos were retained.

The historical 2×2 and 22×18 CPU/Vulkan f32/AHardwareBuffer comparisons passed all five stages. The final **0.3.1** physical 270×258 Vulkan f32 and AHardwareBuffer runs complete three frames with zero resource allocations after warmup; AHardwareBuffer records three zero-copy frames and zero new imports. Sensor DN is bit-exact across both GPU paths and the historical 0.3.0 CPU dump. Independent full-chain comparisons still **fail** at two sensor pixels by 1 DN, exceeding the unchanged 0.51-DN contract; the other four stages pass. Separate ISP/output comparisons conditioned on actual native sensor DN pass. [Complete native evidence](../reports/release_candidate_0_15/gpu_isp/physical_270x258_summary.json) retains all failures, coordinates, scopes and hashes; a conditioned pass does not turn the full chain into a pass.

The historical 0.15 isolated arm64 API 36 emulator reports an actual page size of **16,384 bytes**. Final native CPU and SwiftShader Vulkan complete one warmup plus three measured 22×18 frames and pass all five stages. The exact ADC half-tie fixture also passes: 64.5 DN becomes 65 DN in both backends. ELF binaries have 16KB LOAD alignment. The 0.15 installed app checks **pass** for startup, privacy, three lifecycle cycles, gallery/review, persistent settings, review-off capture and permission recovery. This focused run reuses prior photos; six-style capture is covered by the historical 0.15 physical run, with earlier emulator capture evidence retained separately. Emulator timings do not represent physical-device performance.

The historical 0.15 **200% font-scale audit passes** for camera, settings, gallery and review navigation, visible labelled controls, 48 dp touch targets and settled modal accessibility isolation. Visual inspection confirms that the camera caption is no longer clipped. The script restores the original font scale (1.0) and does not take photos or change app preferences, permissions or locale. This is not a complete manual TalkBack certification.

There is **no completed same-scene physical preview-versus-saved-photo measurement**. Synthetic preview-detail modeling supports the resolution/sampling changes, but its MTF/RMSE values are not measured photographic improvement. Record matched scene/lens/style/exposure pairs and compare detail, color/exposure, crop/aspect and orientation; also measure sustained preview cadence and thermal behavior on the final physical-device build.

Before production rollout, complete backgrounding during capture, manual TalkBack, storage-full save failure, camera contention, multi-window/foldable layouts and an unplugged 30-minute preview/capture run. Cover at least one additional GPU/vendor and the minimum supported Android version. The historical 0.15 lint has **0 errors and 35 warnings**; the report retains orientation/large-screen adaptation, obsolete resources and reviewed native View/accessibility warnings. Acceptance must match the exact production-signed bundle/profile hashes that will be uploaded.

## Submission inputs still required

Configure the four Play products, their permanent `unlock` purchase options, regional pricing and public RSA key; perform the license-tester payment matrix in [PLAY_BILLING.md](PLAY_BILLING.md). Empty-key builds deliberately cannot sell or grant paid cameras. Device-side receipt verification is implemented; a trusted purchase-verification/RTDN backend is not deployed and remains a production fraud-resistance decision.

1. **Permanent application ID.** Development defaults to `com.phytoy.sample`; the store gate rejects it. Choose the final ID before the first upload and reuse it for all updates. Gradle accepts `-PphytoyApplicationId=...` or `PHYTOY_APPLICATION_ID`.
2. **Upload signing identity.** Supply the four environment variables documented in [Android build/signing instructions](ANDROID_BUILD.md). No private key has been created or committed. Enable Play App Signing as appropriate for the account. Keep upload certificates and signing keys backed up outside the repository.
3. **Publisher and privacy contact.** Complete the marked fields in [privacy-policy.html](privacy-policy.html), add the same contact to the in-app privacy text, host the completed HTML on a public stable HTTPS URL, and verify it without login. The draft itself must not be submitted as the final policy. [Google's privacy-policy requirements](https://support.google.com/googleplay/android-developer/answer/10144311?hl=en).
4. **Play Console content.** Use the [listing draft](google-play-listing.md), original [store assets](store-assets/) and [Data safety worksheet](data-safety.md). Capture the final promotional screenshots using an owned scene in the exact final build. Set countries, price, support details, audience and IARC content rating. The code does not infer business/account declarations.
5. **Testing and production access.** Complete internal/closed testing and inspect the Play pre-launch report. For personal developer accounts created after 2023-11-13, the current requirement is at least 12 continuously opted-in testers for 14 days before applying for production access. Check the actual account's Console requirements. [Official testing requirements](https://support.google.com/googleplay/android-developer/answer/14151465?hl=en).

The current Google Play target requirement for new apps/updates is API 36 or higher from 2026-08-31; target 37 satisfies that numerical gate. [Official target API requirements](https://developer.android.com/google/play/requirements/target-sdk). Native packaging uses NDK 28 and 16KB alignment; an alignment pass is distinct from runtime page-size validation. [Official page-size guidance](https://developer.android.com/guide/practices/page-sizes).

## Reproduce

Follow [ANDROID_BUILD.md](ANDROID_BUILD.md) for the release APK/AAB and artifact audit. On a dedicated device with the release-equivalent benchmark installed:

```sh
python3 tools/run_android_product_smoke.py \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --package com.phytoy.sample \
  --check-library-settings --skip-captures \
  --output reports/product_release_smoke
```

With --skip-captures, this test omits the six-style shooting sequence and reuses gallery photos; its review-off check still saves a photo with an unlocked/free camera. This test saves actual photos and temporarily revokes/restores camera permission. It does not clear app data or delete existing photos. The benchmark is debug-signed for testing; upload the separately audited production-signed AAB, never the benchmark APK.

To repeat only library/settings/permission checks using existing photos, add `--skip-captures`; its report explicitly excludes six-style capture verification.

For the dedicated device's large-font audit (camera permission and an existing photo required):

```sh
python3 tools/run_android_large_font_smoke.py \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --serial emulator-5580 \
  --output reports/large_font_smoke
```

This script defaults to emulators. An authorized physical test phone requires an explicit `--serial` and `--allow-physical-device`; `--expected-apk-sha256` pins the installed candidate. It restores the exact previous system font scale in `finally`.

For the new unconfigured purchase UI on a dedicated device:

```sh
python3 tools/run_android_billing_smoke.py --adb "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --serial emulator-5580 --output reports/billing_smoke
```

By default this requires an emulator; an authorized test phone requires `--allow-physical-device` and its explicit serial. Add `--expected-apk-sha256` to verify the installed candidate. This checks the four trial/capture gates, disabled unconfigured checkout, restore/default behavior and 200% purchase-page controls. It never taps a purchase button or grants a fake entitlement. It restores the original font scale and visible free camera via UI; it does not claim byte-for-byte restoration of inaccessible legacy paid preferences.
