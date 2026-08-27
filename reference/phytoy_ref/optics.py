"""Target lens projection, chromatic aberration, vignetting and blur field."""

from __future__ import annotations

import numpy as np
from scipy.ndimage import map_coordinates
from scipy.signal import convolve2d


def _normalized_grid(height: int, width: int) -> tuple[np.ndarray, np.ndarray]:
    y, x = np.mgrid[0:height, 0:width]
    xn = ((x + 0.5) / width) * 2.0 - 1.0
    yn = ((y + 0.5) / height) * 2.0 - 1.0
    return xn, yn


def _to_pixel_coordinates(
    xn: np.ndarray, yn: np.ndarray, height: int, width: int
) -> tuple[np.ndarray, np.ndarray]:
    x = ((xn + 1.0) * 0.5 * width) - 0.5
    y = ((yn + 1.0) * 0.5 * height) - 0.5
    return x, y


def _distortion_safe_scale(
    k1: float, k2: float, p1: float, p2: float, ca_scale: np.ndarray
) -> float:
    candidates = [0.0, 1.0, 2.0]
    if abs(k2) > np.finfo(np.float64).eps:
        stationary_radius2 = -k1 / (2.0 * k2)
        if 0.0 < stationary_radius2 < 2.0:
            candidates.append(stationary_radius2)
    maximum_radial = max(
        abs(1.0 + k1 * radius2 + k2 * radius2 * radius2)
        for radius2 in candidates
    )
    tangential_bound = 4.0 * (abs(p1) + abs(p2))
    boundary_scale = float(np.max(ca_scale)) * (maximum_radial + tangential_bound)
    return 1.0 / max(boundary_scale, 1.0)


def warp_distortion_ca(image: np.ndarray, optics_profile: dict) -> np.ndarray:
    source = np.asarray(image, dtype=np.float64)
    height, width, _ = source.shape
    xn, yn = _normalized_grid(height, width)
    k1, k2, p1, p2 = [float(value) for value in optics_profile["distortion"]]
    ca_scale = np.asarray(optics_profile["ca_scale"], dtype=np.float64)
    safe_scale = _distortion_safe_scale(k1, k2, p1, p2, ca_scale)
    xn = xn * safe_scale
    yn = yn * safe_scale
    radius2 = xn * xn + yn * yn
    radial = 1.0 + k1 * radius2 + k2 * radius2 * radius2
    x_base = xn * radial + 2.0 * p1 * xn * yn + p2 * (radius2 + 2.0 * xn * xn)
    y_base = yn * radial + p1 * (radius2 + 2.0 * yn * yn) + 2.0 * p2 * xn * yn

    output = np.empty_like(source)
    for channel in range(3):
        x_source, y_source = _to_pixel_coordinates(
            x_base * ca_scale[channel], y_base * ca_scale[channel], height, width
        )
        output[..., channel] = map_coordinates(
            source[..., channel],
            [y_source, x_source],
            order=1,
            mode="reflect",
            prefilter=False,
        )
    return output


def apply_vignette(image: np.ndarray, optics_profile: dict) -> np.ndarray:
    source = np.asarray(image, dtype=np.float64)
    xn, yn = _normalized_grid(source.shape[0], source.shape[1])
    radius2 = xn * xn + yn * yn
    coefficients = np.asarray(optics_profile["vignette"], dtype=np.float64)
    if coefficients.shape == (2,):
        coefficients = np.repeat(coefficients.reshape(1, 2), 3, axis=0)
    if coefficients.shape != (3, 2):
        raise ValueError("vignette must be [v1,v2] or three channel pairs")
    gain = np.empty_like(source)
    for channel in range(3):
        v1, v2 = coefficients[channel]
        gain[..., channel] = np.maximum(1.0 - v1 * radius2 - v2 * radius2**2, 0.0)
    return source * gain


def _interpolate_map(grid: np.ndarray, height: int, width: int) -> np.ndarray:
    grid = np.asarray(grid, dtype=np.float64)
    y = np.linspace(0.0, grid.shape[0] - 1.0, height)
    x = np.linspace(0.0, grid.shape[1] - 1.0, width)
    yy, xx = np.meshgrid(y, x, indexing="ij")
    return map_coordinates(grid, [yy, xx], order=1, mode="nearest", prefilter=False)


def apply_spatial_psf(image: np.ndarray, optics_profile: dict) -> np.ndarray:
    source = np.asarray(image, dtype=np.float64)
    psf = optics_profile["psf"]
    bases = np.asarray(psf["bases"], dtype=np.float64)
    coefficients = np.asarray(psf["coefficients"], dtype=np.float64)
    if bases.ndim != 3:
        raise ValueError("PSF bases must have shape KxHxW")
    if coefficients.ndim != 4 or coefficients.shape[:2] != (bases.shape[0], 3):
        raise ValueError("PSF coefficients must have shape Kx3xGridHxGridW")

    basis_sums = np.sum(bases, axis=(1, 2))
    if np.any(np.abs(basis_sums) < 1e-12):
        raise ValueError("PSF basis energy cannot be zero")
    normalized_bases = bases / basis_sums[:, None, None]

    output = np.zeros_like(source)
    weight_sum = np.zeros_like(source)
    for basis_index, kernel in enumerate(normalized_bases):
        for channel in range(3):
            blurred = convolve2d(
                source[..., channel], kernel, mode="same", boundary="symm"
            )
            weight = _interpolate_map(
                coefficients[basis_index, channel], source.shape[0], source.shape[1]
            )
            output[..., channel] += blurred * weight
            weight_sum[..., channel] += weight
    return output / np.maximum(weight_sum, 1e-12)


def apply_optics(scene_linear: np.ndarray, toy_profile: dict) -> np.ndarray:
    optics = toy_profile["optics"]
    warped = warp_distortion_ca(scene_linear, optics)
    blurred = apply_spatial_psf(warped, optics)
    return apply_vignette(blurred, optics)
