# Runtime license inventory

The PhyToy engine/application source is provided under [MIT](../LICENSE).

| Component | Shipped channel | Role | License / source |
| --- | --- | --- | --- |
| AndroidX ExifInterface 1.4.2 | Play and Galaxy | JPEG metadata read/write across supported Android versions | Apache-2.0; [official release](https://developer.android.com/jetpack/androidx/releases/exifinterface) |
| Google Play Billing Library 9.1.0 and resolved dependencies | Play only | Permanent camera purchases, localized prices and restore | Android Software Development Kit License; original AAR third-party notices included in the Play app; [official terms](https://developer.android.com/studio/terms.html) |
| Samsung IAP SDK 6.5.2 (`com.samsung.developer:iap:6.5.2`) | Galaxy only | Permanent Item purchases, localized prices, acknowledgement and restore | Compiled SDK/services governed by the [Samsung IAP License Agreement](https://developer.samsung.com/iap/terms/license-agreement.txt); [official artifact metadata](https://github.com/Samsung/iap-metadata) |
| AndroidX annotation (resolved transitively) | Play and Galaxy | AndroidX API annotations; most have no runtime behavior | Apache-2.0; [AndroidX source](https://android.googlesource.com/platform/frameworks/support/) |
| Kotlin standard library (resolved by Android build plugin) | Play and Galaxy | Kotlin application runtime | Apache-2.0; [Kotlin source](https://github.com/JetBrains/kotlin) |
| Android NDK libc++ shared runtime | Play and Galaxy | C++ runtime distributed with the native engine | Apache-2.0 WITH LLVM-exception and applicable LLVM notices; [LLVM source](https://github.com/llvm/llvm-project/tree/main/libcxx) |

The Samsung artifact resolves from **Maven Central**, which is configured in `android/settings.gradle.kts`; no separate Samsung Maven repository is configured. Its resolved 6.5.2 AAR is 280,458 bytes, with SHA-256 `bb3c65fb288b0e160f67d672033b13c501e71fb2a68382c4395949d951cb0cab`. The Gradle cache location relative to `GRADLE_USER_HOME` is `caches/modules-2/files-2.1/com.samsung.developer/iap/6.5.2/4804df7d068a7683dbb629584d4aa87c6989bfe7/iap-6.5.2.aar`. The official metadata repository's open-source license does **not** make the compiled Samsung SDK Apache-2.0.

Current application notice sources are:

| Notice | Source file | Provenance |
| --- | --- | --- |
| Common native/AndroidX/runtime notices | [open_source_notices.txt](../android/sample/src/main/res/raw/open_source_notices.txt) | Shared by both store variants |
| Play Billing notices | [channel_billing_notices.txt](../android/sample/src/play/res/raw/channel_billing_notices.txt) | Unmodified `third_party_licenses.txt` from the official Billing 9.1.0 AAR; its JSON index hash is recorded separately |
| Samsung IAP attribution | [channel_billing_notices.txt](../android/sample/src/galaxy/res/raw/channel_billing_notices.txt) | Publisher-written SDK attribution and links to the official Samsung license/metadata/docs; no LICENSE/NOTICE/COPYING entry was found in the resolved AAR or its `classes.jar` |

The former `src/main/res/raw/play_billing_notices.txt` has moved into the Play flavor. Each final app displays the common notice and its own `R.raw.channel_billing_notices` in About → Open source licenses; the Galaxy resource is a Samsung license reference, not a copied complete license text. Current artifact and source-notice hashes are recorded in [license-provenance.json](license-provenance.json). These hashes identify the bytes reviewed; unsigned candidate builds do not establish seller/account eligibility or a completed license acceptance.

The native Vulkan/Camera2 implementation is in this repository. Referenced imaging papers, research tools and public photographs are not vendored runtime dependencies. The nine Hendigi study images used for analysis are not included in the app or store material. Profile filenames referencing camera styles do not grant rights to external sample photos, logos or endorsements.

Keep this inventory synchronized with each flavor's resolved dependency report and final APK/AAB when dependencies change. Standard platform APIs are supplied by Android itself.
