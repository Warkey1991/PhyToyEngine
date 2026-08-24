"""Evaluate PhyToyEngine benchmark JSON against a versioned acceptance contract."""

from __future__ import annotations

import argparse
import json
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--preview", required=True, type=Path)
    parser.add_argument("--final", required=True, type=Path)
    parser.add_argument("--long-run", required=True, type=Path)
    parser.add_argument("--cpu-conformance", required=True, type=Path)
    parser.add_argument("--vulkan-f32-conformance", required=True, type=Path)
    parser.add_argument("--vulkan-ahb-conformance", required=True, type=Path)
    parser.add_argument("--cpu-statistics", required=True, type=Path)
    parser.add_argument("--vulkan-f32-statistics", required=True, type=Path)
    parser.add_argument("--vulkan-ahb-statistics", required=True, type=Path)
    parser.add_argument("--cpu-repeatability", required=True, type=Path)
    parser.add_argument("--vulkan-f32-repeatability", required=True, type=Path)
    parser.add_argument("--vulkan-ahb-repeatability", required=True, type=Path)
    arguments = parser.parse_args()

    contract = json.loads(arguments.contract.read_text(encoding="utf-8"))
    reports = {
        "preview_720p": json.loads(arguments.preview.read_text(encoding="utf-8")),
        "final_12mp": json.loads(arguments.final.read_text(encoding="utf-8")),
        "long_run_720p": json.loads(arguments.long_run.read_text(encoding="utf-8")),
    }
    results: list[dict[str, object]] = []

    def check(name: str, passed: bool, actual: object, expected: object) -> None:
        results.append({"check": name, "passed": passed, "actual": actual, "expected": expected})

    for suite, report in reports.items():
        limits = contract[suite]
        for field in ("width", "height", "input", "backend"):
            check(f"{suite}.{field}", report[field] == limits[field], report[field], limits[field])
        check(
            f"{suite}.minimum_measured_frames",
            report["measured_frames"] >= limits["minimum_measured_frames"],
            report["measured_frames"],
            limits["minimum_measured_frames"],
        )
        for report_field, limit_field in (
            ("latency_ms_p50", "latency_ms_p50_max"),
            ("latency_ms_p95", "latency_ms_p95_max"),
            ("vulkan_allocated_bytes", "vulkan_allocated_bytes_max"),
            ("peak_rss_kib", "peak_rss_kib_max"),
        ):
            if limit_field in limits:
                check(
                    f"{suite}.{limit_field}",
                    report[report_field] <= limits[limit_field],
                    report[report_field],
                    limits[limit_field],
                )
        rss_growth = max(report["rss_kib_after"] - report["rss_kib_before"], 0)
        if "rss_growth_kib_max" in limits:
            check(
                f"{suite}.rss_growth_kib_max",
                rss_growth <= limits["rss_growth_kib_max"],
                rss_growth,
                limits["rss_growth_kib_max"],
            )
        check(
            f"{suite}.thermal_status_max",
            0 <= report["thermal_status_max"] <= limits["thermal_status_max"],
            report["thermal_status_max"],
            limits["thermal_status_max"],
        )
        check(
            f"{suite}.single_submit",
            report["queue_submission_delta"] == report["measured_frames"],
            report["queue_submission_delta"],
            report["measured_frames"],
        )
        check(
            f"{suite}.resource_reuse",
            report["resource_allocation_delta_after_warmup"] == 0,
            report["resource_allocation_delta_after_warmup"],
            0,
        )
        check(
            f"{suite}.zero_copy",
            report["zero_copy_frame_delta"] == report["measured_frames"]
            and report["ahardware_buffer_import_delta"] == 0,
            {
                "zero_copy_frames": report["zero_copy_frame_delta"],
                "imports": report["ahardware_buffer_import_delta"],
            },
            {"zero_copy_frames": report["measured_frames"], "imports_after_warmup": 0},
        )

    conformance_reports = {
        "cpu_f32": json.loads(arguments.cpu_conformance.read_text(encoding="utf-8")),
        "vulkan_f32": json.loads(arguments.vulkan_f32_conformance.read_text(encoding="utf-8")),
        "vulkan_ahb": json.loads(arguments.vulkan_ahb_conformance.read_text(encoding="utf-8")),
    }
    conformance_contract = contract["device_conformance"]
    stage_limits = contract["noiseless_stage_max_absolute_error"]
    stage_keys = {
        "01_scene_linear": "scene_linear",
        "02_target_optics": "target_optics",
        "03_target_sensor_dn": "target_sensor_dn",
        "04_target_isp_linear": "target_isp_linear",
        "05_output_srgb": "output_srgb",
    }
    check(
        "device_conformance.required_paths",
        set(conformance_reports) == set(conformance_contract["required_paths"]),
        sorted(conformance_reports),
        sorted(conformance_contract["required_paths"]),
    )
    for path, report in conformance_reports.items():
        for field in ("width", "height", "seed"):
            check(
                f"device_conformance.{path}.{field}",
                report[field] == conformance_contract[field],
                report[field],
                conformance_contract[field],
            )
        for stage, limit_key in stage_keys.items():
            actual = report["stages"][stage]["max_absolute"]
            limit = stage_limits[limit_key]
            check(
                f"device_conformance.{path}.{stage}",
                actual <= limit,
                actual,
                limit,
            )

    statistics_reports = {
        "cpu_f32": json.loads(arguments.cpu_statistics.read_text(encoding="utf-8")),
        "vulkan_f32": json.loads(arguments.vulkan_f32_statistics.read_text(encoding="utf-8")),
        "vulkan_ahb": json.loads(arguments.vulkan_ahb_statistics.read_text(encoding="utf-8")),
    }
    statistics_contract = contract["stochastic_consistency"]
    check(
        "stochastic_consistency.required_paths",
        set(statistics_reports) == set(statistics_contract["required_paths"]),
        sorted(statistics_reports),
        sorted(statistics_contract["required_paths"]),
    )
    for path, report in statistics_reports.items():
        expected_path = statistics_contract["required_paths"][path]
        for field in ("backend", "input"):
            check(
                f"stochastic_consistency.{path}.{field}",
                report[field] == expected_path[field],
                report[field],
                expected_path[field],
            )
        for field in ("width", "height", "constant_input_u8"):
            check(
                f"stochastic_consistency.{path}.{field}",
                report[field] == statistics_contract[field],
                report[field],
                statistics_contract[field],
            )
        check(
            f"stochastic_consistency.{path}.minimum_seeds",
            report["frames"] >= statistics_contract["minimum_seeds"],
            report["frames"],
            statistics_contract["minimum_seeds"],
        )
        for check_name, limit_name in (
            ("mean_relative_error", "mean_relative_error_max"),
            ("variance_relative_error", "variance_relative_error_max"),
        ):
            actual = report["checks"][check_name]["actual"]
            limit = statistics_contract[limit_name]
            check(
                f"stochastic_consistency.{path}.{limit_name}",
                actual <= limit,
                actual,
                limit,
            )

    repeatability_reports = {
        "cpu_f32": json.loads(arguments.cpu_repeatability.read_text(encoding="utf-8")),
        "vulkan_f32": json.loads(arguments.vulkan_f32_repeatability.read_text(encoding="utf-8")),
        "vulkan_ahb": json.loads(arguments.vulkan_ahb_repeatability.read_text(encoding="utf-8")),
    }
    for path, report in repeatability_reports.items():
        expected_path = statistics_contract["required_paths"][path]
        for field in ("backend", "input"):
            check(
                f"fixed_seed_repeatability.{path}.{field}",
                report[field] == expected_path[field],
                report[field],
                expected_path[field],
            )
        check(
            f"fixed_seed_repeatability.{path}.minimum_frames",
            report["measured_frames"] >= statistics_contract["repeatability_minimum_frames"],
            report["measured_frames"],
            statistics_contract["repeatability_minimum_frames"],
        )
        check(
            f"fixed_seed_repeatability.{path}.fixed_seed",
            report["fixed_seed"] is True,
            report["fixed_seed"],
            True,
        )
        check(
            f"fixed_seed_repeatability.{path}.sensor_samples",
            report["sensor_fixed_seed_repeatable"] is True,
            report["sensor_fixed_seed_repeatable"],
            True,
        )

    passed = all(bool(result["passed"]) for result in results)
    print(json.dumps({"contract": contract["id"], "passed": passed, "checks": results}, indent=2))
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
