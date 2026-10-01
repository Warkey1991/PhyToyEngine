"""Guard the v0.3 tuning intent; no claim of measured real-camera similarity."""
import json
import os

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile
from phytoy_ref.profile_package import compile_profile


def render(project_root, version, source):
    toy = load_toy_profile(project_root/f'profiles/authoring/toy_harinezumi_2pp_daylight_v0_{version}.json')
    toy['sensor']['noise_enabled'] = False
    return ReferencePipeline(load_host_profile(project_root/'profiles/authoring/host_generic_srgb.json'), toy).render_srgb(source).output_srgb


@pytest.mark.parametrize('channel', [0, 2])
def test_saturated_color_ramps_keep_more_gradation(project_root, channel):
    source = np.full((48,256,3), .16)
    source[:,:,channel] = np.linspace(.3,.95,256)
    old, new = [render(project_root, v, source)[12:36,12:-12,channel] for v in [2,3]]
    assert np.mean(new > .995) < np.mean(old > .995)*.7
    assert new.max() > .95  # Still reaches vivid highlights.
    assert np.std(new) > np.std(old)*.85


def test_shadows_open_without_lifting_black_and_neutrals_lose_magenta_bias(project_root):
    black = render(project_root, 3, np.zeros((24,32,3)))
    assert black.max() == 0
    for level in [.08,.2]:
        old, new = [render(project_root,v,np.full((24,32,3),level))[8:16,12:20] for v in [2,3]]
        assert new.mean() > old.mean()
        assert new.mean() < level
    old, new = [render(project_root,v,np.full((24,32,3),.45))[8:16,12:20].mean(axis=(0,1)) for v in [2,3]]
    assert np.ptp(new) < np.ptp(old)*.5


def test_v03_package_and_sampling_contract(project_root, tmp_path):
    paths = [project_root/f'profiles/authoring/toy_harinezumi_2pp_daylight_v0_{v}.json' for v in [2,3]]
    old, new = [load_toy_profile(p) for p in paths]
    assert old['reference_sampling'] == new['reference_sampling']
    assert old['sensor'] == new['sensor']
    assert old['optics'] == new['optics']
    assert new['provenance']['calibration_status'] == 'public_sample_approximation'
    package = compile_profile(paths[1],tmp_path/'v03.ptp')
    assert package.read_bytes() == (project_root/'android/sdk/src/main/assets/phytoy/toy_harinezumi_2pp_daylight_v0_3.ptp').read_bytes()


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY'),reason='native library unavailable')
def test_v03_native_output_matches_reference(project_root,tmp_path):
    from test_cpp_conformance import _native_render
    toy = load_toy_profile(project_root/'profiles/authoring/toy_harinezumi_2pp_daylight_v0_3.json')
    toy['sensor']['noise_enabled'] = False
    authoring = tmp_path/'v03.json'
    authoring.write_text(json.dumps(toy))
    package = compile_profile(authoring,tmp_path/'v03.ptp')
    source = np.random.default_rng(17).uniform(.05,.9,(48,64,3))
    actual, _ = _native_render(source,toy_package=str(package))
    assert np.max(np.abs(actual-render(project_root,3,source))) < .005
