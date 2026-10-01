"""Target digital sensor response, CFA, stochastic noise and ADC."""

from __future__ import annotations

import numpy as np

from .color import apply_matrix
from .demosaic import mosaic


def _round_adc_half_up(values: np.ndarray) -> np.ndarray:
    """Round nonnegative pre-ADC values; .5 ties select the higher integer.

    Splitting the fraction avoids moving nextafter(.5, below) onto the tie
    when adding .5 in finite precision, and preserves large integral values.
    """
    integral = np.floor(values)
    return integral + (values - integral >= 0.5)


def simulate_sensor(
    optical_rgb: np.ndarray, toy_profile: dict, *, seed: int
) -> np.ndarray:
    sensor = toy_profile["sensor"]
    sensor_rgb = np.maximum(
        apply_matrix(optical_rgb, np.asarray(sensor["scene_to_sensor"], dtype=np.float64)),
        0.0,
    )
    expected = sensor_rgb * float(sensor["exposure_scale_e"])

    # A deterministic noise bypass is useful for backend conformance fixtures.
    # Production profiles omit this key and therefore use the physical noise path.
    if not bool(sensor.get("noise_enabled", True)):
        electrons = np.clip(expected, 0.0, float(sensor["full_well_e"]))
        mosaic_e = mosaic(electrons, sensor["cfa"])
        gain = float(sensor["conversion_gain_e_per_dn"])
        black = float(sensor["black_level_dn"])
        white = float(sensor["white_level_dn"])
        return np.clip(_round_adc_half_up(mosaic_e / gain + black), 0.0, white)

    profile_seed = int(sensor.get("profile_seed", 1))
    fixed_rng = np.random.Generator(np.random.Philox(profile_seed))
    temporal_rng = np.random.Generator(np.random.Philox(int(seed)))

    prnu_sigma = float(sensor.get("prnu_sigma", 0.0))
    if prnu_sigma > 0.0:
        prnu = fixed_rng.normal(0.0, prnu_sigma, size=expected.shape)
        expected *= np.maximum(1.0 + prnu, 0.0)

    electrons = temporal_rng.poisson(np.maximum(expected, 0.0)).astype(np.float64)
    height, width, _ = electrons.shape

    row_sigma = float(sensor.get("row_noise_e", 0.0))
    column_sigma = float(sensor.get("column_noise_e", 0.0))
    if row_sigma > 0.0:
        electrons += temporal_rng.normal(0.0, row_sigma, size=(height, 1, 1))
    if column_sigma > 0.0:
        electrons += fixed_rng.normal(0.0, column_sigma, size=(1, width, 1))

    read_sigma = float(sensor.get("read_noise_e", 0.0))
    if read_sigma > 0.0:
        electrons += temporal_rng.normal(0.0, read_sigma, size=electrons.shape)

    dsnu_sigma = float(sensor.get("dsnu_e", 0.0))
    if dsnu_sigma > 0.0:
        electrons += fixed_rng.normal(0.0, dsnu_sigma, size=electrons.shape)

    full_well = float(sensor["full_well_e"])
    electrons = np.clip(electrons, 0.0, full_well)
    mosaic_e = mosaic(electrons, sensor["cfa"])

    gain = float(sensor["conversion_gain_e_per_dn"])
    black = float(sensor["black_level_dn"])
    white = float(sensor["white_level_dn"])
    dn = _round_adc_half_up(mosaic_e / gain + black)
    return np.clip(dn, 0.0, white)
