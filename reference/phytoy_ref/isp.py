"""Target camera ISP reference implementation."""

from __future__ import annotations

import numpy as np
from scipy.ndimage import gaussian_filter

from .color import apply_matrix, scene_linear_to_srgb
from .demosaic import demosaic_bilinear


def _apply_tone(image: np.ndarray, points: list[list[float]]) -> np.ndarray:
    curve = np.asarray(points, dtype=np.float64)
    return np.interp(image, curve[:, 0], curve[:, 1])


def run_isp(sensor_dn: np.ndarray, toy_profile: dict) -> tuple[np.ndarray, np.ndarray]:
    sensor = toy_profile["sensor"]
    isp = toy_profile["isp"]
    black = float(sensor["black_level_dn"])
    white = float(sensor["white_level_dn"])
    normalized = np.maximum((sensor_dn - black) / max(white - black, 1e-12), 0.0)

    rgb = demosaic_bilinear(normalized, sensor["cfa"])
    rgb *= np.asarray(isp["white_balance"], dtype=np.float64).reshape(1, 1, 3)
    scene = apply_matrix(rgb, np.asarray(isp["sensor_to_rec2020"], dtype=np.float64))

    denoise_sigma = float(isp.get("denoise_sigma", 0.0))
    if denoise_sigma > 0.0:
        scene = gaussian_filter(scene, sigma=(denoise_sigma, denoise_sigma, 0.0), mode="mirror")

    sharpen_amount = float(isp.get("sharpen_amount", 0.0))
    if sharpen_amount != 0.0:
        radius = float(isp.get("sharpen_radius", 1.0))
        low_pass = gaussian_filter(scene, sigma=(radius, radius, 0.0), mode="mirror")
        scene = scene + sharpen_amount * (scene - low_pass)

    toned = _apply_tone(np.maximum(scene, 0.0), isp["tone_curve"])
    return toned, scene_linear_to_srgb(toned)

