from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path


def _write(path: Path, value: object) -> Path:
    path.write_text(json.dumps(value), encoding="utf-8")
    return path


def test_acceptance_evaluator_includes_all_device_conformance_paths(
    project_root: Path, tmp_path: Path
) -> None:
    contract_path = project_root / "acceptance/synthetic_alpha_v1.json"
    contract = json.loads(contract_path.read_text(encoding="utf-8"))

    performance_paths = {}
    for suite in ("preview_720p", "final_12mp", "long_run_720p"):
        limits = contract[suite]
        frames = limits["minimum_measured_frames"]
        report = {
            "width": limits["width"],
            "height": limits["height"],
            "input": limits["input"],
            "backend": limits["backend"],
            "measured_frames": frames,
            "latency_ms_p50": 1.0,
            "latency_ms_p95": 1.0,
            "vulkan_allocated_bytes": 1,
            "rss_kib_before": 100,
            "rss_kib_after": 100,
            "peak_rss_kib": 100,
            "thermal_status_max": 0,
            "queue_submission_delta": frames,
            "resource_allocation_delta_after_warmup": 0,
            "zero_copy_frame_delta": frames,
            "ahardware_buffer_import_delta": 0,
        }
        performance_paths[suite] = _write(tmp_path / f"{suite}.json", report)

    stage_names = {
        "01_scene_linear",
        "02_target_optics",
        "03_target_sensor_dn",
        "04_target_isp_linear",
        "05_output_srgb",
    }
    conformance_paths = {}
    for path in contract["device_conformance"]["required_paths"]:
        report = {
            "passed": True,
            "width": contract["device_conformance"]["width"],
            "height": contract["device_conformance"]["height"],
            "seed": contract["device_conformance"]["seed"],
            "stages": {stage: {"max_absolute": 0.0} for stage in stage_names},
        }
        conformance_paths[path] = _write(tmp_path / f"{path}.json", report)

    statistics_paths = {}
    repeatability_paths = {}
    statistics_contract = contract["stochastic_consistency"]
    for path, path_contract in statistics_contract["required_paths"].items():
        report = {
            "passed": True,
            "backend": path_contract["backend"],
            "input": path_contract["input"],
            "width": statistics_contract["width"],
            "height": statistics_contract["height"],
            "constant_input_u8": statistics_contract["constant_input_u8"],
            "frames": statistics_contract["minimum_seeds"],
            "checks": {
                "mean_relative_error": {"actual": 0.0},
                "variance_relative_error": {"actual": 0.0},
            },
        }
        statistics_paths[path] = _write(tmp_path / f"statistics_{path}.json", report)
        repeatability_paths[path] = _write(
            tmp_path / f"repeatability_{path}.json",
            {
                "backend": path_contract["backend"],
                "input": path_contract["input"],
                "measured_frames": statistics_contract["repeatability_minimum_frames"],
                "fixed_seed": True,
                "sensor_fixed_seed_repeatable": True,
            },
        )

    command = [
        sys.executable,
        str(project_root / "tools/evaluate_acceptance.py"),
        "--contract",
        str(contract_path),
        "--preview",
        str(performance_paths["preview_720p"]),
        "--final",
        str(performance_paths["final_12mp"]),
        "--long-run",
        str(performance_paths["long_run_720p"]),
        "--cpu-conformance",
        str(conformance_paths["cpu_f32"]),
        "--vulkan-f32-conformance",
        str(conformance_paths["vulkan_f32"]),
        "--vulkan-ahb-conformance",
        str(conformance_paths["vulkan_ahb"]),
        "--cpu-statistics",
        str(statistics_paths["cpu_f32"]),
        "--vulkan-f32-statistics",
        str(statistics_paths["vulkan_f32"]),
        "--vulkan-ahb-statistics",
        str(statistics_paths["vulkan_ahb"]),
        "--cpu-repeatability",
        str(repeatability_paths["cpu_f32"]),
        "--vulkan-f32-repeatability",
        str(repeatability_paths["vulkan_f32"]),
        "--vulkan-ahb-repeatability",
        str(repeatability_paths["vulkan_ahb"]),
    ]
    accepted = subprocess.run(command, check=False, capture_output=True, text=True)
    assert accepted.returncode == 0, accepted.stdout + accepted.stderr
    assert json.loads(accepted.stdout)["passed"] is True

    failed_report = json.loads(conformance_paths["vulkan_ahb"].read_text(encoding="utf-8"))
    failed_report["stages"]["01_scene_linear"]["max_absolute"] = 1.0
    _write(conformance_paths["vulkan_ahb"], failed_report)
    rejected = subprocess.run(command, check=False, capture_output=True, text=True)
    assert rejected.returncode == 1
    assert json.loads(rejected.stdout)["passed"] is False


def test_camera2_private_log_evaluator_accepts_only_the_required_runtime_contract(
    project_root: Path, tmp_path: Path
) -> None:
    log_path = tmp_path / "camera2_log.txt"
    log_path.write_text(
        "Camera2 PRIVATE input session ready: 1280x720 PhyToyEngine\n"
        "Camera2 PRIVATE rendered=30 received=60 dropped=0 p50_ms=21.5 p95_ms=39.0 "
        "errors=0 submits=30 imports=4 zero_copy=30 "
        "aimage_format=34 buffer_format=17 usage=256 "
        "throttled=30 target_fps=15 presented=30 "
        "swapchain_recreates=0 output=720x1560\n",
        encoding="utf-8",
    )
    command = [
        sys.executable,
        str(project_root / "tools/evaluate_android_camera2_log.py"),
        "--contract",
        str(project_root / "acceptance/android_camera2_product_alpha_v1.json"),
        "--log",
        str(log_path),
    ]

    accepted = subprocess.run(command, check=False, capture_output=True, text=True)
    assert accepted.returncode == 0, accepted.stdout + accepted.stderr
    accepted_report = json.loads(accepted.stdout)
    assert accepted_report["passed"] is True
    assert accepted_report["latest"]["target_fps"] == 15
    assert next(
        item for item in accepted_report["checks"]
        if item["check"] == "frame_accounting"
    )["passed"] is True

    log_path.write_text(
        log_path.read_text(encoding="utf-8").replace("zero_copy=30", "zero_copy=29"),
        encoding="utf-8",
    )
    rejected = subprocess.run(command, check=False, capture_output=True, text=True)
    assert rejected.returncode == 1
    report = json.loads(rejected.stdout)
    assert report["passed"] is False
    assert next(
        item for item in report["checks"] if item["check"] == "zero_copy_every_frame"
    )["passed"] is False
