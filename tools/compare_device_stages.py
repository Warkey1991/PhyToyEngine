"""Compare native PFM stage dumps with the Python reference graph."""

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
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--input", type=Path)
    source.add_argument(
        "--synthetic",
        action="store_true",
        help="recreate the deterministic RGB pattern used by phytoy_benchmark",
    )
    parser.add_argument("--width", type=int)
    parser.add_argument("--height", type=int)
    parser.add_argument("--stages", required=True, type=Path)
    parser.add_argument("--host-profile", required=True, type=Path)
    parser.add_argument("--toy-profile", required=True, type=Path)
    parser.add_argument("--seed", required=True, type=int)
    parser.add_argument("--contract", type=Path)
    arguments = parser.parse_args()

    if arguments.synthetic:
        if not arguments.width or not arguments.height:
            parser.error("--synthetic requires nonzero --width and --height")
        yy, xx = np.mgrid[0 : arguments.height, 0 : arguments.width]
        source_image = np.stack(
            [xx % 256, yy % 256, (xx + yy) % 256], axis=-1
        ).astype(np.float64) / 255.0
    else:
        source_image = (
            np.asarray(Image.open(arguments.input).convert("RGB"), dtype=np.float64) / 255.0
        )

    reference = ReferencePipeline(
        load_host_profile(arguments.host_profile), load_toy_profile(arguments.toy_profile)
    ).render_srgb(source_image, seed=arguments.seed)
    limits = None
    if arguments.contract:
        contract = json.loads(arguments.contract.read_text(encoding="utf-8"))
        limits = contract["noiseless_stage_max_absolute_error"]

    limit_names = {
        "01_scene_linear": "scene_linear",
        "02_target_optics": "target_optics",
        "03_target_sensor_dn": "target_sensor_dn",
        "04_target_isp_linear": "target_isp_linear",
        "05_output_srgb": "output_srgb",
    }
    stages = {}
    for name, expected in reference.stages.items():
        actual = read_pfm(arguments.stages / f"{name}.pfm")
        difference = actual - expected
        maximum = float(np.max(np.abs(difference)))
        limit = limits[limit_names[name]] if limits is not None else None
        stages[name] = {
            "max_absolute": float(np.max(np.abs(difference))),
            "mean_absolute": float(np.mean(np.abs(difference))),
            "rmse": float(np.sqrt(np.mean(difference * difference))),
            "limit": limit,
            "passed": limit is None or maximum <= limit,
        }
    passed = all(bool(stage["passed"]) for stage in stages.values())
    print(
        json.dumps(
            {
                "passed": passed,
                "width": int(source_image.shape[1]),
                "height": int(source_image.shape[0]),
                "seed": arguments.seed,
                "stages": stages,
            },
            indent=2,
            sort_keys=True,
        )
    )
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
