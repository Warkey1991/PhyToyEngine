#!/usr/bin/env python3
"""Resize the approved no-wordmark artwork into Galaxy Full launcher assets.

No image generation or creative editing happens here. The approved square master
already contains the centered camera; all raster variants preserve its framing.
"""
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MARKET = ROOT / "android/market/galaxy-full"
RES = ROOT / "android/sample/src/galaxyFull/res"


def main():
    source = Image.open(MARKET / "camera-master.png").convert("RGB")
    if source.width != source.height:
        raise ValueError("Approved icon master must be square")
    for density, size, foreground_size in [
        ("mdpi", 48, 108), ("hdpi", 72, 162), ("xhdpi", 96, 216),
        ("xxhdpi", 144, 324), ("xxxhdpi", 192, 432),
    ]:
        folder = RES / f"mipmap-{density}"
        folder.mkdir(parents=True, exist_ok=True)
        for name in ["ic_launcher", "ic_launcher_round"]:
            source.resize((size, size), Image.Resampling.LANCZOS).save(folder / f"{name}.webp", lossless=True)
        source.resize((foreground_size, foreground_size), Image.Resampling.LANCZOS).save(
            folder / "ic_launcher_foreground.webp", lossless=True)
    source.resize((512, 512), Image.Resampling.LANCZOS).save(MARKET / "galaxy-store-icon-512.png")
    source.resize((1024, 1024), Image.Resampling.LANCZOS).save(MARKET / "icon-preview-1024.png")


if __name__ == "__main__":
    main()
