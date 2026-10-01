"""Monochrome signal behavior and backend parity, not measured camera fidelity."""
import copy
import json
import os

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile, validate_toy_profile, ProfileError
from phytoy_ref.profile_package import compile_profile

NAME = 'toy_harinezumi_2pp_mono_v0_1'


def pipeline(root, noise=False):
    toy = load_toy_profile(root / f'profiles/authoring/{NAME}.json')
    toy['sensor']['noise_enabled'] = noise
    return ReferencePipeline(load_host_profile(root / 'profiles/authoring/host_generic_srgb.json'), toy)


def test_monochrome_has_no_chroma_even_with_sensor_noise(project_root):
    source = np.random.default_rng(19).uniform(0, 1, (40, 64, 3))
    pipe = pipeline(project_root, noise=True)
    a = pipe.render_srgb(source, seed=9).output_srgb
    b = pipe.render_srgb(source, seed=10).output_srgb
    np.testing.assert_array_equal(a[..., 0], a[..., 1])
    np.testing.assert_array_equal(a[..., 1], a[..., 2])
    assert np.std(a - b) > 0.0001
    np.testing.assert_array_equal(a, pipe.render_srgb(source, seed=9).output_srgb)


def test_mono_gray_order_black_and_highlight(project_root):
    pipe = pipeline(project_root)
    levels = [0, .08, .2, .4, .6, .8, 1]
    values = [pipe.render_srgb(np.full((24, 32, 3), x)).output_srgb[8:16, 12:20].mean() for x in levels]
    assert values[0] == 0
    assert values[-1] > .97
    assert np.all(np.diff(values) > 0)


@pytest.mark.parametrize('value', [1, 0, 'true', None])
def test_monochrome_requires_boolean(project_root, value):
    toy = copy.deepcopy(pipeline(project_root).toy_profile)
    toy['isp']['monochrome'] = value
    with pytest.raises(ProfileError, match='monochrome'):
        validate_toy_profile(toy)


def test_shipping_mono_package(project_root, tmp_path):
    package = compile_profile(project_root / f'profiles/authoring/{NAME}.json', tmp_path / 'mono.ptp')
    assert package.read_bytes() == (project_root / f'android/sdk/src/main/assets/phytoy/{NAME}.ptp').read_bytes()


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY'), reason='native library unavailable')
def test_mono_all_stages_match_native(project_root, tmp_path):
    from test_cpp_conformance import _native_render
    pipe = pipeline(project_root)
    path = tmp_path / 'mono.json'
    path.write_text(json.dumps(pipe.toy_profile))
    package = compile_profile(path, tmp_path / 'mono.ptp')
    source = np.random.default_rng(13).uniform(.01, .99, (26, 34, 3))
    expected = pipe.render_srgb(source, seed=7)
    actual, stages = _native_render(source, toy_package=str(package), seed=7)
    for i, (reference, limit) in enumerate(zip(expected.stages.values(), [2e-5, 8e-5, 1.01, .003, .005]), 1):
        stage = stages[i][..., 0] if reference.ndim == 2 else stages[i]
        assert np.max(np.abs(stage-reference)) <= limit
    np.testing.assert_allclose(actual, expected.output_srgb, atol=.005, rtol=0)
    np.testing.assert_array_equal(actual[..., 0], actual[..., 2])
