"""Resolution-aware approximation of optics/ISP footprints and sensor noise.

This preserves signal response and estimates noise power after CFA interpolation.
It is not a replacement for simulating and reducing a complete fixed CFA grid.
"""
from __future__ import annotations

import copy
import math

import numpy as np


def _bayer_axis_energy(bin_width: float) -> float:
    """Mean squared weights of box-reduced, linearly interpolated R/B samples.

    Average both CFA phases. At native resolution it is (1 + .5)/2 = .75.
    This accounts for neighboring RGB pixels sharing the same raw sample.
    """
    if bin_width <= 1:
        return .75
    if bin_width > 64:
        return 2/bin_width  # Asymptotic integrated tent footprint.
    energies = []
    for phase in [0, 1]:
        left, right = phase-.5, phase+bin_width-.5
        weights: dict[int, float] = {}
        for pixel in range(phase, math.ceil(right+.5)):
            weight = max(0, min(right, pixel+.5)-max(left, pixel-.5))/bin_width
            index = pixel//2
            taps = [(index, 1)] if pixel % 2 == 0 else [(index, .5), (index+1, .5)]
            for raw_index, interpolation in taps:
                weights[raw_index] = weights.get(raw_index, 0)+weight*interpolation
        energies.append(sum(w*w for w in weights.values()))
    return sum(energies)/2


def profile_for_resolution(profile: dict, width: int, height: int) -> dict:
    sampling = profile.get('reference_sampling')
    if sampling is None:
        return profile
    if width <= 0 or height <= 0:
        raise ValueError('render dimensions must be positive')
    sx = width / sampling['width']
    sy = height / sampling['height']
    # Bound extrapolation; apply the shared GPU footprint limits below as well.
    if max(sx, sy) > 2:
        raise ValueError('reference_sampling supports at most 2x the reference dimensions')
    result = copy.deepcopy(profile)
    del result['reference_sampling']  # Materialized parameters must never scale twice.
    bases = np.asarray(profile['optics']['psf']['bases'], dtype=np.float64)
    ry, rx = bases.shape[1] // 2, bases.shape[2] // 2
    out_rx, out_ry = math.ceil(rx*sx), math.ceil(ry*sy)
    scale = math.sqrt(sx*sy)
    isp = result['isp']
    if (max(out_rx, out_ry) > 4 or isp.get('denoise_sigma', 0)*scale > 1 or
            (isp.get('sharpen_amount', 0) != 0 and isp.get('sharpen_radius', 1)*scale > 1)):
        raise ValueError('reference_sampling exceeds shared filter support (9x9 PSF, Gaussian sigma <= 1)')
    if sx == 1 and sy == 1:
        return result
    scaled = np.zeros((len(bases), 2*out_ry+1, 2*out_rx+1))
    # Deposit each tap at its scaled coordinate; conserve energy and centroid.
    for y in range(bases.shape[1]):
        for x in range(bases.shape[2]):
            px, py = (x-rx)*sx+out_rx, (y-ry)*sy+out_ry
            ix, iy = math.floor(px), math.floor(py)
            tx, ty = px-ix, py-iy
            for dx, wx in [(0, 1-tx), (1, tx)]:
                for dy, wy in [(0, 1-ty), (1, ty)]:
                    if wx*wy > 0:
                        scaled[:, iy+dy, ix+dx] += bases[:, y, x]*wx*wy
    result['optics']['psf']['bases'] = scaled.tolist()
    isp['denoise_sigma'] = isp.get('denoise_sigma', 0)*scale
    isp['sharpen_radius'] = isp.get('sharpen_radius', 1)*scale
    sensor = result['sensor']
    noise_gain = math.sqrt(_bayer_axis_energy(1/sx)*_bayer_axis_energy(1/sy))/.75
    area = 1/(noise_gain*noise_gain)
    for key in ['exposure_scale_e', 'full_well_e', 'conversion_gain_e_per_dn']:
        sensor[key] *= area
    for key in ['read_noise_e', 'dsnu_e']:
        sensor[key] = sensor.get(key, 0)*math.sqrt(area)
    sensor['prnu_sigma'] = sensor.get('prnu_sigma', 0)*noise_gain
    sensor['row_noise_e'] = sensor.get('row_noise_e', 0)*area*math.sqrt(min(sy, 1))
    sensor['column_noise_e'] = sensor.get('column_noise_e', 0)*area*math.sqrt(min(sx, 1))
    return result
