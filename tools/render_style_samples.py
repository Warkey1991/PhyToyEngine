"""Render labelled, generated-scene illustrations with the shipped style profiles.

These assets explain a style; they are not device captures or calibration evidence.
"""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageOps

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "reference"))
from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile

STYLES = {
    "dh_color": ("toy_harinezumi_2pp_daylight_v0_4.json", 3 / 4),
    "dh_mono": ("toy_harinezumi_2pp_mono_v0_2.json", 3 / 4),
    "digital": ("toy_phytoy_digital_01_v1_2.json", 3 / 4),
    "plastic": ("toy_phytoy_plastic_82_v1.json", 1),
    "street": ("toy_phytoy_street_84_v1.json", 2 / 3),
    "fisheye": ("toy_phytoy_fisheye_05_v1.json", 1),
}


def main() -> None:
    source_path = ROOT / "release/style-samples/source-scene.jpg"
    with Image.open(source_path) as opened:
        source = ImageOps.exif_transpose(opened).convert("RGB")
        source.thumbnail((640, 480), Image.Resampling.LANCZOS)
    encoded = np.asarray(source, dtype=np.float64) / 255
    destination = ROOT / "android/sample/src/main/res/drawable-nodpi"
    destination.mkdir(parents=True, exist_ok=True)
    host_path = ROOT / "profiles/authoring/host_generic_srgb.json"
    manifest = {"purpose": "Generated scene, Python reference style illustrations; not app photographs",
                "seed": 20261006, "source_sha256": hashlib.sha256(source_path.read_bytes()).hexdigest(),
                "host_sha256": hashlib.sha256(host_path.read_bytes()).hexdigest(), "styles": {}}
    for name, (profile_file, aspect) in STYLES.items():
        profile_path = ROOT / "profiles/authoring" / profile_file
        result = ReferencePipeline(load_host_profile(host_path), load_toy_profile(profile_path)).render_srgb(
            encoded, seed=manifest["seed"])
        rendered = Image.fromarray(np.rint(np.clip(result.output_srgb, 0, 1) * 255).astype(np.uint8))
        width = min(rendered.width, round(rendered.height * aspect))
        height = min(rendered.height, round(rendered.width / aspect))
        left, top = (rendered.width - width) // 2, (rendered.height - height) // 2
        rendered = rendered.crop((left, top, left + width, top + height))
        source.crop((left, top, left + width, top + height)).save(
            destination / f"style_source_{name}.webp", "WEBP", quality=88, method=6)
        output_path = destination / f"style_sample_{name}.webp"
        rendered.save(output_path, "WEBP", quality=88, method=6)
        manifest["styles"][name] = {"profile": profile_file,
            "profile_sha256": hashlib.sha256(profile_path.read_bytes()).hexdigest(),
            "output_sha256": hashlib.sha256(output_path.read_bytes()).hexdigest(),
            "size": list(rendered.size)}
    (ROOT / "release/style-samples/manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print(f"Rendered {len(STYLES)} original-scene style illustrations")


if __name__ == "__main__":
    main()
