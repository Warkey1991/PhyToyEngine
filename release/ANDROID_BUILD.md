# Android 0.17 build and release

Version **0.17.0 / 18**, API 26+, arm64-v8a, Vulkan 1.1, compile/target SDK 37. Use JDK 21, platform android-37.0, build-tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, Gradle 9.5.0, AGP 9.3.1. Configure ANDROID_SDK_ROOT or ignored android/local.properties. Samsung IAP is declared as `com.samsung.developer:iap:6.5.2`; dependency repositories are Google and Maven Central in android/settings.gradle.kts.

## Unsigned candidates

```sh
cd android
./gradlew :sample:assemblePlayBenchmark :sample:assembleGalaxyBenchmark \
  :sample:testPlayDebugUnitTest :sample:testGalaxyDebugUnitTest \
  :sample:assemblePlayRelease :sample:bundlePlayRelease :sample:lintPlayRelease \
  :sample:assembleGalaxyRelease :sample:bundleGalaxyRelease :sample:lintGalaxyRelease
cd ..
python3 tools/check_android_release.py --channel play \
  --apk android/sample/build/outputs/apk/play/release/sample-play-release-unsigned.apk \
  --aab android/sample/build/outputs/bundle/playRelease/sample-play-release.aab \
  --expected-application-id com.ycolor.team.phytoy.camera.android.gpapp \
  --allow-unsigned --output reports/release_candidate_0_17/play-packaging.json
python3 tools/check_android_release.py --channel galaxy \
  --apk android/sample/build/outputs/apk/galaxy/release/sample-galaxy-release-unsigned.apk \
  --aab android/sample/build/outputs/bundle/galaxyRelease/sample-galaxy-release.aab \
  --expected-application-id com.ycolor.team.phytoy.camera.android.galaxyapp \
  --allow-unsigned --output reports/release_candidate_0_17/galaxy-packaging.json
```

Without signing credentials both release files are unsigned. Gradle's sign…Bundle task name does not prove signing. Benchmark builds inherit release optimization but use a development certificate; never upload them.

## Public inputs

Properties override environment variables. Published IDs remain permanent; updates increase versionCode.

| Property | Environment variable | Default |
| --- | --- | --- |
| phytoyApplicationId | PHYTOY_APPLICATION_ID | com.ycolor.team.phytoy.camera.android.gpapp |
| phytoyGalaxyApplicationId | PHYTOY_GALAXY_APPLICATION_ID | com.ycolor.team.phytoy.camera.android.galaxyapp |
| phytoyVersionCode | PHYTOY_VERSION_CODE | 18 |
| phytoyVersionName | PHYTOY_VERSION_NAME | 0.17.0 |
| phytoyPlayBillingPublicKey | PHYTOY_PLAY_BILLING_PUBLIC_KEY | empty |
| phytoyGalaxyBillingConfigured | PHYTOY_GALAXY_BILLING_CONFIGURED | false |
| phytoyPublisherName | PHYTOY_PUBLISHER_NAME | empty |
| phytoySupportEmail | PHYTOY_SUPPORT_EMAIL | empty |
| phytoyPrivacyPolicyUrl | PHYTOY_PRIVACY_POLICY_URL | empty |

The Play licensing key is public X509/base64 RSA data, not a private key. Configure the four Play products and four Samsung permanent Item products before enabling sales. The publisher gate checks identity/email/HTTPS syntax; independently verify public hosting and policy accuracy.

## Production signing

Set all four secrets in a secure environment: PHYTOY_KEYSTORE, PHYTOY_STORE_PASSWORD, PHYTOY_KEY_ALIAS, PHYTOY_KEY_PASSWORD. Partial credentials fail. Do not pass passwords on command lines, enable shell tracing or commit credentials.

With public inputs, store products and the licensing key configured:

```sh
cd android
./gradlew :sample:assemblePlayRelease :sample:bundlePlayRelease :sample:lintPlayRelease \
  -PphytoyRequireSignedRelease=true -PphytoyRequireBillingConfigured=true \
  -PphytoyRequirePublisherConfigured=true
```

For Galaxy, use its signing environment in a separate command:

```sh
./gradlew :sample:assembleGalaxyRelease :sample:bundleGalaxyRelease :sample:lintGalaxyRelease \
  -PphytoyGalaxyBillingConfigured=true -PphytoyRequireSignedRelease=true \
  -PphytoyRequireBillingConfigured=true -PphytoyRequirePublisherConfigured=true
```

Audit each signed pair with --store-ready, --channel play|galaxy, the actual --expected-application-id and independently recorded --expected-cert-sha256. Signed APK filenames omit -unsigned. Upload the Play AAB and Seller Portal's accepted Galaxy format. Retain each store's certificate identity. Play App Signing's upload certificate and delivered-app certificate are separate.

## Artifact audit scope

The checker verifies matching APK/AAB IDs/versions, isolated store billing permissions, release flags, disabled cleartext, arm64, 16 KB ELF/ZIP alignment, GNU_RELRO, native/profile hashes and signing/certificate metadata. Its store_ready field covers artifacts, not live commerce, privacy hosting, device acceptance or store approval.

Retain outputs/mapping/playRelease and galaxyRelease and matching outputs/native-debug-symbols directories with hashes. Rebuild/audit after every code/resource/public-input/signing change. CI builds both unsigned channels; local workflow changes do not prove a CI-host run.

See [release gates](README.md), [Play billing](PLAY_BILLING.md), [Galaxy billing](GALAXY_BILLING.md), [privacy worksheet](data-safety.md). Static alignment cannot replace real 16 KB runtime and physical camera tests.
