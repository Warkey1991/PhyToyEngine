# Android 0.17 build and release

Version **0.17.8 / 26**, API 26+, arm64-v8a, Vulkan 1.1, compile/target SDK 37. Use JDK 21, platform android-37.0, build-tools 36.0.0, NDK 28.2.13676358, CMake 3.22.1, Gradle 9.5.0, AGP 9.3.1. Configure ANDROID_SDK_ROOT or ignored android/local.properties. Samsung IAP is declared as `com.samsung.developer:iap:6.5.2`; dependency repositories are Google and Maven Central in android/settings.gradle.kts.

The app namespace and Kotlin source package are `com.team.phytoy.camera`, with launcher Activity `com.team.phytoy.camera.MainActivity`. Store application IDs remain the permanent channel IDs below. The SDK package and native JNI bindings remain `com.phytoy.engine`.

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
| phytoyVersionCode | PHYTOY_VERSION_CODE | 26 |
| phytoyVersionName | PHYTOY_VERSION_NAME | 0.17.8 |
| phytoyPlayBillingPublicKey | PHYTOY_PLAY_BILLING_PUBLIC_KEY | empty |
| phytoyGalaxyBillingConfigured | PHYTOY_GALAXY_BILLING_CONFIGURED | false |
| phytoyPublisherName | PHYTOY_PUBLISHER_NAME | empty |
| phytoySupportEmail | PHYTOY_SUPPORT_EMAIL | empty |
| phytoyPrivacyPolicyUrl | PHYTOY_PRIVACY_POLICY_URL | empty |

The Play licensing key is public X509/base64 RSA data, not a private key. Configure the five Play products and five Samsung permanent Item products before enabling sales. The publisher gate checks identity/email/HTTPS syntax; independently verify public hosting and policy accuracy.

## Production signing

### Kotlin DSL channel packaging

The applied script [android/package.gradle.kts](../android/package.gradle.kts) adds channel packaging tasks to the existing Gradle wrapper. No standalone Kotlin installation is needed. From the repository root:

```sh
# Signed candidates: Play, Galaxy, or both channels.
android/gradlew -p android --no-configuration-cache packagePlay
android/gradlew -p android --no-configuration-cache packageGalaxy
android/gradlew -p android --no-configuration-cache packageAll

# Specify the next unused version code; retain or update the version name.
android/gradlew -p android --no-configuration-cache packageAll \
  -PphytoyVersionCode=30 -PphytoyVersionName=0.17.8

# Enforce this project's billing and publisher configuration before packaging.
android/gradlew -p android --no-configuration-cache packagePlay \
  -PphytoyVersionCode=30 -PpackageStoreReady=true

# Check the existing shared signing key without building.
android/gradlew -p android --no-configuration-cache checkReleaseSigning
```

Use the full task names. Add `--offline` for cached dependencies and `--no-daemon` for a single-use Gradle process. The tasks read the existing ignored `android/keystore.properties`, or a complete set of the four signing environment variables below. They validate the private key before the app's release build and use the same key for both channels. Passwords are literal data, never command-line arguments, task inputs or published files. Configuration cache is rejected for these tasks to keep local signing inputs out of its serialized state. Normal IDE/debug builds do not read the local signing file.

Each task builds Release APK/AAB, runs channel lint, reuses the existing Python signature/16 KB packaging checker, then archives APK, AAB, `mapping.txt`, `native-debug-symbols.zip`, the audit JSON and `SHA256SUMS.txt` into `release-artifacts/packages/<play|galaxy>/<versionName>-<versionCode>-<candidate|store-checked>/`. Re-running the same version/mode replaces that directory's corresponding files. `-PpackageOutputDir=/absolute/output/path` changes the archive root; `-PpackagePython=/absolute/path/to/python3` selects Python 3.10+ for the existing helpers. The build stays in one Gradle invocation, without recursively starting another Gradle build.

`packageStoreReady=true` checks only the existing local configuration and artifact gates. It does not verify Play/Seller Portal products, perform payments, publish policies, upload a package or establish store approval. In particular, the current Play client still needs its RSA billing public key to enable purchases. A missing key blocks strict Play packaging while Galaxy packaging checks its own configuration independently.

The isolated Gradle integration tests use a disposable keystore and fixture build (no Android SDK or marketplace access): `PHYTOY_RUN_GRADLE_PACKAGING_TESTS=1 python3 -m unittest discover -s tools/tests -p test_gradle_packaging.py`. They check single/both channel selection, checksums, custom output paths, audit failure, signing environment precedence, literal passwords, permissions, pre-build key validation, configuration-cache rejection and normal help without signing configuration.

Signing policy confirmed by the user on October 17, 2026: Google Play and Galaxy Store release builds use the same existing keystore and key alias. The shared release signing configuration already applies to both channels. The validated local key and the audited 0.17.8 / 26 Play and Galaxy APK/AAB candidates have certificate SHA-256 `27b5195d4a2faf157fa6f4656768f0dcba706effad0c0a0fbebd4e073aa7a1f2`.

To retain the same certificate on APKs delivered by Google Play, supply this existing app signing key when configuring Play App Signing. Signing the uploaded AAB with this key alone does not establish the delivered APK's certificate. Verify the app signing certificate in Play Console against the fingerprint above. See [Android app signing](https://developer.android.com/studio/publish/app-signing). The Play billing licensing public key is separate from this signing certificate and must still be configured for purchases.

The local packaging entry point is `tools/package_android_release.py`. It loads the ignored `android/keystore.properties` as literal data and supplies the four existing signing environment variables to Gradle. Keep the private file at mode 0600. `android/keystore.properties.example` contains placeholders only; never add credentials to the example, Gradle source or this document. All four externally supplied signing environment variables override the local file as a complete set; partial environment configuration fails.

```sh
python3 tools/package_android_release.py --check-signing
python3 tools/package_android_release.py --channel galaxy
python3 tools/package_android_release.py --channel play
python3 tools/package_android_release.py --channel both
```

The signing check verifies the keystore password, alias, private-key password and certificate without modifying the JKS. On macOS the script selects JDK 21 when JAVA_HOME is unset. Passwords are passed through the child environment, never shell sourcing or command-line arguments; visible tool output is redacted. Gradle runs without configuration cache. Builds use Release, enforce signing, run the channel's lint checks, and audit both APK and AAB against the JKS certificate using the existing release checker. Audit reports are written under ignored `release-artifacts/`.

These default commands produce signed candidates. Add `--store-ready` to enforce the existing billing and publisher configuration gates and the static store packaging audit. Those gates do not certify real purchases, privacy hosting or marketplace acceptance. Use `--offline` when dependencies are already cached, and `--version-code N --version-name VERSION` when producing an updated release. No command uploads or installs an app.

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
