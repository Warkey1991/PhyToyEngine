"""ADC half-tie behavior, numeric boundaries and complete native sensor stages."""
from __future__ import annotations

import copy
import json
import os

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profile_package import compile_profile
from phytoy_ref.profiles import load_host_profile, load_toy_profile
from phytoy_ref.sensor import _round_adc_half_up, simulate_sensor


def adc_fixture(project_root):
    return load_toy_profile(project_root / "profiles/authoring/toy_adc_half_tie_conformance.json")


@pytest.mark.parametrize("dtype", [np.float32, np.float64])
def test_half_up_keeps_values_immediately_below_ties_and_large_integers(dtype):
    half = dtype(0.5)
    below = np.nextafter(half, dtype(0.0))
    above = np.nextafter(half, dtype(1.0))
    values = np.array([below, half, above, 64.5, 65.5, 8388609.0], dtype=dtype)
    expected = np.array([0.0, 1.0, 1.0, 65.0, 66.0, 8388609.0], dtype=dtype)
    np.testing.assert_array_equal(_round_adc_half_up(values), expected)
    # A direct floor(q+.5) implementation loses the below-tie boundary.
    assert np.floor(below + dtype(0.5)) == 1.0


def test_sensor_exact_optical_half_tie_selects_higher_dn(project_root):
    toy = adc_fixture(project_root)
    toy["sensor"]["black_level_dn"] = 64.0
    # All arithmetic is exactly representable: (1/32)*720 + 64 = 86.5.
    optical = np.full((2, 2, 3), 1.0 / 32.0)
    np.testing.assert_array_equal(simulate_sensor(optical, toy, seed=1234), np.full((2, 2), 87.0))


def test_noisy_zero_electron_path_uses_the_same_half_tie_rule(project_root):
    toy = adc_fixture(project_root)
    toy["sensor"]["noise_enabled"] = True
    # Poisson(0) and zero noise amplitudes keep electrons exactly zero.
    optical = np.zeros((2, 2, 3))
    np.testing.assert_array_equal(simulate_sensor(optical, toy, seed=1234), np.full((2, 2), 65.0))


def test_adc_half_up_respects_electron_and_white_level_clamps(project_root):
    toy = adc_fixture(project_root)
    toy["sensor"]["white_level_dn"] = 100.0
    optical = np.zeros((2, 2, 3))
    optical[0, :, :] = -1.0
    optical[1, :, :] = 10.0
    np.testing.assert_array_equal(simulate_sensor(optical, toy, seed=1234), [[65.0, 65.0], [100.0, 100.0]])


native_required = pytest.mark.skipif(
    not os.environ.get("PHYTOY_CPP_LIBRARY") or not os.environ.get("PHYTOY_CPP_PROFILE_DIR"),
    reason="native library conformance environment is not configured",
)


@native_required
@pytest.mark.parametrize("black, expected", [
    (float(np.nextafter(np.float32(0.5), np.float32(0.0))), 0.0),
    (0.5, 1.0),
    (float(np.nextafter(np.float32(0.5), np.float32(1.0))), 1.0),
    (64.5, 65.0),
    (65.5, 66.0),
    (8388609.0, 8388609.0),
])
def test_native_complete_adc_path_at_ties_neighbors_and_large_integer(project_root, tmp_path, black, expected):
    from test_cpp_conformance import _native_render

    toy = adc_fixture(project_root)
    toy["sensor"]["black_level_dn"] = black
    toy["sensor"]["white_level_dn"] = max(1023.0, black + 1024.0)
    toy["sensor"]["adc_bits"] = 32
    authoring = tmp_path / "adc.json"
    authoring.write_text(json.dumps(toy))
    package = compile_profile(authoring, tmp_path / "adc.ptp")
    _, stages = _native_render(np.zeros((2, 2, 3)), toy_package=str(package), seed=1234)
    np.testing.assert_array_equal(stages[3][..., 0], np.full((2, 2), expected))


@native_required
@pytest.mark.parametrize("shape", [(2, 2), (18, 22)])
@pytest.mark.parametrize("noise_enabled", [False, True])
def test_half_tie_fixture_native_and_independent_reference_agree(project_root, tmp_path, shape, noise_enabled):
    from test_cpp_conformance import _native_render

    toy = adc_fixture(project_root)
    package = "toy_adc_half_tie_conformance.ptp"
    if noise_enabled:
        toy = copy.deepcopy(toy)
        toy["sensor"]["noise_enabled"] = True
        authoring = tmp_path / "noisy_zero_electrons.json"
        authoring.write_text(json.dumps(toy))
        package = str(compile_profile(authoring, tmp_path / "noisy_zero_electrons.ptp"))
    source = np.zeros((*shape, 3))
    host = load_host_profile(project_root / "profiles/authoring/host_generic_srgb.json")
    reference = ReferencePipeline(host, toy).render_srgb(source, seed=1234)
    output, stages = _native_render(source, toy_package=package, seed=1234)
    np.testing.assert_array_equal(reference.stages["03_target_sensor_dn"], np.full(shape, 65.0))
    np.testing.assert_array_equal(stages[3][..., 0], reference.stages["03_target_sensor_dn"])
    np.testing.assert_allclose(output, reference.output_srgb, atol=8e-6, rtol=0)
