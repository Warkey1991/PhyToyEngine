"""Behavioral bounds for a public-sample approximation, not real-camera fidelity tests."""
from __future__ import annotations

import copy
import json
import os

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profile_package import compile_profile, load_compiled_profile
from phytoy_ref.profiles import load_host_profile, load_toy_profile

PROFILE = 'toy_harinezumi_2pp_daylight_v0_1'


def _pipeline(project_root, *, noiseless=False):
    host = load_host_profile(project_root / 'profiles/authoring/host_generic_srgb.json')
    toy = load_toy_profile(project_root / f'profiles/authoring/{PROFILE}.json')
    if noiseless:
        toy = copy.deepcopy(toy)
        toy['sensor']['noise_enabled'] = False
    return ReferencePipeline(host, toy)


def test_shipping_package_matches_authoring_and_is_explicitly_unmeasured(project_root, tmp_path):
    authoring = project_root / f'profiles/authoring/{PROFILE}.json'
    package = compile_profile(authoring, tmp_path / 'study.ptp')
    shipping = project_root / f'android/sdk/src/main/assets/phytoy/{PROFILE}.ptp'
    assert shipping.read_bytes() == package.read_bytes()
    provenance = load_compiled_profile(package)['provenance']
    assert provenance['profile_type'] == 'designed'
    assert provenance['calibration_status'] == 'public_sample_approximation'
    assert not provenance.get('dataset_sha256')


def test_daylight_grays_preserve_order_and_usable_midtone(project_root):
    pipeline = _pipeline(project_root, noiseless=True)
    levels = [0.0, 0.1, 0.25, 0.45, 0.65, 0.8, 1.0]
    outputs = [pipeline.render_srgb(np.full((20, 24, 3), level)).output_srgb[7:13, 9:15].mean(axis=(0, 1)) for level in levels]
    outputs = np.asarray(outputs)
    assert np.all(np.diff(outputs.mean(axis=1)) > 0)
    assert outputs[0].max() < 0.03
    assert 0.3 < outputs[3].mean() < 0.6
    assert np.ptp(outputs[3]) < 0.08  # Neutral objects must not acquire a dominant color cast.
    assert outputs[-1].min() > 0.9


def test_daylight_color_separation_without_heavy_black_corners(project_root):
    pipeline = _pipeline(project_root, noiseless=True)
    for channel in range(3):
        patch = np.full((32, 40, 3), 0.25)
        patch[..., channel] = 0.65
        output = pipeline.render_srgb(patch).output_srgb[10:22, 14:26].mean(axis=(0, 1))
        assert output[channel] > np.max(np.delete(output, channel)) + 0.35
    gray = pipeline.render_srgb(np.full((48, 64, 3), 0.5)).output_srgb
    assert gray[:5, :5].mean() / gray[20:28, 28:36].mean() > 0.65


def test_study_noise_is_repeatable_but_not_a_static_overlay(project_root):
    pipeline = _pipeline(project_root)
    source = np.full((32, 40, 3), 0.32)
    a = pipeline.render_srgb(source, seed=123).output_srgb
    b = pipeline.render_srgb(source, seed=123).output_srgb
    c = pipeline.render_srgb(source, seed=124).output_srgb
    np.testing.assert_array_equal(a, b)
    assert np.std(a - c) > 0.001
    assert np.max(np.abs(a - c)) < 0.15


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY') or not os.environ.get('PHYTOY_CPP_PROFILE_DIR'), reason='native conformance environment is not configured')
def test_study_all_five_stages_match_cpp(project_root, tmp_path):
    from test_cpp_conformance import _native_render

    pipeline = _pipeline(project_root, noiseless=True)
    source_path = tmp_path / 'noiseless.json'
    source_path.write_text(json.dumps(pipeline.toy_profile))
    package = compile_profile(source_path, tmp_path / 'noiseless.ptp')
    yy, xx = np.mgrid[0:26, 0:34]
    source = np.stack([xx / 33, yy / 25, 0.1 + 0.7 * ((xx + yy) % 9) / 8], axis=-1)
    expected = pipeline.render_srgb(source, seed=7)
    output, stages = _native_render(source, toy_package=str(package), seed=7)
    for stage, (name, reference), limit in zip(range(1, 6), expected.stages.items(), [2e-5, 8e-5, 1.01, 0.003, 0.005]):
        actual = stages[stage]
        if reference.ndim == 2:
            actual = actual[..., 0]
        assert np.max(np.abs(actual - reference)) <= limit, name
    assert np.max(np.abs(output - expected.output_srgb)) <= 0.005
