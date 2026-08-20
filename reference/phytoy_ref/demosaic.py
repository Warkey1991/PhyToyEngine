"""Deterministic Bayer utilities shared by host normalization and target ISP."""

from __future__ import annotations

import numpy as np
from scipy.ndimage import convolve


_BILINEAR_KERNEL = np.array(
    [[1.0, 2.0, 1.0], [2.0, 4.0, 2.0], [1.0, 2.0, 1.0]], dtype=np.float64
)


def cfa_masks(height: int, width: int, pattern: str) -> np.ndarray:
    pattern = pattern.upper()
    if pattern not in {"RGGB", "BGGR", "GRBG", "GBRG"}:
        raise ValueError(f"unsupported CFA pattern: {pattern}")

    layout = np.array(list(pattern)).reshape(2, 2)
    masks = np.zeros((height, width, 3), dtype=np.float64)
    channels = {"R": 0, "G": 1, "B": 2}
    for row in range(2):
        for column in range(2):
            masks[row::2, column::2, channels[layout[row, column]]] = 1.0
    return masks


def mosaic(rgb: np.ndarray, pattern: str) -> np.ndarray:
    image = np.asarray(rgb, dtype=np.float64)
    masks = cfa_masks(image.shape[0], image.shape[1], pattern)
    return np.sum(image * masks, axis=2)


def demosaic_bilinear(raw: np.ndarray, pattern: str) -> np.ndarray:
    """Normalized bilinear demosaic with deterministic mirrored boundaries."""

    plane = np.asarray(raw, dtype=np.float64)
    masks = cfa_masks(plane.shape[0], plane.shape[1], pattern)
    output = np.empty((*plane.shape, 3), dtype=np.float64)
    for channel in range(3):
        values = plane * masks[..., channel]
        numerator = convolve(values, _BILINEAR_KERNEL, mode="mirror")
        denominator = convolve(masks[..., channel], _BILINEAR_KERNEL, mode="mirror")
        output[..., channel] = numerator / np.maximum(denominator, 1e-12)
    return output

