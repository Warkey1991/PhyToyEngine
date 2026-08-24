"""Collect reproducible Android/Vulkan metadata for an acceptance report."""

from __future__ import annotations

import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import subprocess
from typing import Any


def _adb(adb: str, *arguments: str) -> str:
    result = subprocess.run(
        [adb, *arguments], check=True, capture_output=True, text=True
    )
    return result.stdout.strip()


def _find(value: Any, key: str) -> Any | None:
    if isinstance(value, dict):
        if key in value:
            return value[key]
        for child in value.values():
            found = _find(child, key)
            if found is not None:
                return found
    elif isinstance(value, list):
        for child in value:
            found = _find(child, key)
            if found is not None:
                return found
    return None


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--contract", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    arguments = parser.parse_args()

    contract = json.loads(arguments.contract.read_text(encoding="utf-8"))
    vulkan = json.loads(_adb(arguments.adb, "shell", "cmd", "gpu", "vkjson"))
    report = {
        "captured_at_utc": datetime.now(timezone.utc).isoformat(),
        "manufacturer": _adb(arguments.adb, "shell", "getprop", "ro.product.manufacturer"),
        "model": _adb(arguments.adb, "shell", "getprop", "ro.product.model"),
        "android_api": int(_adb(arguments.adb, "shell", "getprop", "ro.build.version.sdk")),
        "build_fingerprint": _adb(arguments.adb, "shell", "getprop", "ro.build.fingerprint"),
        "gpu": _find(vulkan, "deviceName"),
        "vulkan_api_version": _find(vulkan, "apiVersion"),
        "vulkan_driver_version": _find(vulkan, "driverVersion"),
        "vulkan_driver": _find(vulkan, "driverName"),
        "vulkan_driver_info": _find(vulkan, "driverInfo"),
        "contract": contract["id"],
        "contract_status": contract["status"],
        "ahardware_buffer_source": "AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM synthetic fixture",
        "camera2_private_capture_tested": False,
    }
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
