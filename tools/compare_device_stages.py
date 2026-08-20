"""Compare PFM stage dumps from the native CLI with the Python reference graph."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from PIL import Image

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def read_pfm(path: Path) -> np.ndarray:
    with path.open("rb") as stream:
        magic = stream.readline().strip()
        if magic not in {b"PF", b"Pf"}:
            raise ValueError(f"not a PFM file: {path}")
        width, height = [int(value) for value in stream.readline().split()]
        scale = float(stream.readline())
        dtype = "<f4" if scale < 0.0 else ">f4"
        channels = 3 if magic == b"PF" else 1
        data = np.frombuffer(stream.read(), dtype=dtype)
    expected = width * height * channels
    if data.size != expected:
        raise ValueError(f"truncated PFM: {path}; expected {expected}, got {data.size}")
    shape = (height, width, channels) if channels == 3 else (height, width)
    return np.flipud(data.reshape(shape)).astype(np.float64)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", required=True, type=Path)
    parser.add_argument("--stages", required=True, type=Path)
    parser.add_argument("--host-profile", required=True, type=Path)
    parser.add_argument("--toy-profile", required=True, type=Path)
    parser.add_argument("--seed", required=True, type=int)
    arguments = parser.parse_args()

    source = np.asarray(Image.open(arguments.input).convert("RGB"), dtype=np.float64) / 255.0
    reference = ReferencePipeline(
        load_host_profile(arguments.host_profile), load_toy_profile(arguments.toy_profile)
    ).render_srgb(source, seed=arguments.seed)
    report = {}
    for name, expected in reference.stages.items():
        actual = read_pfm(arguments.stages / f"{name}.pfm")
        difference = actual - expected
        report[name] = {
            "max_absolute": float(np.max(np.abs(difference))),
            "mean_absolute": float(np.mean(np.abs(difference))),
            "rmse": float(np.sqrt(np.mean(difference * difference))),
        }
    print(json.dumps(report, indent=2, sort_keys=True))


if __name__ == "__main__":
    main()

