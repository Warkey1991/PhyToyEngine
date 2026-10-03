# Android release candidate build

The current candidate is **0.16.0-rc1 / versionCode 17**. A passing build and
packaging audit do not constitute device acceptance or permission to publish.
The app currently supports Android API 26+, arm64-v8a, and Vulkan 1.1. The two
Vulkan manifest declarations represent feature level 1 and API version 1.1
separately, so Play can filter devices correctly.

## Build without signing credentials

Use JDK 21, Android platform `android-37.0`, build-tools 36.0.0, NDK
28.2.13676358, and CMake 3.22.1. Set `ANDROID_SDK_ROOT` or configure the ignored
`android/local.properties`; no user-specific paths are committed. The wrapper
uses Gradle 9.5.0 with the official distribution SHA-256 pinned. AGP is 9.3.1.

```sh
cd android
./gradlew :sample:assembleRelease :sample:bundleRelease :sample:lintRelease :sdk:assembleRelease
cd ..
python3 tools/check_android_release.py \
  --apk android/sample/build/outputs/apk/release/sample-release-unsigned.apk \
  --aab android/sample/build/outputs/bundle/release/sample-release.aab \
  --allow-unsigned --output reports/release/packaging.json
```

No signing credentials means an explicitly unsigned APK and AAB, including when
Gradle displays a task named `signReleaseBundle`. The audit reads the files to
verify the actual signing state. Unsigned files cannot be installed or submitted
to Google Play. Debug builds and the optimized benchmark build use Android's
development certificate and must never be submitted as release builds.

## Permanent application ID and version

The default `com.phytoy.sample` preserves development-install upgrades. The store
packaging gate rejects it. Choose and record the final application ID before the
first Play release; **changing it after first publication creates a different
app**, rather than an update. Gradle properties take precedence over environment
variables:

| Gradle property | Environment variable | Default |
| --- | --- | --- |
| `phytoyApplicationId` | `PHYTOY_APPLICATION_ID` | `com.phytoy.sample` |
| `phytoyVersionCode` | `PHYTOY_VERSION_CODE` | `17` |
| `phytoyVersionName` | `PHYTOY_VERSION_NAME` | `0.16.0-rc1` |

For example, supply the actual approved ID using `-PphytoyApplicationId=...` to
the build; no unapproved production ID is invented by this repository. Every
published update requires a greater versionCode than the last Play upload.

## Billing configuration

See [PLAY_BILLING.md](PLAY_BILLING.md) for the four non-consumable products and USD 9.99 target pricing. Set the public Play licensing RSA key using `phytoyPlayBillingPublicKey` or `PHYTOY_PLAY_BILLING_PUBLIC_KEY`. Empty configuration leaves paid photography and purchases unavailable while free cameras and trials work. Production signing commands below enforce `phytoyRequireBillingConfigured=true`. This gate is additional to artifact/signing checks; it does not prove the products are activated or real payments tested.

## Signing an approved candidate

Supply all four environment variables through your local secure environment or
a protected release runner: `PHYTOY_KEYSTORE`, `PHYTOY_STORE_PASSWORD`,
`PHYTOY_KEY_ALIAS`, and `PHYTOY_KEY_PASSWORD`. The keystore must already exist.
The build fails if credentials are partially configured. Do not put passwords on
the command line, enable shell tracing, or commit a keystore or credentials file.
This repository neither generates signing keys nor uploads to a store.

```sh
cd android
./gradlew :sample:assembleRelease :sample:bundleRelease :sample:lintRelease \
  -PphytoyRequireSignedRelease=true -PphytoyRequireBillingConfigured=true
cd ..
python3 tools/check_android_release.py \
  --apk android/sample/build/outputs/apk/release/sample-release.apk \
  --aab android/sample/build/outputs/bundle/release/sample-release.aab \
  --store-ready --expected-application-id "$PHYTOY_APPLICATION_ID" \
  --expected-cert-sha256 "$PHYTOY_UPLOAD_CERT_SHA256" \
  --output reports/release/packaging.json
```

`PHYTOY_UPLOAD_CERT_SHA256` is the expected public certificate fingerprint,
independently checked against the intended upload key. The APK/AAB certificate
must match, verify cryptographically, and differ from an Android debug
certificate. With Play App Signing, the upload certificate differs from the key
Play uses to sign delivered APKs; separately record both in release management.

## What the gate verifies

- APK and AAB versions, application ID, minimum and target API levels agree.
- Release is not debuggable or test-only, and cleartext traffic is disabled.
- Only the supported arm64 runtime is packaged; each native library's ELF LOAD
  segments support 16 KB pages and do not permit writable executable segments.
- GNU_RELRO protection rounded to 16 KB pages does not overlap non-RELRO writable
  memory. A harmless partially filled RELRO end page is permitted when the linker
  leaves sufficient space before writable data, as Android's Bionic loader does.
- Uncompressed APK native entries are 16 KB ZIP aligned; AAB configuration asks
  bundletool for `PAGE_ALIGNMENT_16K` or better.
- APK/AAB native files agree, and packaged profile hashes match source assets.
- Signing state and, for a signed store candidate, the expected certificate match.

The JSON report records SHA-256 hashes and public metadata only. Rebuild after
every source/resource change, then rerun the audit on those final files. Keep
`outputs/mapping/release/mapping.txt` and
`outputs/native-debug-symbols/release/native-debug-symbols.zip` alongside each
candidate for crash retracing. `benchmark` inherits release R8/resource
optimization and retains diagnostic logs so Camera2 smoke checks can exercise the
optimized app; it remains signed with the development key.

## CI and remaining release gates

`.github/workflows/release-checks.yml` runs native/reference tests, failure-focused
packaging tests, release/benchmark builds, release lint, and unsigned packaging
audit. Official action commits and Android tool package versions are fixed. CI
does not receive signing credentials and retains candidates, mapping, symbols,
lint, and the audit report for 14 days. A workflow added locally must still be
committed and run on the repository's CI host before claiming CI passed.

Real camera capture, preview/still consistency, permission denial/recovery,
background/foreground behavior, thermal behavior, and save/EXIF checks remain
device gates. Test on a real 16 KB runtime (`adb shell getconf PAGE_SIZE` must
report `16384`) and Vulkan-compatible devices across the supported API range.
Static alignment checks cannot establish runtime compatibility alone. Consult the
other files in `release/` for the remaining Play listing, privacy, Data safety,
identity, and account requirements.

## Official requirements checked

As checked on 2026-09-30, new apps and updates submitted after 2026-08-31 need
target API 36 or higher; the current target 37 meets that packaging requirement.
The current 16 KB guidance requires compatibility for API 35+ apps and notes a
2027-02-01 update block. The candidate is built and checked for 16 KB now.

- [Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk)
- [16 KB page size support](https://developer.android.com/guide/practices/page-sizes)
- [Release building and signing](https://developer.android.com/build/build-for-release)
- [R8 release optimization](https://developer.android.com/topic/performance/app-optimization/enable-app-optimization)
- [Bionic ELF/RELRO loading](https://android.googlesource.com/platform/bionic/+/refs/heads/main/linker/linker_phdr.cpp)
- [BundleConfig schema](https://github.com/google/bundletool/blob/master/src/main/proto/config.proto)
