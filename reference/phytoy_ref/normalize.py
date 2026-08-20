"""Host RAW, YUV and encoded RGB normalization into canonical scene-linear RGB."""

from __future__ import annotations

import numpy as np

from .color import XYZ_D65_TO_REC2020, apply_matrix, srgb_to_scene_linear
from .demosaic import demosaic_bilinear


def normalize_srgb(encoded: np.ndarray, host_profile: dict) -> np.ndarray:
    image = np.asarray(encoded, dtype=np.float64)
    if image.ndim != 3 or image.shape[2] != 3:
        raise ValueError("sRGB input must have shape HxWx3")
    scene = srgb_to_scene_linear(np.clip(image, 0.0, 1.0))
    residual = np.asarray(
        host_profile.get("normalization", {}).get("rgb_residual_matrix", np.eye(3)),
        dtype=np.float64,
    )
    return apply_matrix(scene, residual)


def normalize_yuv420(
    y_plane: np.ndarray,
    uv_plane: np.ndarray,
    host_profile: dict,
    *,
    full_range: bool = True,
) -> np.ndarray:
    """Decode a full-resolution Y plane and half-resolution interleaved UV plane."""

    y = np.asarray(y_plane, dtype=np.float64)
    uv = np.asarray(uv_plane, dtype=np.float64)
    if uv.ndim != 3 or uv.shape[2] != 2:
        raise ValueError("UV input must have shape H/2 x W/2 x 2")

    u = np.repeat(np.repeat(uv[..., 0], 2, axis=0), 2, axis=1)[: y.shape[0], : y.shape[1]]
    v = np.repeat(np.repeat(uv[..., 1], 2, axis=0), 2, axis=1)[: y.shape[0], : y.shape[1]]
    if full_range:
        yy = y
        cb = u - 0.5
        cr = v - 0.5
    else:
        yy = (y - 16.0 / 255.0) * (255.0 / 219.0)
        cb = (u - 128.0 / 255.0) * (255.0 / 224.0)
        cr = (v - 128.0 / 255.0) * (255.0 / 224.0)

    # BT.709 Y'CbCr to nonlinear RGB.
    rgb = np.stack(
        [
            yy + 1.5748 * cr,
            yy - 0.187324 * cb - 0.468124 * cr,
            yy + 1.8556 * cb,
        ],
        axis=-1,
    )
    return normalize_srgb(np.clip(rgb, 0.0, 1.0), host_profile)


def normalize_raw(raw: np.ndarray, host_profile: dict) -> np.ndarray:
    plane = np.asarray(raw, dtype=np.float64)
    raw_config = host_profile["raw"]
    black = np.asarray(raw_config["black_level"], dtype=np.float64)
    if black.size == 1:
        black_map = np.full_like(plane, black.item())
    elif black.size == 4:
        layout = black.reshape(2, 2)
        black_map = np.empty_like(plane)
        for row in range(2):
            for column in range(2):
                black_map[row::2, column::2] = layout[row, column]
    else:
        raise ValueError("black_level must contain one or four values")

    white = float(raw_config["white_level"])
    normalized = (plane - black_map) / np.maximum(white - black_map, 1e-12)
    normalized = np.maximum(normalized, 0.0)

    rgb = demosaic_bilinear(normalized, raw_config["cfa"])
    white_balance = np.asarray(raw_config["white_balance"], dtype=np.float64)
    rgb *= white_balance.reshape(1, 1, 3)
    camera_to_xyz = np.asarray(raw_config["camera_to_xyz_d65"], dtype=np.float64)
    xyz = apply_matrix(rgb, camera_to_xyz)
    scene = apply_matrix(xyz, XYZ_D65_TO_REC2020)

    exposure_scale = float(raw_config.get("radiometric_scale", 1.0))
    return scene * exposure_scale

