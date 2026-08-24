"""Evaluate the Android sample's real Camera2 PRIVATE Vulkan progress log."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import re


PROGRESS = re.compile(
    r"Camera2 PRIVATE rendered=(?P<rendered>\d+) "
    r"received=(?P<received>\d+) dropped=(?P<dropped>\d+) "
    r"p50_ms=(?P<p50>[0-9.]+) p95_ms=(?P<p95>[0-9.]+) "
    r"errors=(?P<errors>\d+) submits=(?P<submits>\d+) "
    r"imports=(?P<imports>\d+) zero_copy=(?P<zero_copy>\d+) "
    r"aimage_format=(?P<aimage_format>\d+) "
    r"buffer_format=(?P<buffer_format>\d+) usage=(?P<usage>\d+)"
    r"(?: throttled=(?P<throttled>\d+) target_fps=(?P<target_fps>\d+))?"
    r"(?: presented=(?P<presented>\d+) "
    r"swapchain_recreates=(?P<swapchain_recreates>\d+) "
    r"output=(?P<output_width>\d+)x(?P<output_height>\d+))?"
)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--log", required=True, type=Path)
    arguments = parser.parse_args()

    contract = json.loads(arguments.contract.read_text(encoding="utf-8"))
    log = arguments.log.read_text(encoding="utf-8", errors="replace")
    matches = list(PROGRESS.finditer(log))
    latest = matches[-1].groupdict() if matches else {}
    actual = {
        key: float(value) if key in {"p50", "p95"} else int(value)
        for key, value in latest.items()
        if value is not None
    }
    limits = contract["smoke"]
    input_contract = contract["input"]

    def check(name: str, passed: bool, observed: object, expected: object) -> dict[str, object]:
        return {"check": name, "passed": passed, "actual": observed, "expected": expected}

    ready = "Camera2 PRIVATE input session ready" in log
    checks = [check("reader_ready", ready, ready, True)]
    if matches:
        rendered = int(actual["rendered"])
        checks.extend(
            [
                check(
                    "minimum_rendered_frames",
                    rendered >= limits["minimum_rendered_frames"],
                    rendered,
                    limits["minimum_rendered_frames"],
                ),
                check(
                    "maximum_error_frames",
                    actual["errors"] <= limits["maximum_error_frames"],
                    actual["errors"],
                    limits["maximum_error_frames"],
                ),
                check(
                    "latency_ms_p95_max",
                    actual["p95"] <= limits["latency_ms_p95_max"],
                    actual["p95"],
                    limits["latency_ms_p95_max"],
                ),
                check(
                    "one_submit_per_frame",
                    actual["submits"] == rendered * limits["queue_submissions_per_frame"],
                    actual["submits"],
                    rendered,
                ),
                check(
                    "zero_copy_every_frame",
                    actual["zero_copy"] == rendered * limits["zero_copy_frames_per_frame"],
                    actual["zero_copy"],
                    rendered,
                ),
                check(
                    "buffer_slot_cache",
                    0 < actual["imports"] <= limits["maximum_imported_buffer_slots"],
                    actual["imports"],
                    f"1..{limits['maximum_imported_buffer_slots']}",
                ),
                check(
                    "private_aimage_format",
                    actual["aimage_format"] == input_contract["expected_aimage_format"],
                    actual["aimage_format"],
                    input_contract["expected_aimage_format"],
                ),
                check(
                    "hardware_buffer_format_reported",
                    actual["buffer_format"] > 0,
                    actual["buffer_format"],
                    "vendor-defined nonzero format",
                ),
                check(
                    "gpu_sampled_usage",
                    actual["usage"] & input_contract["required_ahardwarebuffer_usage_mask"]
                    == input_contract["required_ahardwarebuffer_usage_mask"],
                    actual["usage"],
                    input_contract["required_ahardwarebuffer_usage_mask"],
                ),
            ]
        )
        if "target_fps" in actual:
            maximum_target = contract["thermal_adaptation"]["normal_processing_fps"]
            accounted = (
                actual["rendered"] + actual["dropped"] + actual["throttled"]
            )
            checks.extend(
                [
                    check(
                        "processing_frame_rate_limit",
                        0 < actual["target_fps"] <= maximum_target,
                        actual["target_fps"],
                        f"1..{maximum_target}",
                    ),
                    check(
                        "frame_accounting",
                        0 <= actual["received"] - accounted <= 2,
                        {
                            "received": actual["received"],
                            "rendered": actual["rendered"],
                            "dropped": actual["dropped"],
                            "thermal_skipped": actual["throttled"],
                        },
                        "at most two pending/in-flight frames",
                    ),
                ]
            )
        if "presented" in actual:
            presentation = contract["presentation"]
            checks.extend(
                [
                    check(
                        "processed_surface_every_frame",
                        actual["presented"] == rendered,
                        actual["presented"],
                        rendered,
                    ),
                    check(
                        "swapchain_recreate_limit",
                        actual["swapchain_recreates"]
                        <= presentation["maximum_swapchain_recreates"],
                        actual["swapchain_recreates"],
                        presentation["maximum_swapchain_recreates"],
                    ),
                    check(
                        "preview_output_width",
                        actual["output_width"] == presentation["output_width"],
                        actual["output_width"],
                        presentation["output_width"],
                    ),
                    check(
                        "preview_output_height",
                        actual["output_height"] >= presentation["minimum_output_height"],
                        actual["output_height"],
                        f">={presentation['minimum_output_height']}",
                    ),
                ]
            )
        else:
            checks.append(
                check("processed_surface_progress", False, None, "presentation metrics")
            )
    else:
        checks.append(check("progress_record", False, None, "Camera2 PRIVATE progress line"))

    fatal = "FATAL EXCEPTION" in log or "Fatal signal" in log
    checks.append(check("no_fatal_process_error", not fatal, fatal, False))
    passed = all(bool(item["passed"]) for item in checks)
    print(
        json.dumps(
            {
                "contract": contract["id"],
                "passed": passed,
                "latest": actual or None,
                "checks": checks,
            },
            indent=2,
        )
    )
    raise SystemExit(0 if passed else 1)


if __name__ == "__main__":
    main()
