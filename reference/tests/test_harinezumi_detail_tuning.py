"""Design-intent checks for sample-informed detail and monochrome gradation."""
import json
import os

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profile_package import compile_profile
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def render(root, name, source):
    toy = load_toy_profile(root / f'profiles/authoring/{name}.json')
    toy['sensor']['noise_enabled'] = False
    return ReferencePipeline(load_host_profile(root / 'profiles/authoring/host_generic_srgb.json'), toy).render_srgb(source).output_srgb


def test_daylight_texture_is_stronger_without_changing_constant_colors(project_root):
    # Use the parameter reference grid proportionally, not a tiny preview where
    # scaled Gaussian support would hide the difference between both tunings.
    yy, xx = np.mgrid[:96, :128]
    source = np.repeat((.45 + .06 * np.sin(2*np.pi*xx/8))[..., None], 3, axis=-1)
    host = load_host_profile(project_root / 'profiles/authoring/host_generic_srgb.json')
    outputs = []
    grays = []
    for v in [3,4]:
        toy = load_toy_profile(project_root / f'profiles/authoring/toy_harinezumi_2pp_daylight_v0_{v}.json')
        toy['reference_sampling'] = {'width':128,'height':96}
        toy['sensor']['noise_enabled'] = False
        pipe = ReferencePipeline(host, toy)
        outputs.append(pipe.render_srgb(source).output_srgb[24:-24,24:-24])
        grays.append(pipe.render_srgb(np.full_like(source,.45)).output_srgb[24:-24,24:-24])
    assert np.std(outputs[1]) > np.std(outputs[0])*1.01
    np.testing.assert_allclose(grays[1],grays[0],atol=.001,rtol=0)


def test_mono_keeps_more_shadow_gray_and_retains_black(project_root):
    names=[f'toy_harinezumi_2pp_mono_v0_{v}' for v in [1,2]]
    for value in [.08,.2,.4]:
        means=[render(project_root,n,np.full((24,32,3),value))[8:16,12:20].mean() for n in names]
        assert means[1] > means[0]
    assert render(project_root,names[1],np.zeros((24,32,3))).max() == 0
    assert render(project_root,names[1],np.ones((24,32,3)))[8:16,12:20].min() > .98


@pytest.mark.parametrize('name',['toy_harinezumi_2pp_daylight_v0_4','toy_harinezumi_2pp_mono_v0_2'])
def test_tuning_package_matches_shipping(project_root,tmp_path,name):
    package=compile_profile(project_root/f'profiles/authoring/{name}.json',tmp_path/'tuning.ptp')
    assert package.read_bytes()==(project_root/f'android/sdk/src/main/assets/phytoy/{name}.ptp').read_bytes()


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY'),reason='native library unavailable')
@pytest.mark.parametrize('name',['toy_harinezumi_2pp_daylight_v0_4','toy_harinezumi_2pp_mono_v0_2'])
def test_new_tuning_matches_native(project_root,tmp_path,name):
    from test_cpp_conformance import _native_render
    toy=load_toy_profile(project_root/f'profiles/authoring/{name}.json')
    toy['sensor']['noise_enabled']=False
    path=tmp_path/'tuning.json';path.write_text(json.dumps(toy))
    package=compile_profile(path,tmp_path/'tuning.ptp')
    source=np.random.default_rng(23).uniform(.01,.95,(48,64,3))
    actual,_=_native_render(source,toy_package=str(package),seed=7)
    np.testing.assert_allclose(actual,render(project_root,name,source),atol=.005,rtol=0)
