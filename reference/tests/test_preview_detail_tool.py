"""Independent sanity checks for synthetic detail metrics, not tuning thresholds."""
from __future__ import annotations

import importlib.util
import json
import os
import subprocess
import sys
from pathlib import Path

import numpy as np
from PIL import Image
from scipy.ndimage import gaussian_filter


def detail_tool(project_root):
    spec = importlib.util.spec_from_file_location('preview_detail_tool', project_root/'tools/check_preview_detail.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_float_resize_preserves_signal_without_8bit_quantization(project_root):
    tool = detail_tool(project_root)
    source = np.full((30, 40, 3), .123456)
    resized = tool.resize_float(source, (77, 57), Image.Resampling.BILINEAR)
    assert resized.shape == (57, 77, 3)
    np.testing.assert_allclose(resized, np.full_like(resized, .123456), atol=1e-7, rtol=0)
    assert abs(resized[0, 0, 0]-round(.123456*255)/255) > .001


def test_presentation_minification_matches_four_taps_without_prefiltering(project_root):
    tool = detail_tool(project_root)
    source = np.zeros((8, 8, 3))
    source[3, 3] = 1
    # 8 -> 2 samples x/y centers 1.5 and 5.5, so this isolated pixel is missed.
    # PIL bilinear minification uses a widened antialias kernel and differs here.
    presented = tool.present_bilinear(source, (2, 2))
    np.testing.assert_array_equal(presented, np.zeros((2, 2, 3)))
    assert tool.resize_float(source, (2, 2), Image.Resampling.BILINEAR).max() > 0
    np.testing.assert_array_equal(tool.present_bilinear(source, (8, 8)), source)


def test_slanted_edge_metric_tracks_known_gaussian_blur(project_root):
    tool = detail_tool(project_root)
    yy, xx = np.mgrid[:128, :160]
    step = np.clip(.5+xx-80-.125*(yy-64), 0, 1)
    source = np.repeat((.34+.16*step)[..., None], 3, axis=2)
    values = []
    for sigma in (.8, 1.6, 3.2):
        blurred = gaussian_filter(source, (sigma, sigma, 0))
        measured = tool.edge_mtf50(blurred, (.2, .1, .8, .9))
        assert abs(measured['edge_slope']-.125) < .003
        values.append(measured['mtf50_cycles_per_display_pixel'])
    assert .5 > values[0] > values[1] > values[2] > 0
    # Analytic Gaussian MTF50 sqrt(2 ln 2)/(2 pi sigma); finite window
    # and rasterized ESF broaden the exact ideal, so use a diagnostic tolerance.
    expected = np.sqrt(2*np.log(2))/(2*np.pi*1.6)
    assert abs(values[1]-expected) < .02
    constant = tool.edge_mtf50(np.full_like(source, .4), (.2, .1, .8, .9))
    assert constant['mtf50_cycles_per_display_pixel'] is None
    assert constant['reason'] == 'no measurable edge contrast'


def test_diagnostic_cli_reports_provenance_and_refuses_existing_directory(project_root, tmp_path):
    env = {**os.environ, 'PYTHONPATH': str(project_root/'reference')}
    output = tmp_path/'detail'
    command = [sys.executable, str(project_root/'tools/check_preview_detail.py'),
               '--width', '192', '--output-dir', str(output)]
    subprocess.run(command, env=env, cwd=project_root, check=True, capture_output=True, text=True)
    report = json.loads((output/'metrics.json').read_text())
    assert report['noise_enabled'] is False
    assert report['model_reference_size'] == [192, 144]
    assert report['model_display_size_landscape'] == [144, 108]
    assert len(report['profile_sha256']) == 64
    assert 'not a real-camera' in report['purpose']
    assert len(report['previews']) == 3
    assert [entry['target_stream_size'] for entry in report['previews']] == [[640, 480], [1280, 960], [1440, 1080]]
    for entry in report['previews']:
        assert np.isfinite(entry['encoded_rgb_rmse_to_displayed_still'])
        assert np.isfinite(entry['high_frequency_region_rmse_to_displayed_still'])
        value = entry['slanted_edge']['mtf50_cycles_per_display_pixel']
        assert value is None or 0 < value <= .5
    assert (output/'comparison.png').is_file()
    with Image.open(output/'comparison.png') as image:
        assert image.size == (576, 172)
    previous = (output/'metrics.json').read_bytes()
    rejected = subprocess.run(command, env=env, cwd=project_root, capture_output=True, text=True)
    assert rejected.returncode != 0
    assert (output/'metrics.json').read_bytes() == previous
