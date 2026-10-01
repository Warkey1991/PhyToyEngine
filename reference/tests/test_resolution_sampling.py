"""Compare preview with a reduced still, including CFA-correlated temporal noise."""
from __future__ import annotations

import copy
import os

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profile_package import compile_profile
from phytoy_ref.profiles import load_host_profile, load_toy_profile, ProfileError
from phytoy_ref.sampling import profile_for_resolution


def study(project_root):
    return load_toy_profile(project_root / 'profiles/authoring/toy_harinezumi_2pp_daylight_v0_2.json')


def pipeline(project_root, toy):
    return ReferencePipeline(load_host_profile(project_root / 'profiles/authoring/host_generic_srgb.json'), toy)


def reduce(image, factor):
    h, w = image.shape[:2]
    return image.reshape(h//factor, factor, w//factor, factor, 3).mean(axis=(1, 3))


@pytest.mark.parametrize('value', [None, {}, {'width': 63, 'height': 48}, {'width': True, 'height': 48},
                                  {'width': 64, 'height': 0}, {'width': 64, 'height': 48, 'typo': 1}])
def test_invalid_reference_grid_rejected(project_root, value):
    toy = study(project_root)
    toy['reference_sampling'] = value
    with pytest.raises(ProfileError):
        load_toy_profile(toy)


def test_materialization_preserves_source_energy_and_signal_response(project_root):
    toy = study(project_root)
    original = copy.deepcopy(toy)
    small = profile_for_resolution(toy, 640, 480)
    assert toy == original
    np.testing.assert_allclose(np.sum(small['optics']['psf']['bases'], axis=(1, 2)),
                               np.sum(toy['optics']['psf']['bases'], axis=(1, 2)), atol=1e-12)
    for field in ['exposure_scale_e', 'full_well_e']:
        assert small['sensor'][field]/small['sensor']['conversion_gain_e_per_dn'] == pytest.approx(
            toy['sensor'][field]/toy['sensor']['conversion_gain_e_per_dn'])
    assert profile_for_resolution(small, 640, 480) is small  # No double scaling.
    assert profile_for_resolution(toy, 1920, 1440) == {k:v for k,v in toy.items() if k != 'reference_sampling'}
    with pytest.raises(ValueError, match='2x'):
        profile_for_resolution(toy, 4000, 3000)
    with pytest.raises(ValueError, match='filter support'):
        profile_for_resolution(toy, 3840, 2880)


@pytest.mark.parametrize('factor', [2, 3])
def test_preview_noise_matches_reduced_still_better_than_legacy(project_root, factor):
    toy = study(project_root)
    # Smaller reference grid exercises the same size ratios quickly.
    toy['reference_sampling'] = {'width': 384, 'height': 288}
    legacy = copy.deepcopy(toy)
    del legacy['reference_sampling']
    source = np.full((288, 384, 3), .32)
    ratios = []
    for profile in [legacy, toy]:
        render = pipeline(project_root, profile).render_srgb
        full_delta = reduce(render(source, seed=11).output_srgb-render(source, seed=22).output_srgb, factor)
        small = reduce(source, factor)
        preview_delta = render(small, seed=11).output_srgb-render(small, seed=22).output_srgb
        # Frame differences isolate temporal noise from vignetting, WB and PRNU.
        ratios.append(np.std(preview_delta[10:-10, 10:-10])/np.std(full_delta[10:-10, 10:-10]))
    assert abs(ratios[1]-1) < .15
    assert abs(ratios[1]-1) < abs(ratios[0]-1)*.6


def test_detail_difference_is_reduced_without_changing_reference_still(project_root):
    toy = study(project_root)
    toy['reference_sampling'] = {'width':384, 'height':288}
    toy['sensor']['noise_enabled'] = False
    legacy = copy.deepcopy(toy)
    del legacy['reference_sampling']
    yy, xx = np.mgrid[:288, :384]
    source = np.repeat((.4+.18*np.sin(2*np.pi*(xx+.5)/48))[..., None], 3, axis=2)
    baseline = pipeline(project_root, legacy).render_srgb(source).output_srgb
    np.testing.assert_array_equal(baseline, pipeline(project_root, toy).render_srgb(source).output_srgb)
    reduced = reduce(baseline, 3)[12:-12, 12:-12]
    errors = []
    for profile in [legacy, toy]:
        preview = pipeline(project_root, profile).render_srgb(reduce(source, 3)).output_srgb[12:-12, 12:-12]
        errors.append(np.sqrt(np.mean((preview-reduced)**2)))
    assert errors[1] < errors[0]*.9


def test_shipping_v02_package_matches_authoring(project_root, tmp_path):
    filename = 'toy_harinezumi_2pp_daylight_v0_2'
    package = compile_profile(project_root/f'profiles/authoring/{filename}.json', tmp_path/'study.ptp')
    assert package.read_bytes() == (project_root/f'android/sdk/src/main/assets/phytoy/{filename}.ptp').read_bytes()


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY'), reason='native library unavailable')
@pytest.mark.parametrize('shape', [(48, 64), (96, 128), (120, 160)])
def test_scaled_five_stages_match_cpp(project_root, tmp_path, shape):
    import json
    from test_cpp_conformance import _native_render
    toy = study(project_root)
    toy['reference_sampling'] = {'width':128, 'height':96}
    toy['sensor']['noise_enabled'] = False
    authoring = tmp_path/'study.json'
    authoring.write_text(json.dumps(toy))
    package = compile_profile(authoring, tmp_path/'study.ptp')
    yy, xx = np.mgrid[:shape[0], :shape[1]]
    source = np.stack([xx/(shape[1]-1), yy/(shape[0]-1), .3+.1*np.sin(xx/4)], axis=-1)
    expected = pipeline(project_root, toy).render_srgb(source, seed=17)
    output, stages = _native_render(source, toy_package=str(package), seed=17)
    for stage, (name, reference), limit in zip(range(1, 6), expected.stages.items(), [2e-5, 8e-5, 1.01, .003, .005]):
        actual = stages[stage]
        if reference.ndim == 2:
            actual = actual[..., 0]
        assert np.max(np.abs(actual-reference)) <= limit, name
    assert np.max(np.abs(output-expected.output_srgb)) <= .005
