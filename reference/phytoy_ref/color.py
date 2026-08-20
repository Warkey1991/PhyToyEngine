"""Explicit scene-linear color conversions used by the reference engine."""

from __future__ import annotations

import numpy as np


SRGB_TO_XYZ_D65 = np.array(
    [
        [0.4124564, 0.3575761, 0.1804375],
        [0.2126729, 0.7151522, 0.0721750],
        [0.0193339, 0.1191920, 0.9503041],
    ],
    dtype=np.float64,
)

XYZ_D65_TO_REC2020 = np.array(
    [
        [1.7166511880, -0.3556707838, -0.2533662814],
        [-0.6666843518, 1.6164812366, 0.0157685458],
        [0.0176398574, -0.0427706133, 0.9421031212],
    ],
    dtype=np.float64,
)

REC2020_TO_XYZ_D65 = np.linalg.inv(XYZ_D65_TO_REC2020)
XYZ_D65_TO_SRGB = np.linalg.inv(SRGB_TO_XYZ_D65)
SRGB_TO_REC2020 = XYZ_D65_TO_REC2020 @ SRGB_TO_XYZ_D65
REC2020_TO_SRGB = XYZ_D65_TO_SRGB @ REC2020_TO_XYZ_D65


def apply_matrix(image: np.ndarray, matrix: np.ndarray) -> np.ndarray:
    """Apply a 3x3 row-vector color transform to an HxWx3 image."""

    array = np.asarray(image, dtype=np.float64)
    transform = np.asarray(matrix, dtype=np.float64).reshape(3, 3)
    return np.einsum("...c,dc->...d", array, transform, optimize=True)


def srgb_decode(encoded: np.ndarray) -> np.ndarray:
    encoded = np.asarray(encoded, dtype=np.float64)
    return np.where(
        encoded <= 0.04045,
        encoded / 12.92,
        np.power((encoded + 0.055) / 1.055, 2.4),
    )


def srgb_encode(linear: np.ndarray) -> np.ndarray:
    linear = np.asarray(linear, dtype=np.float64)
    nonnegative = np.maximum(linear, 0.0)
    return np.where(
        nonnegative <= 0.0031308,
        nonnegative * 12.92,
        1.055 * np.power(nonnegative, 1.0 / 2.4) - 0.055,
    )


def srgb_to_scene_linear(encoded: np.ndarray) -> np.ndarray:
    return apply_matrix(srgb_decode(encoded), SRGB_TO_REC2020)


def scene_linear_to_srgb(linear_rec2020: np.ndarray) -> np.ndarray:
    linear_srgb = apply_matrix(linear_rec2020, REC2020_TO_SRGB)
    return np.clip(srgb_encode(linear_srgb), 0.0, 1.0)

