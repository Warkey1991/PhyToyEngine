"""Print the deterministic small-image reference manifest used by regression tests."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def digest(array: np.ndarray) -> str:
    canonical = np.round(np.asarray(array, dtype=np.float64), 6).astype("<f4")
    return hashlib.sha256(canonical.tobytes(order="C")).hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument(
        "--toy-profile",
        type=Path,
        default=Path("profiles/authoring/toy_phytoy_digital_01_v1.json"),
        help="Toy profile path, relative to --root unless absolute",
    )
    arguments = parser.parse_args()
    root = arguments.root
    toy_profile = arguments.toy_profile
    if not toy_profile.is_absolute():
        toy_profile = root / toy_profile
    height, width = 12, 16
    yy, xx = np.mgrid[0:height, 0:width]
    source = np.stack(
        [
            xx / (width - 1),
            yy / (height - 1),
            0.15 + 0.65 * ((xx + 2 * yy) % 7) / 6.0,
        ],
        axis=-1,
    )
    pipeline = ReferencePipeline(
        load_host_profile(root / "profiles/authoring/host_generic_srgb.json"),
        load_toy_profile(toy_profile),
    )
    result = pipeline.render_srgb(source, seed=20260820)
    manifest = {
        "fixture": "procedural_rgb_16x12_v1",
        "toy_profile": str(toy_profile.relative_to(root)),
        "seed": 20260820,
        "round_decimals": 6,
        "stages": {
            name: {
                "shape": list(stage.shape),
                "sha256_f32le": digest(stage),
                "mean": round(float(np.mean(stage)), 9),
                "standard_deviation": round(float(np.std(stage)), 9),
            }
            for name, stage in result.stages.items()
        },
    }
    print(json.dumps(manifest, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()
