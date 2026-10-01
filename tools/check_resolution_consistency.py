"""Measure preview/still consistency using a synthetic signal, not camera fidelity."""
from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def shrink(image, factor):
    h, w = image.shape[:2]
    return image.reshape(h//factor, factor, w//factor, factor, 3).mean(axis=(1, 3))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile-version', choices=['0.2','0.3'], default='0.3')
    parser.add_argument('--width', type=int, default=384)
    parser.add_argument('--height', type=int, default=288)
    parser.add_argument('--factor', type=int, default=3)
    parser.add_argument('--output-dir', type=Path, required=True)
    args = parser.parse_args()
    if args.factor not in (2, 3) or any(n < 96 or n > 2048 or n % (2*args.factor)
                                       for n in (args.width, args.height)):
        parser.error('dimensions must be multiples of 2*factor in [96,2048]; factor must be 2 or 3')
    args.output_dir.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[1]
    version_suffix = args.profile_version.replace('.', '_')
    profile_path = root/f'profiles/authoring/toy_harinezumi_2pp_daylight_v{version_suffix}.json'
    toy = load_toy_profile(profile_path)
    toy['reference_sampling'] = {'width':args.width, 'height':args.height}
    legacy = copy.deepcopy(toy)
    del legacy['reference_sampling']
    host = load_host_profile(root/'profiles/authoring/host_generic_srgb.json')
    metrics = {'purpose':'Synthetic sampling diagnostic; not real-camera fidelity',
               'profile_sha256':hashlib.sha256(profile_path.read_bytes()).hexdigest(),
               'reference_grid':toy['reference_sampling'], 'factor':args.factor,
               'noise_seeds':[11,22], 'versions':{}}
    source = np.full((args.height, args.width, 3), .32)
    crop = np.s_[10:-10, 10:-10]
    for name, profile in [('legacy', legacy), ('scaled', toy)]:
        render = ReferencePipeline(host, profile).render_srgb
        full_delta = shrink(render(source, seed=11).output_srgb-render(source, seed=22).output_srgb, args.factor)
        preview = shrink(source, args.factor)
        small_delta = render(preview, seed=11).output_srgb-render(preview, seed=22).output_srgb
        ratio = float(np.std(small_delta[crop])/np.std(full_delta[crop]))
        metrics['versions'][name] = {'temporal_noise_ratio_preview_to_reduced_still':ratio,
                                     'relative_noise_mismatch':abs(ratio-1)}
        print(f'{name}: noise ratio {ratio:.5f}', flush=True)
    yy, xx = np.mgrid[:args.height, :args.width]
    source = np.repeat((.4+.18*np.sin(2*np.pi*(xx+.5)/(args.width/8)))[..., None], 3, axis=2)
    reference = None
    panels = []
    for name, profile in [('legacy', legacy), ('scaled', toy)]:
        profile['sensor']['noise_enabled'] = False
        render = ReferencePipeline(host, profile).render_srgb
        if reference is None:
            reference = shrink(render(source).output_srgb, args.factor)
        preview = render(shrink(source, args.factor)).output_srgb
        error = float(np.sqrt(np.mean((preview[crop]-reference[crop])**2)))
        metrics['versions'][name]['detail_rmse'] = error
        panels.append(preview)
    panels.append(reference)
    w, h = reference.shape[1], reference.shape[0]
    sheet = Image.new('RGB', (3*w, h+52), '#191b20')
    draw = ImageDraw.Draw(sheet)
    for i, (label, array) in enumerate(zip(['Pixel-based preview', 'Scaled preview', 'Reduced still'], panels)):
        draw.text((i*w+4, 6), label, fill='white')
        sheet.paste(Image.fromarray(np.rint(np.clip(array, 0, 1)*255).astype('uint8')), (i*w, 26))
    draw.text((4, h+32), 'Synthetic noiseless detail signal', fill='white')
    sheet.save(args.output_dir/'detail.png')
    (args.output_dir/'metrics.json').write_text(json.dumps(metrics, indent=2)+'\n')
    print(args.output_dir.resolve())


if __name__ == '__main__':
    main()
