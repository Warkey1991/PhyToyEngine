from __future__ import annotations

import numpy as np

from phytoy_ref.optics import apply_optics
from phytoy_ref.profiles import load_toy_profile


def _corner_to_center_ratio(optics_profile: dict) -> float:
    output = apply_optics(
        np.ones((96, 96, 3), dtype=np.float64), {"optics": optics_profile}
    )
    corners = np.concatenate(
        [
            output[:8, :8].ravel(),
            output[:8, -8:].ravel(),
            output[-8:, :8].ravel(),
            output[-8:, -8:].ravel(),
        ]
    )
    center = output[40:56, 40:56]
    return float(np.mean(corners) / np.mean(center))


def test_v1_1_balances_style_without_removing_digital_01_signature(
    toy_path, tuned_toy_path
):
    baseline = load_toy_profile(toy_path)
    tuned = load_toy_profile(tuned_toy_path)

    baseline_ca = np.max(np.abs(np.asarray(baseline["optics"]["ca_scale"]) - 1.0))
    tuned_ca = np.max(np.abs(np.asarray(tuned["optics"]["ca_scale"]) - 1.0))
    assert 0.0 < tuned_ca < baseline_ca

    baseline_falloff = _corner_to_center_ratio(baseline["optics"])
    tuned_falloff = _corner_to_center_ratio(tuned["optics"])
    assert baseline_falloff + 0.25 < tuned_falloff < 0.8

    for name in (
        "read_noise_e",
        "row_noise_e",
        "column_noise_e",
        "prnu_sigma",
        "dsnu_e",
    ):
        assert 0.0 < tuned["sensor"][name] < baseline["sensor"][name]

    red, green, blue = tuned["isp"]["white_balance"]
    assert red > green > blue


def test_v1_2_reduces_ca_and_opens_only_lower_tones(
    tuned_toy_path, outdoor_tuned_toy_path
):
    v1_1 = load_toy_profile(tuned_toy_path)
    v1_2 = load_toy_profile(outdoor_tuned_toy_path)

    ca_v1_1 = np.max(np.abs(np.asarray(v1_1["optics"]["ca_scale"]) - 1.0))
    ca_v1_2 = np.max(np.abs(np.asarray(v1_2["optics"]["ca_scale"]) - 1.0))
    assert 0.7 * ca_v1_1 < ca_v1_2 < 0.8 * ca_v1_1

    tone_v1_1 = np.asarray(v1_1["isp"]["tone_curve"])
    tone_v1_2 = np.asarray(v1_2["isp"]["tone_curve"])
    np.testing.assert_array_equal(tone_v1_2[:, 0], tone_v1_1[:, 0])
    assert np.all(tone_v1_2[1:4, 1] > tone_v1_1[1:4, 1])
    np.testing.assert_array_equal(tone_v1_2[4:, 1], tone_v1_1[4:, 1])

    assert v1_2["optics"]["vignette"] == v1_1["optics"]["vignette"]
    assert v1_2["sensor"] == v1_1["sensor"]
    assert v1_2["isp"]["white_balance"] == v1_1["isp"]["white_balance"]
    assert v1_2["isp"]["sensor_to_rec2020"] == v1_1["isp"]["sensor_to_rec2020"]
