# PhyToy Google Play artwork

| Upload asset | Editable source | Format |
| --- | --- | --- |
| `store-icon.png` | `store-icon.svg` | 512 × 512, 32-bit RGBA PNG, full square, sRGB |
| `feature-graphic.png` | `feature-graphic.svg` | 1024 × 500, 24-bit RGB PNG, no transparency, sRGB |

The store icon extends the project's original yellow camera vector. The feature graphic uses original geometric postcards and the black/gold PhyToy palette. These are illustrative brand graphics, not camera sample outputs or screenshots. No third-party photos, camera trademarks, AI images, performance claims, rankings or store badges are included.

The SVG text remains editable and uses Arial with Helvetica/sans-serif fallbacks. The raster generator uses a locally installed Arial, Liberation Sans or DejaVu Sans font; it does not bundle font files.

Rebuild with Python and Pillow:

```sh
python3 release/store-assets/generate_store_assets.py
python3 release/store-assets/generate_store_assets.py --verify-only
```

Suggested alt text:

- Icon (English): Yellow camera with concentric lens rings on a dark background.
- Icon (Chinese): 深色方形背景上的黄色相机，镜头由同心圆环组成。
- Feature (English): PhyToy: A little camera. A different view. Warm color and monochrome geometric postcards suggest everyday photography.
- Feature (Chinese): PhyToy，用小相机发现不同视角。暖色与黑白几何卡片展现日常摄影的趣味。

Specifications checked against [Google Play preview asset requirements](https://support.google.com/googleplay/android-developer/answer/9866151?hl=en) and [Google Play icon design specifications](https://developer.android.com/distribute/google-play/resources/icon-design-specifications). Keep the icon square with no baked-in corner mask or external shadow; Google Play adds those treatments.
