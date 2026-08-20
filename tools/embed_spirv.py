"""Embed named SPIR-V binaries as portable uint32_t arrays."""

from __future__ import annotations

import argparse
import struct
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("inputs", nargs="+", help="NAME=path.spv")
    arguments = parser.parse_args()

    sections = [
        "#pragma once",
        "#include <cstddef>",
        "#include <cstdint>",
        "namespace phytoy::spirv {",
    ]
    for item in arguments.inputs:
        name, path_text = item.split("=", 1)
        data = Path(path_text).read_bytes()
        if len(data) % 4:
            raise ValueError(f"{path_text} is not a uint32-aligned SPIR-V module")
        words = struct.unpack(f"<{len(data) // 4}I", data)
        encoded = ",\n    ".join(f"0x{word:08x}U" for word in words)
        sections.extend(
            [
                f"inline constexpr uint32_t {name}[] = {{",
                f"    {encoded}",
                "};",
                f"inline constexpr size_t {name}_bytes = sizeof({name});",
            ]
        )
    sections.append("}  // namespace phytoy::spirv")
    arguments.output.parent.mkdir(parents=True, exist_ok=True)
    arguments.output.write_text("\n".join(sections) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
