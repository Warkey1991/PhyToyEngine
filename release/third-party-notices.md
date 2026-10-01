# Runtime license inventory

The PhyToy engine/application source is provided under [MIT](../LICENSE).

| Component | Role | License | Source |
| --- | --- | --- | --- |
| AndroidX ExifInterface 1.4.2 | Safe JPEG metadata read/write across supported Android versions | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/exifinterface |
| AndroidX annotation (resolved transitively) | AndroidX API annotations; most have no runtime behavior | Apache-2.0 | https://android.googlesource.com/platform/frameworks/support/ |
| Kotlin standard library (resolved by Android build plugin) | Kotlin application runtime | Apache-2.0 | https://github.com/JetBrains/kotlin |
| Android NDK libc++ shared runtime | C++ runtime distributed with the native engine | Apache-2.0 WITH LLVM-exception and applicable LLVM notices | https://github.com/llvm/llvm-project/tree/main/libcxx |

The native Vulkan/Camera2 implementation is in this repository. Referenced imaging papers, research tools and public photographs are not vendored runtime dependencies. The nine Hendigi study images used for analysis are not included in the app or store material. Profile filenames referencing camera styles do not grant rights to external sample photos, logos or endorsements.

Keep this inventory synchronized with Gradle's resolved dependency report and the final APK/AAB when dependencies change. License notices for shipped runtime libraries are also available in the app's About → Open source licenses dialog. Standard platform APIs are supplied by Android itself.
