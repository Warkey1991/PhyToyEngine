# Android localization

The default `values/` resources remain English. Simplified Chinese uses the existing `values-zh-rCN/` resources plus shared Chinese camera controls in `values-zh/`. Added locales:

| Language | Resource qualifier | Android locale |
| --- | --- | --- |
| Japanese | values-ja | ja |
| Korean | values-ko | ko |
| German | values-de | de |
| French | values-fr | fr |
| Spanish | values-es | es |
| Portuguese | values-pt | pt |
| Indonesian | values-in | in (Android alias of id) |
| Hindi | values-hi | hi |
| Thai | values-th | th |

“Indian” is interpreted as Hindi for this request. Portuguese uses a general locale with European Portuguese wording. Each added locale includes all translatable main strings and the photo-count plural, plus the Play and Galaxy channel overrides. Indonesian consistently uses Android's legacy `in` alias in resources and the locale declaration; Android/Java normalize the language to `id`. This avoids both LocaleFolder and UnusedTranslation warnings in the current Android tooling.

The system chooses resources from its language settings. Supported app languages are listed in `android/sample/src/main/res/xml/locales_config.xml` for Android 13+ per-app language settings. The default English resource set remains complete for fallback. This change does not add an in-app language picker.

Camera/style names, format-only values and hidden engine diagnostics retain their technical names. Screens, settings, camera errors, photo actions, purchase plans, restoration, permissions, help and privacy are localized. Google Play and Galaxy Store resources preserve their own provider/account wording and display the store's formatted price. The active privacy dialog uses `privacy_channel_body` and passes the current store/account names; the unused older Play-only `product_privacy_body` resource was removed.

When changing strings, retain numbered format arguments, types and precision (for example `%1$s`, `%2$d`, `%1$+.2f`). Use Android string escaping as well as XML escaping. Camera exposure/zoom readouts, the ISO badge and exposure-time units now come from resources. Photo filenames and EXIF formatting keep their existing machine-readable format.

Validation commands, from the repository root:

```sh
python3 -m unittest discover -s tools/tests -p test_android_localization.py
android/gradlew -p android --no-configuration-cache \
  :sample:processPlayDebugResources :sample:processGalaxyDebugResources \
  :sample:compilePlayDebugKotlin :sample:compileGalaxyDebugKotlin \
  :sample:lintPlayDebug :sample:lintGalaxyDebug
```

The tests verify locale declarations, resource coverage, duplicate keys, format parameters, plural fallbacks, correct store overrides and preservation of Simplified Chinese. Resource compilation and lint validate both channel variants. Physical-device layout and native-speaker review are separate from these checks.
