from __future__ import annotations

import numpy as np

from phytoy_ref.normalize import normalize_raw, normalize_srgb, normalize_yuv420
from phytoy_ref.profiles import load_host_profile


def test_neutral_yuv_matches_neutral_srgb(project_root):
    host = load_host_profile(project_root / "profiles/authoring/host_generic_yuv420.json")
    y = np.full((8, 10), 0.5)
    uv = np.full((4, 5, 2), 0.5)
    yuv_scene = normalize_yuv420(y, uv, host, full_range=True)
    rgb_scene = normalize_srgb(np.full((8, 10, 3), 0.5), host)
    np.testing.assert_allclose(yuv_scene, rgb_scene, atol=1e-12)


def test_raw_black_level_normalizes_to_zero(host_raw_path):
    host = load_host_profile(host_raw_path)
    black = np.asarray(host["raw"]["black_level"]).reshape(2, 2)
    raw = np.tile(black, (5, 6))
    scene = normalize_raw(raw, host)
    np.testing.assert_allclose(scene, 0.0, atol=1e-12)


def test_limited_range_yuv_black_is_zero(project_root):
    host = load_host_profile(project_root / "profiles/authoring/host_generic_yuv420.json")
    y = np.full((4, 4), 16.0 / 255.0)
    uv = np.full((2, 2, 2), 128.0 / 255.0)
    scene = normalize_yuv420(y, uv, host, full_range=False)
    np.testing.assert_allclose(scene, 0.0, atol=1e-12)

