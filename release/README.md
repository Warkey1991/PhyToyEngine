# Google Play release candidate

Version: **0.15.0-rc1**, version code **16**. This is a reviewable candidate, not a completed store submission. The engine is version **0.3.1**, retaining C ABI v2. The target product for this candidate is an offline Android camera on supported arm64/Vulkan 1.1 devices.

## Implemented

- Unified stable ADC half-up rounding across CPU, Python and Vulkan, with exact-boundary regressions. See [numeric contract](../docs/ENGINE_NUMERIC_CONTRACT.md).
- Shared denoise→sharpen order in CPU, Python and Vulkan; aligned Gaussian truncation, explicit unsupported-Gaussian errors, packed shared memory below Vulkan's 32KB guaranteed minimum.
- Bounded 8192-pixel-per-axis / 16,777,216-pixel image inputs, checked padded strides and C ABI error boundaries; stable per-thread error snapshots.
- High-resolution processed viewfinder, common supported host ISP modes for preview/still, adaptive processing cadence and thermal protection.
- Foreground camera lifecycle, permission explanation/recovery, camera restart action, stalled-viewfinder detection, safe stale callback checks, capture-state controls and bounded camera-thread shutdown.
- MediaStore publication verification and failure cleanup, bounded photo/review bitmaps, EXIF, thumbnail and modal review.
- Local photo list: newest-first, own-album migration, virtualized three-column grid, bounded background thumbnails and photo-to-list/camera navigation.
- Persisted camera settings: default/last style, resolution ceiling, grid, sound, haptics, optional immediate review and preference reset; browsing suspends camera repeating requests.
- Six styles, usable touch targets, button roles, TalkBack exposure/zoom actions, multilingual text, adaptive/monochrome icons and offline privacy information.
- Optimized release/benchmark builds, native JNI keep rules, external signing credentials, target SDK 37, Vulkan device filters, reproducible packaging checks and CI.

## Evidence and remaining device coverage

See the current machine-readable [validation summary](../reports/release_candidate_0_15/validation.json), packaging audit, product smoke results and ISP comparisons under that report directory. Historical reports remain in their original directories; they are not evidence that the latest release was tested on every device.

The completed engine changes pass **139 Python tests and 2/2 CTest checks**, including C ABI exception containment, allocation-safe diagnostic storage and error snapshots across threads. The compiled ISP shader uses 23,196 bytes of workgroup memory, below the 32,768-byte minimum budget. Diagnostic allocation failure was injected into error storage; the tests do not exhaustively inject allocation failures throughout the CPU/Vulkan pipelines.

The recorded APK/AAB packaging audit **passed**, including native 16KB alignment, but the artifacts are **unsigned** and retain the development ID `com.phytoy.sample`, so **store_ready remains false**. The current hashes identify the final candidate build with gallery/settings and engine 0.3.1. A production ID or signing change requires a new build, artifact audit and device checks against the actual submission hashes.

The physical SM-S9210 completed camera startup, offline privacy access and five background/resume cycles in the release-equivalent R8 build. It disconnected during the subsequent front-camera capture audit, so the six-style product smoke is **partial**. The 2×2 and 22×18 CPU/Vulkan f32/AHardwareBuffer comparisons passed all five stages. At 270×258, the CPU full-chain comparison **fails**: two sensor pixels differ by 1 DN, exceeding the unchanged 0.51-DN contract. Its ISP/output stages pass, and a separate comparison conditioned on the actual native sensor pixels also passes. The complete failed comparison, reproduction arguments and sensor coordinates remain in `validation.json`; the conditioned result does not turn the full chain into a pass. Large Vulkan f32/AHardwareBuffer results are not recorded because the physical device disconnected.

An isolated arm64 API 36 emulator reports an actual page size of **16,384 bytes**. The final native CPU and SwiftShader Vulkan f32 binaries complete one warmup plus three measured 22×18 frames, and all five stages satisfy the existing contract. Both ELF binaries have 16KB LOAD alignment. This establishes native loader and small-stage compatibility; emulator timings do not represent physical-device performance. The app audit is **not passed**: the initial attempt timed out at the privacy entry, and a later attempt passed privacy access and three background/resume cycles but failed review accessibility isolation. Final source fixes require a new app audit.

There is **no completed same-scene physical preview-versus-saved-photo measurement**. Synthetic preview-detail modeling supports the resolution/sampling changes, but its MTF/RMSE values are not measured photographic improvement. Record matched scene/lens/style/exposure pairs and compare detail, color/exposure, crop/aspect and orientation; also measure sustained preview cadence and thermal behavior on the final physical-device build.

Before production rollout, finish six-style capture/EXIF/return-to-preview tests, permission denial/restoration, backgrounding during capture, repeated camera/style switching, front-camera orientation, large font/TalkBack, storage-full save failure, camera contention, multi-window/foldable layouts and an unplugged 30-minute preview/capture run. Cover at least one additional GPU/vendor and the minimum supported Android version. Complete the final app audit in the 16KB-page environment in addition to the native runtime and artifact-alignment checks already recorded. Acceptance must match the exact signed bundle/profile hashes that will be uploaded.

## Submission inputs still required

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
  --check-library-settings \
  --output reports/product_release_smoke
```

This test saves actual photos and temporarily revokes/restores camera permission. It does not clear app data or delete existing photos. The benchmark is debug-signed for testing; upload the separately audited production-signed AAB, never the benchmark APK.
