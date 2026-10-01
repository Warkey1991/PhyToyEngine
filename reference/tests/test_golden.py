from __future__ import annotations

import hashlib
import json

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def _digest(array: np.ndarray, decimals: int) -> str:
    canonical = np.round(np.asarray(array, dtype=np.float64), decimals).astype("<f4")
    return hashlib.sha256(canonical.tobytes(order="C")).hexdigest()


@pytest.mark.parametrize(
    "manifest_name",
    [
        "reference_srgb_16x12_v1.json",
        "harinezumi_2pp_daylight_srgb_16x12_v0_1.json",
        "phytoy_digital_01_srgb_16x12_v1.json",
        "phytoy_digital_01_srgb_16x12_v1_1.json",
    ],
)
def test_small_reference_golden_manifest(project_root, manifest_name):
    manifest = json.loads(
        (project_root / "goldens" / manifest_name).read_text(encoding="utf-8")
    )
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
        load_host_profile(project_root / "profiles/authoring/host_generic_srgb.json"),
        load_toy_profile(project_root / manifest["toy_profile"]),
    )
    result = pipeline.render_srgb(source, seed=manifest["seed"])
    for name, expected in manifest["stages"].items():
        stage = result.stages[name]
        assert list(stage.shape) == expected["shape"]
        assert _digest(stage, manifest["round_decimals"]) == expected["sha256_f32le"]
        assert abs(float(np.mean(stage)) - expected["mean"]) < 5e-10
        assert abs(float(np.std(stage)) - expected["standard_deviation"]) < 5e-10
