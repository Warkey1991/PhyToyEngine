"""Spatial ISP filter regression: order, mirrored boundaries, native and shader budgets."""
from __future__ import annotations

import copy
import importlib.util
import os
import shutil
import subprocess
from pathlib import Path

import numpy as np
import pytest
from scipy.ndimage import convolve1d, gaussian_filter

from phytoy_ref.demosaic import demosaic_bilinear
from phytoy_ref.isp import run_isp
from phytoy_ref.profile_package import compile_profile
from phytoy_ref.profiles import load_toy_profile


def fixture(project_root):
    return load_toy_profile(project_root/'profiles/authoring/toy_isp_filter_conformance.json')


def discrete_gaussian(sigma):
    radius = int(4*sigma+.5)
    if not radius:
        return np.array([1.0])
    positions = np.arange(-radius, radius+1)
    weights = np.exp(-.5*(positions/sigma)**2)
    return weights/weights.sum()


@pytest.mark.parametrize('shape', [(1, 1), (1, 17), (2, 2), (15, 17), (18, 22), (31, 33)])
@pytest.mark.parametrize('sigmas', [(0, .65), (.8, .65), (1, 1), (.12, .12)])
def test_combined_lowpass_matches_sequential_gaussians_at_mirrored_edges(shape, sigmas):
    """Validate the fused lowpass's mathematics on tiny and partial workgroups.

    This is an independent host oracle, not execution of the Android GPU shader.
    """
    source = np.random.default_rng(14).uniform(.12, .65, (*shape, 3))
    denoise_sigma, sharpen_sigma = sigmas
    denoised = gaussian_filter(source, (denoise_sigma, denoise_sigma, 0), mode='mirror')
    expected = gaussian_filter(denoised, (sharpen_sigma, sharpen_sigma, 0), mode='mirror')
    combined = np.convolve(discrete_gaussian(denoise_sigma), discrete_gaussian(sharpen_sigma))
    actual = convolve1d(convolve1d(source, combined, axis=1, mode='mirror'), combined, axis=0, mode='mirror')
    np.testing.assert_allclose(actual, expected, rtol=1e-13, atol=1e-13)


def test_dual_filter_fixture_detects_unfiltered_sharpen_lowpass(project_root):
    toy = fixture(project_root)
    yy, xx = np.mgrid[:18, :22]
    normalized = .27+.18*((xx+yy) % 3 == 0)+.12*(xx >= 11)
    sensor = toy['sensor']
    raw = sensor['black_level_dn']+normalized*(sensor['white_level_dn']-sensor['black_level_dn'])
    scene = demosaic_bilinear(normalized, sensor['cfa'])
    denoised = gaussian_filter(scene, (.8, .8, 0), mode='mirror')
    correct = denoised+1.2*(denoised-gaussian_filter(denoised, (.65, .65, 0), mode='mirror'))
    wrong_order = denoised+1.2*(denoised-gaussian_filter(scene, (.65, .65, 0), mode='mirror'))
    toned, _ = run_isp(raw, toy)
    np.testing.assert_allclose(toned, np.clip(correct, 0, 1), atol=1e-13, rtol=1e-13)
    assert np.max(np.abs(correct-wrong_order)) > .015
    assert not toy['sensor']['noise_enabled']


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY') or not os.environ.get('PHYTOY_CPP_PROFILE_DIR'),
                    reason='native library conformance environment is not configured')
@pytest.mark.parametrize('shape', [(1, 1), (2, 2), (15, 17), (18, 22), (31, 33)])
@pytest.mark.parametrize('mode', ['both', 'denoise_only', 'sharpen_only', 'identity'])
def test_native_spatial_filter_stage_matches_reference(project_root, tmp_path, shape, mode):
    from test_cpp_conformance import _native_render
    toy = copy.deepcopy(fixture(project_root))
    if mode in ('sharpen_only', 'identity'):
        toy['isp']['denoise_sigma'] = 0
    if mode in ('denoise_only', 'identity'):
        toy['isp']['sharpen_amount'] = 0
    authoring = tmp_path/'filters.json'
    import json
    authoring.write_text(json.dumps(toy))
    package = compile_profile(authoring, tmp_path/'filters.ptp')
    yy, xx = np.mgrid[:shape[0], :shape[1]]
    source = np.stack([.2+.3*((xx+yy) % 3 == 0), .3+.25*(xx % 2), .15+.3*(yy % 2)], axis=-1)
    output, stages = _native_render(source, toy_package=str(package), seed=1234)
    # Feed the captured native sensor DN into the reference ISP to isolate its
    # filter arithmetic from upstream ADC rounding and color normalization.
    toned, encoded = run_isp(stages[3][..., 0], toy)
    np.testing.assert_allclose(stages[4], toned, atol=2e-6, rtol=0)
    np.testing.assert_allclose(output, encoded, atol=8e-6, rtol=0)
    assert np.all(np.isfinite(output))


def test_isp_shader_compiles_within_guaranteed_workgroup_budget(project_root, tmp_path):
    compiler = os.environ.get('PHYTOY_GLSLC') or shutil.which('glslc')
    cache = project_root/'build/CMakeCache.txt'
    if not compiler and cache.is_file():
        for line in cache.read_text().splitlines():
            if line.startswith('PHYTOY_GLSLC:FILEPATH='):
                compiler = line.split('=', 1)[1]
    if not compiler or not Path(compiler).is_file():
        pytest.skip('glslc compiler is unavailable')
    spirv = tmp_path/'isp.spv'
    subprocess.run([compiler, '--target-env=vulkan1.1', '-O',
                    str(project_root/'backends/vulkan/shaders/isp.comp'), '-o', str(spirv)], check=True)
    spec = importlib.util.spec_from_file_location('spirv_memory', project_root/'tools/check_spirv_workgroup_memory.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    amount = module.workgroup_memory_bytes(spirv)
    assert 0 < amount <= 32768


@pytest.mark.skipif(not os.environ.get('PHYTOY_CPP_LIBRARY') or not os.environ.get('PHYTOY_CPP_PROFILE_DIR'),
                    reason='native library conformance environment is not configured')
@pytest.mark.parametrize('field, value, message', [('denoise_sigma', -.2, 'nonnegative'),
                                                  ('sharpen_radius', 0, 'positive')])
def test_native_rejects_invalid_filter_values_even_with_valid_package_checksum(project_root, tmp_path, field, value, message):
    """Native package consumers cannot assume the authoring validator ran."""
    import ctypes
    import hashlib
    import json
    from phytoy_ref.profile_package import HEADER, MAGIC
    toy = fixture(project_root)
    toy['isp'][field] = value
    payload = json.dumps(toy, separators=(',', ':')).encode()
    path = tmp_path/'invalid.ptp'
    path.write_bytes(HEADER.pack(MAGIC, 1, 2, len(payload), hashlib.sha256(payload).digest())+payload)
    library = ctypes.CDLL(os.environ['PHYTOY_CPP_LIBRARY'])
    library.pte_engine_create.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    library.pte_engine_create.restype = ctypes.c_int
    library.pte_last_error.restype = ctypes.c_char_p
    engine = ctypes.c_void_p()
    status = library.pte_engine_create(os.fsencode(Path(os.environ['PHYTOY_CPP_PROFILE_DIR'])/'host_generic_srgb.ptp'),
                                       os.fsencode(path), ctypes.byref(engine))
    assert status == 3  # PTE_STATUS_PROFILE_ERROR
    assert engine.value is None
    assert message in library.pte_last_error().decode()
