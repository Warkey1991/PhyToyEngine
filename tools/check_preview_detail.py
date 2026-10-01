"""Diagnose preview detail using noiseless synthetic charts at matched display sizes.

Default 384x288 is a 1:5 model of 1920x1440. It preserves stream size ratios,
not real-device pixel footprints or Camera2 processing. --width 1920 --allow-large
runs the full reference grid, with a substantially higher NumPy memory budget.
"""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw
from scipy.ndimage import map_coordinates

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


STREAMS = [(640, 480), (1280, 960), (1440, 1080)]


def resize_float(image: np.ndarray, size: tuple[int, int], resample: Image.Resampling) -> np.ndarray:
    """Resize encoded RGB without first quantizing to 8-bit PNG pixels."""
    return np.stack([
        np.asarray(Image.fromarray(image[..., channel].astype(np.float32)).resize(size, resample),
                   dtype=np.float64)
        for channel in range(3)
    ], axis=-1)


def present_bilinear(image: np.ndarray, size: tuple[int, int]) -> np.ndarray:
    """Replicate present.frag four-tap sampling, including minification without prefiltering."""
    width, height = size
    xs = (np.arange(width)+.5)*image.shape[1]/width-.5
    ys = (np.arange(height)+.5)*image.shape[0]/height-.5
    yy, xx = np.meshgrid(ys, xs, indexing='ij')
    return np.stack([map_coordinates(image[..., channel], (yy, xx), order=1,
                                     mode='nearest', prefilter=False)
                     for channel in range(3)], axis=-1)


def synthetic_chart(width: int, height: int) -> tuple[np.ndarray, tuple[float, float, float, float]]:
    """Low contrast slanted edge plus high frequency bars/checkers and color patches."""
    yy, xx = np.mgrid[:height, :width]
    signal = np.full((height, width, 3), .42)
    edge_roi = (.06, .06, .46, .46)
    x0, y0, x1, y1 = [int(v*n) for v, n in zip(edge_roi, (width, height, width, height))]
    cx, cy = (x0+x1)/2, (y0+y1)/2
    # Eight subpixel samples along x reduce arbitrary binary raster phase.
    distance = xx[y0:y1, x0:x1] - cx - .125*(yy[y0:y1, x0:x1]-cy)
    coverage = np.zeros_like(distance, dtype=np.float64)
    for offset in (np.arange(8)+.5)/8-.5:
        coverage += (distance+offset >= 0)/8
    signal[y0:y1, x0:x1] = (.34+.16*coverage)[..., None]
    colors = [[.65, .22, .18], [.22, .58, .28], [.17, .33, .67], [.72, .60, .20]]
    for index, color in enumerate(colors):
        left = int(width*(.54+index*.10))
        right = int(width*(.54+(index+1)*.10))
        signal[int(height*.06):int(height*.25), left:right] = color
    ramp_left, ramp_right = int(width*.54), int(width*.94)
    ramp = np.linspace(.04, .86, ramp_right-ramp_left)
    signal[int(height*.30):int(height*.46), ramp_left:ramp_right] = ramp[None, :, None]
    # Include periods near the low resolution preview's Nyquist limit, not only broad stripes.
    for index, period in enumerate((2, 4, 8, 16)):
        left, right = int(width*(.06+index*.22)), int(width*(.06+(index+1)*.22))
        for top, bottom, checker in ((.55, .73, False), (.79, .94, True)):
            ys = slice(int(height*top), int(height*bottom))
            xs = slice(left, right)
            pattern = xx[ys, xs]//period
            if checker:
                pattern = pattern + yy[ys, xs]//period
            signal[ys, xs] = (.24+.36*(pattern % 2))[..., None]
    return signal, edge_roi


def edge_mtf50(image: np.ndarray, roi: tuple[float, float, float, float]) -> dict:
    """Estimate slanted-edge MTF50; diagnostic e-SFR, not certified ISO 12233.

    Fit an edge location per row, bin its ESF at four samples/display pixel,
    differentiate, Hann-window the LSF, and find its normalized FFT's 50% crossing.
    Values beyond display Nyquist are not reported as resolvable detail.
    """
    height, width = image.shape[:2]
    x0, y0, x1, y1 = [int(v*n) for v, n in zip(roi, (width, height, width, height))]
    grey = np.mean(image[y0:y1, x0:x1], axis=-1)
    if min(grey.shape) < 16:
        return {'mtf50_cycles_per_display_pixel': None, 'reason': 'edge ROI too small'}
    tail = max(2, grey.shape[1]//8)
    low, high = float(np.median(grey[:, :tail])), float(np.median(grey[:, -tail:]))
    if high-low <= 1e-5:
        return {'mtf50_cycles_per_display_pixel': None, 'reason': 'no measurable edge contrast'}
    threshold = (low+high)/2
    centers = []
    for row in grey:
        crossing = np.flatnonzero((row[:-1] <= threshold) & (row[1:] > threshold))
        if len(crossing) == 0:
            return {'mtf50_cycles_per_display_pixel': None, 'reason': 'edge crossing unavailable'}
        position = crossing[np.argmin(abs(crossing-grey.shape[1]/2))]
        centers.append(position+(threshold-row[position])/(row[position+1]-row[position]))
    slope, intercept = np.polyfit(np.arange(grey.shape[0]), centers, 1)
    yy, xx = np.mgrid[:grey.shape[0], :grey.shape[1]]
    distance = (xx-(intercept+slope*yy))/np.sqrt(1+slope*slope)
    oversample = 4
    radius = min(12, grey.shape[1]//4)
    bins = np.arange(-radius, radius+1/oversample, 1/oversample)
    weights, _ = np.histogram(distance, bins=bins, weights=grey)
    count, _ = np.histogram(distance, bins=bins)
    valid = count > 0
    if valid.sum() < 16:
        return {'mtf50_cycles_per_display_pixel': None, 'reason': 'insufficient ESF samples'}
    positions = (bins[:-1]+bins[1:])/2
    esf = np.interp(positions, positions[valid], weights[valid]/count[valid])
    lsf = np.gradient(esf, 1/oversample)*np.hanning(len(esf))
    spectrum = np.abs(np.fft.rfft(lsf, n=4096))
    spectrum /= max(spectrum[0], 1e-12)
    frequency = np.fft.rfftfreq(4096, d=1/oversample)
    crossings = np.flatnonzero((spectrum[:-1] >= .5) & (spectrum[1:] < .5))
    mtf50 = None
    if len(crossings):
        index = crossings[0]
        value = frequency[index]+(spectrum[index]-.5)/(spectrum[index]-spectrum[index+1])*(frequency[index+1]-frequency[index])
        if value <= .5:
            mtf50 = float(value)
    return {
        'mtf50_cycles_per_display_pixel': mtf50,
        'edge_slope': float(slope),
        'edge_contrast_encoded_srgb': high-low,
        'mtf50_above_display_nyquist': mtf50 is None and bool(len(crossings)),
    }


def save_rgb(array: np.ndarray, path: Path) -> None:
    Image.fromarray(np.rint(np.clip(array, 0, 1)*255).astype(np.uint8)).save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--width', type=int, default=384, help='4:3 reference model width; multiple of 24')
    parser.add_argument('--display-short-edge', type=int, default=1080, help='real display short edge; scaled with the model')
    parser.add_argument('--surface-short-edge', type=int, default=0, help='0: native display buffer; 720: model current extra Surface scaling')
    parser.add_argument('--profile', type=Path, help='toy profile JSON; default shipping DH 2++ color v0.3')
    parser.add_argument('--allow-large', action='store_true', help='allow a reference width above 768 (higher NumPy memory use)')
    parser.add_argument('--output-dir', type=Path, required=True, help='must be a new directory')
    args = parser.parse_args()
    if args.width < 96 or args.width > 1920 or args.width % 24:
        parser.error('--width must be a multiple of 24 in [96,1920]')
    if args.width > 768 and not args.allow_large:
        parser.error('reference widths above 768 require --allow-large')
    if not 480 <= args.display_short_edge <= 1920:
        parser.error('--display-short-edge must be in [480,1920]')
    if args.surface_short_edge != 0 and not 480 <= args.surface_short_edge <= 1920:
        parser.error('--surface-short-edge must be 0 or in [480,1920]')
    root = Path(__file__).resolve().parents[1]
    profile_path = args.profile or root/'profiles/authoring/toy_harinezumi_2pp_daylight_v0_3.json'
    host_path = root/'profiles/authoring/host_generic_srgb.json'
    toy = copy.deepcopy(load_toy_profile(profile_path))
    toy['sensor']['noise_enabled'] = False
    height = args.width*3//4
    toy['reference_sampling'] = {'width': args.width, 'height': height}
    pipeline = ReferencePipeline(load_host_profile(host_path), toy)
    source, edge_roi = synthetic_chart(args.width, height)
    scale = args.width/1920
    display_short = max(1, round(args.display_short_edge*scale))
    display_size = (round(display_short*4/3), display_short)
    surface_short = round((args.surface_short_edge or args.display_short_edge)*scale)
    surface_size = (round(surface_short*4/3), surface_short)
    args.output_dir.mkdir(parents=True, exist_ok=False)
    started = time.perf_counter()
    reference_output = pipeline.render_srgb(source, seed=1).output_srgb
    reference_seconds = time.perf_counter()-started
    # LANCZOS represents still photo scaling. Preview reproduces bilinear present.frag.
    reference = resize_float(reference_output, display_size, Image.Resampling.LANCZOS)
    save_rgb(source, args.output_dir/'synthetic_source.png')
    save_rgb(reference, args.output_dir/'reference_still_display.png')
    reports = []
    panels = [('Reference still', reference)]
    for real_width, real_height in STREAMS:
        model_size = (round(real_width*scale), round(real_height*scale))
        camera2_input = resize_float(source, model_size, Image.Resampling.BOX)
        started = time.perf_counter()
        output = pipeline.render_srgb(camera2_input, seed=1).output_srgb
        elapsed = time.perf_counter()-started
        surface = present_bilinear(output, surface_size)
        displayed = present_bilinear(surface, display_size) if surface_size != display_size else surface
        border = max(2, display_short//50)
        crop = np.s_[border:-border, border:-border]
        detail = np.s_[int(display_short*.55):int(display_short*.94), int(display_size[0]*.06):int(display_size[0]*.94)]
        reports.append({
            'target_stream_size': [real_width, real_height],
            'model_stream_size': list(model_size),
            'encoded_rgb_rmse_to_displayed_still': float(np.sqrt(np.mean((displayed[crop]-reference[crop])**2))),
            'high_frequency_region_rmse_to_displayed_still': float(np.sqrt(np.mean((displayed[detail]-reference[detail])**2))),
            'slanted_edge': edge_mtf50(displayed, edge_roi),
            'python_reference_render_seconds': elapsed,
        })
        name = f'preview_{real_width}x{real_height}_display.png'
        save_rgb(displayed, args.output_dir/name)
        panels.append((f'Preview {real_width}x{real_height}', displayed))
        print(f'{real_width}x{real_height}: RMSE {reports[-1]["encoded_rgb_rmse_to_displayed_still"]:.6f}, edge MTF50 {reports[-1]["slanted_edge"]["mtf50_cycles_per_display_pixel"]}', flush=True)
    panel_width, panel_height = display_size
    sheet = Image.new('RGB', (panel_width*4, panel_height+64), '#191b20')
    draw = ImageDraw.Draw(sheet)
    for column, (label, array) in enumerate(panels):
        draw.text((column*panel_width+4, 8), label, fill='white')
        sheet.paste(Image.fromarray(np.rint(np.clip(array, 0, 1)*255).astype(np.uint8)), (column*panel_width, 30))
    draw.text((4, panel_height+42), f'Synthetic noiseless diagnostic | model {args.width}x{height} | no Camera2/GPU measurements', fill='#d4d7dd')
    sheet.save(args.output_dir/'comparison.png')
    report = {
        'purpose': 'Noiseless synthetic preview/still detail diagnostic; not a real-camera fidelity measurement',
        'backend': 'Python reference',
        'model_reference_size': [args.width, height],
        'target_reference_size': [1920, 1440],
        'model_scale': scale,
        'target_display_short_edge': args.display_short_edge,
        'model_display_size_landscape': list(display_size),
        'target_surface_short_edge': args.surface_short_edge or args.display_short_edge,
        'model_surface_size_landscape': list(surface_size),
        'noise_enabled': False,
        'profile_path': str(profile_path.resolve()),
        'profile_sha256': hashlib.sha256(profile_path.read_bytes()).hexdigest(),
        'host_sha256': hashlib.sha256(host_path.read_bytes()).hexdigest(),
        'assumptions': [
            'Camera2 preview input approximated by encoded-sRGB box reduction of the same synthetic source; vendor ISP/3A differences excluded.',
            'Preview scales with Vulkan four-tap bilinear interpolation, optionally through an intermediate Surface; still uses Lanczos. No extra sharpening or engine modifications.',
            'Default reduced model preserves stream ratios but its Bayer lattice and chart periods cannot be extrapolated to real-device resolution.',
            'MTF is diagnostic encoded-sRGB slanted-edge e-SFR without ISO derivative correction; values beyond display Nyquist are not reported.',
            'Python render seconds are tool runtime only; Android GPU latency, thermal behavior and steady-state FPS require device measurements.',
        ],
        'reference_still_slanted_edge': edge_mtf50(reference, edge_roi),
        'reference_python_render_seconds': reference_seconds,
        'previews': reports,
    }
    (args.output_dir/'metrics.json').write_text(json.dumps(report, indent=2)+'\n')
    print(args.output_dir.resolve())


if __name__ == '__main__':
    main()
