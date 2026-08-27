import numpy as np

from phytoy_ref.demosaic import mosaic
from phytoy_ref.optics import _distortion_safe_scale
from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def _gradient(height=48, width=64):
    y, x = np.mgrid[0:height, 0:width]
    return np.stack(
        [
            x / max(width - 1, 1),
            y / max(height - 1, 1),
            (x + y) / max(width + height - 2, 1),
        ],
        axis=-1,
    )


def test_distortion_safe_scale_only_crops_when_edge_sampling_would_fold():
    neutral = _distortion_safe_scale(0.0, 0.0, 0.0, 0.0, np.ones(3))
    assert neutral == 1.0

    distortion = (0.024, -0.008, 0.001, -0.0007)
    ca_scale = np.asarray([1.0026, 1.0, 0.9974])
    safe_scale = _distortion_safe_scale(*distortion, ca_scale)
    assert 0.97 < safe_scale < 0.98

    k1, k2, p1, p2 = distortion
    stationary_radius2 = -k1 / (2.0 * k2)
    maximum_radial = max(
        abs(1.0 + k1 * radius2 + k2 * radius2**2)
        for radius2 in (0.0, 1.0, stationary_radius2, 2.0)
    )
    conservative_edge_bound = max(ca_scale) * (
        maximum_radial + 4.0 * (abs(p1) + abs(p2))
    )
    assert conservative_edge_bound * safe_scale <= 1.0 + 1e-12


def test_srgb_pipeline_is_seed_reproducible(host_srgb_path, toy_path):
    pipeline = ReferencePipeline(
        load_host_profile(host_srgb_path), load_toy_profile(toy_path)
    )
    source = _gradient()
    first = pipeline.render_srgb(source, seed=1234)
    second = pipeline.render_srgb(source, seed=1234)
    different = pipeline.render_srgb(source, seed=1235)
    np.testing.assert_array_equal(first.output_srgb, second.output_srgb)
    assert not np.array_equal(first.output_srgb, different.output_srgb)
    assert set(first.stages) == {
        "01_scene_linear",
        "02_target_optics",
        "03_target_sensor_dn",
        "04_target_isp_linear",
        "05_output_srgb",
    }
    assert np.all(np.isfinite(first.output_srgb))
    assert np.min(first.output_srgb) >= 0.0
    assert np.max(first.output_srgb) <= 1.0


def test_raw_pipeline_executes(host_raw_path, toy_path):
    host = load_host_profile(host_raw_path)
    pipeline = ReferencePipeline(host, load_toy_profile(toy_path))
    rgb = _gradient(32, 40)
    raw_normalized = mosaic(rgb, host["raw"]["cfa"])
    black = host["raw"]["black_level"][0]
    white = host["raw"]["white_level"]
    raw_dn = raw_normalized * (white - black) + black
    result = pipeline.render_raw(raw_dn, seed=9)
    assert result.output_srgb.shape == rgb.shape
    assert np.all(np.isfinite(result.output_srgb))


def test_named_android_raw_profile_executes(host_device_raw_path, toy_path):
    host = load_host_profile(host_device_raw_path)
    pipeline = ReferencePipeline(host, load_toy_profile(toy_path))
    rgb = _gradient(24, 30)
    raw_normalized = mosaic(rgb, host["raw"]["cfa"])
    black_map = np.asarray(host["raw"]["black_level"]).reshape(2, 2)
    black = np.tile(black_map, (12, 15))
    raw_dn = raw_normalized * (host["raw"]["white_level"] - black) + black
    result = pipeline.render_raw(raw_dn, seed=17)
    assert result.output_srgb.shape == rgb.shape
    assert np.all(np.isfinite(result.output_srgb))


def test_flat_input_has_edge_vignette(host_srgb_path, toy_path):
    pipeline = ReferencePipeline(
        load_host_profile(host_srgb_path), load_toy_profile(toy_path)
    )
    source = np.full((64, 64, 3), 0.5, dtype=np.float64)
    result = pipeline.render_srgb(source, seed=7)
    optical = result.stages["02_target_optics"]
    center = float(np.mean(optical[30:34, 30:34]))
    corner = float(np.mean(optical[:4, :4]))
    assert center > corner
