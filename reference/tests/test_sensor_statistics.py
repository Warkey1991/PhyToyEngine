import copy

import numpy as np

from phytoy_ref.profiles import load_toy_profile
from phytoy_ref.sensor import simulate_sensor


def test_sensor_variance_increases_with_signal(toy_path):
    profile = copy.deepcopy(load_toy_profile(toy_path))
    profile["sensor"]["prnu_sigma"] = 0.0
    profile["sensor"]["row_noise_e"] = 0.0
    profile["sensor"]["column_noise_e"] = 0.0
    profile["sensor"]["dsnu_e"] = 0.0
    dark_samples = []
    bright_samples = []
    for seed in range(80):
        dark = simulate_sensor(np.full((8, 8, 3), 0.05), profile, seed=seed)
        bright = simulate_sensor(np.full((8, 8, 3), 0.7), profile, seed=seed)
        dark_samples.append(float(dark[4, 4]))
        bright_samples.append(float(bright[4, 4]))
    assert np.var(bright_samples) > np.var(dark_samples) * 2.0


def test_sensor_mean_variance_curve_is_monotonic(toy_path):
    profile = copy.deepcopy(load_toy_profile(toy_path))
    profile["sensor"]["prnu_sigma"] = 0.0
    profile["sensor"]["row_noise_e"] = 0.0
    profile["sensor"]["column_noise_e"] = 0.0
    profile["sensor"]["dsnu_e"] = 0.0
    variances = []
    for level in [0.04, 0.12, 0.3, 0.55]:
        samples = [
            float(simulate_sensor(np.full((6, 6, 3), level), profile, seed=seed)[3, 3])
            for seed in range(120)
        ]
        variances.append(float(np.var(samples)))
    assert np.all(np.diff(variances) > 0.0), variances


def test_fixed_pattern_component_is_seed_independent(toy_path):
    profile = copy.deepcopy(load_toy_profile(toy_path))
    profile["sensor"]["exposure_scale_e"] = 0.0
    profile["sensor"]["read_noise_e"] = 0.0
    profile["sensor"]["row_noise_e"] = 0.0
    profile["sensor"]["prnu_sigma"] = 0.0
    dark = np.zeros((20, 24, 3))
    first = simulate_sensor(dark, profile, seed=1)
    second = simulate_sensor(dark, profile, seed=999)
    np.testing.assert_array_equal(first, second)


def test_adc_output_respects_white_level(toy_path):
    profile = load_toy_profile(toy_path)
    result = simulate_sensor(np.full((12, 14, 3), 100.0), profile, seed=7)
    assert np.min(result) >= 0.0
    assert np.max(result) <= profile["sensor"]["white_level_dn"]
