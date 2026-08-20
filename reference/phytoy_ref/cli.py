"""Command-line reference renderer for images and NumPy RAW fixtures."""

from __future__ import annotations

import argparse
from pathlib import Path

import numpy as np
from PIL import Image

from .pipeline import ReferencePipeline
from .profiles import load_host_profile, load_toy_profile


def _load_image(path: Path) -> np.ndarray:
    if path.suffix.lower() == ".npy":
        return np.load(path)
    return np.asarray(Image.open(path).convert("RGB"), dtype=np.float64) / 255.0


def _save_png(path: Path, image: np.ndarray) -> None:
    encoded = np.rint(np.clip(image, 0.0, 1.0) * 255.0).astype(np.uint8)
    Image.fromarray(encoded, mode="RGB").save(path)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--host-profile", required=True, type=Path)
    parser.add_argument("--toy-profile", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--seed", type=int, default=1)
    parser.add_argument("--dump-stages", type=Path)
    arguments = parser.parse_args()

    host = load_host_profile(arguments.host_profile)
    toy = load_toy_profile(arguments.toy_profile)
    pipeline = ReferencePipeline(host, toy)
    source = _load_image(arguments.input)
    if host["input"]["mode"] == "raw":
        result = pipeline.render_raw(source, seed=arguments.seed)
    elif host["input"]["mode"] == "srgb":
        result = pipeline.render_srgb(source, seed=arguments.seed)
    else:
        raise ValueError("CLI YUV rendering requires direct Python API plane input")

    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    _save_png(arguments.output, result.output_srgb)
    if arguments.dump_stages:
        arguments.dump_stages.mkdir(parents=True, exist_ok=True)
        for name, stage in result.stages.items():
            np.save(arguments.dump_stages / f"{name}.npy", stage)


if __name__ == "__main__":
    main()

