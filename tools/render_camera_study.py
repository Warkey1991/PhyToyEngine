"""Render reproducible input / previous DH 2++ / current DH 2++ comparisons with provenance."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageOps

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def diagnostic_chart() -> Image.Image:
    """Synthetic signal diagnostic, not evidence of real-camera fidelity."""
    width, height = 768, 576
    yy, xx = np.mgrid[:height, :width]
    rgb = np.full((height, width, 3), 0.5)
    rgb[:144] = np.linspace(0, 1, width)[None, :, None]
    colors = [[.85,.15,.12], [.18,.7,.25], [.12,.3,.85], [.85,.7,.12],
              [.12,.7,.7], [.7,.2,.65], [.76,.57,.46], [.45,.31,.24]]
    for index, color in enumerate(colors):
        rgb[144:288, index*96:(index+1)*96] = color
    for index, period in enumerate([2, 4, 8, 16]):
        region = slice(index*192, (index+1)*192)
        pattern = ((xx[288:432, region] // period + yy[288:432, region] // period) % 2)
        rgb[288:432, region] = (.2 + .6*pattern)[..., None]
    rgb[432:] = np.linspace(.025, .3, width)[None, :, None]
    return Image.fromarray(np.rint(rgb*255).astype(np.uint8))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, nargs='+', help='Your own sRGB JPEG/PNG photographs; omitted: synthetic chart')
    parser.add_argument('--output-dir', type=Path, default=Path('renders/harinezumi_2pp'))
    parser.add_argument('--max-edge', type=int, default=1024)
    parser.add_argument('--seed', type=int, default=220100)
    args = parser.parse_args()
    if not 32 <= args.max_edge <= 2048:
        parser.error('--max-edge must be between 32 and 2048 (reference renderer memory budget)')
    if args.seed < 0:
        parser.error('--seed must be nonnegative')
    # A fresh directory protects earlier comparisons and input photographs.
    args.output_dir.mkdir(parents=True, exist_ok=False)
    root = Path(__file__).resolve().parents[1]
    host_path = root / 'profiles/authoring/host_generic_srgb.json'
    profile_paths = [root / 'profiles/authoring' / name for name in [
        'toy_harinezumi_2pp_daylight_v0_4.json', 'toy_harinezumi_2pp_mono_v0_2.json']]
    pipelines = [ReferencePipeline(load_host_profile(host_path), load_toy_profile(p)) for p in profile_paths]
    manifest = {
        'purpose': 'Visual comparison; not a calibrated real-camera match',
        'backend': 'Python reference', 'seed': args.seed,
        'sampling': 'Same input size; DH 2++ uses its reference_sampling grid; legacy styles use render pixels',
        'profiles': [{'path': str(p.relative_to(root)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                     for p in [host_path, *profile_paths]],
        'images': [],
    }
    for index, source_path in enumerate(args.input or [None]):
        if source_path:
            with Image.open(source_path) as opened:
                source = ImageOps.exif_transpose(opened).convert('RGB')
            source_hash = hashlib.sha256(source_path.read_bytes()).hexdigest()
        else:
            source, source_hash = diagnostic_chart(), None
        source.thumbnail((args.max_edge, args.max_edge), Image.Resampling.LANCZOS)
        array = np.asarray(source, dtype=np.float64) / 255
        outputs = [source]
        for pipeline in pipelines:
            output = pipeline.render_srgb(array, seed=args.seed).output_srgb
            outputs.append(Image.fromarray(np.rint(np.clip(output, 0, 1)*255).astype(np.uint8)))
        labels = ['Input sRGB', 'DH 2++ color v0.4', 'DH 2++ mono v0.2']
        sheet = Image.new('RGB', (source.width*3, source.height+64), '#191b20')
        draw = ImageDraw.Draw(sheet)
        for column, (label, output) in enumerate(zip(labels, outputs)):
            output.save(args.output_dir / f'{index:02d}_{column}.png')
            draw.text((column*source.width+12, 10), label, fill='white')
            sheet.paste(output, (column*source.width, 32))
        draw.text((12, source.height+42), 'Synthetic diagnostic - no real camera reference' if source_path is None
                  else 'Same input and seed - public-sample approximation, not hardware calibration', fill='#d4d7dd')
        comparison = f'{index:02d}_comparison.png'
        sheet.save(args.output_dir / comparison)
        manifest['images'].append({'source': str(source_path) if source_path else 'synthetic_chart_v1',
                                   'source_sha256': source_hash, 'size': list(source.size), 'comparison': comparison})
    (args.output_dir / 'manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    print(args.output_dir.resolve())


if __name__ == '__main__':
    main()
