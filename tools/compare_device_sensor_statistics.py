"""Compare native sensor sample statistics with the Python reference graph."""

from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--host-profile", required=True, type=Path)
    parser.add_argument("--toy-profile", required=True, type=Path)
    parser.add_argument("--contract", required=True, type=Path)
    arguments = parser.parse_args()

    report = json.loads(arguments.report.read_text(encoding="utf-8"))
    contract = json.loads(arguments.contract.read_text(encoding="utf-8"))
    limits = contract["stochastic_consistency"]
    width = int(report["width"])
    height = int(report["height"])
    value = int(report["constant_input_u8"])
    source = np.full((height, width, 3), value / 255.0, dtype=np.float64)
    pipeline = ReferencePipeline(
        load_host_profile(arguments.host_profile), load_toy_profile(arguments.toy_profile)
    )
    x0 = (width - 2) // 2
    y0 = (height - 2) // 2
    reference_samples = []
    first_seed = int(report["measured_seed_start"])
    frames = int(report["measured_frames"])
    for seed in range(first_seed, first_seed + frames):
        result = pipeline.render_srgb(source, seed=seed)
        reference_samples.extend(
            result.stages["03_target_sensor_dn"][y0 : y0 + 2, x0 : x0 + 2].ravel()
        )

    reference = np.asarray(reference_samples, dtype=np.float64)
    expected_count = frames * 4
    actual_mean = float(report["sensor_sample_mean"])
    actual_variance = float(report["sensor_sample_variance"])
    reference_mean = float(np.mean(reference))
    reference_variance = float(np.var(reference))
    mean_error = abs(actual_mean - reference_mean) / max(abs(reference_mean), 1.0e-12)
    variance_error = abs(actual_variance - reference_variance) / max(
        abs(reference_variance), 1.0e-12
    )
    checks = {
        "sample_count": {
            "passed": report["sensor_sample_count"] == expected_count,
            "actual": report["sensor_sample_count"],
            "expected": expected_count,
        },
        "mean_relative_error": {
            "passed": mean_error <= limits["mean_relative_error_max"],
            "actual": mean_error,
            "expected_max": limits["mean_relative_error_max"],
        },
        "variance_relative_error": {
            "passed": variance_error <= limits["variance_relative_error_max"],
            "actual": variance_error,
            "expected_max": limits["variance_relative_error_max"],
        },
    }
    passed = all(bool(check["passed"]) for check in checks.values())
    output = {
        "passed": passed,
        "backend": report["backend"],
        "input": report["input"],
        "width": width,
        "height": height,
        "constant_input_u8": value,
        "first_seed": first_seed,
        "frames": frames,
        "native_mean": actual_mean,
        "python_mean": reference_mean,
        "native_variance": actual_variance,
        "python_variance": reference_variance,
        "checks": checks,
    }
    print(json.dumps(output, indent=2, sort_keys=True))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
