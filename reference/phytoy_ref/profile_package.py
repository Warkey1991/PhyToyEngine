"""Compile validated authoring JSON into an integrity-checked Alpha profile package."""

from __future__ import annotations

import argparse
import hashlib
import json
import struct
from pathlib import Path

from .profiles import load_host_profile, load_toy_profile


MAGIC = b"PTEPROF1"
HEADER = struct.Struct("<8sIII32s")
KIND = {"host": 1, "toy": 2}


def compile_profile(source: str | Path, destination: str | Path) -> Path:
    source_path = Path(source)
    raw = json.loads(source_path.read_text(encoding="utf-8"))
    if raw.get("kind") == "host":
        profile = load_host_profile(raw)
    elif raw.get("kind") == "toy":
        profile = load_toy_profile(raw)
    else:
        raise ValueError("profile kind must be host or toy")
    payload = json.dumps(
        profile, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")
    digest = hashlib.sha256(payload).digest()
    header = HEADER.pack(
        MAGIC, int(profile["schema_version"]), KIND[profile["kind"]], len(payload), digest
    )
    destination_path = Path(destination)
    destination_path.parent.mkdir(parents=True, exist_ok=True)
    destination_path.write_bytes(header + payload)
    return destination_path


def load_compiled_profile(path: str | Path) -> dict:
    data = Path(path).read_bytes()
    if len(data) < HEADER.size:
        raise ValueError("profile package is truncated")
    magic, schema_version, kind, payload_size, expected_digest = HEADER.unpack_from(data)
    if magic != MAGIC or schema_version != 1 or kind not in KIND.values():
        raise ValueError("invalid profile package header")
    payload = data[HEADER.size :]
    if len(payload) != payload_size:
        raise ValueError("profile package payload size mismatch")
    if hashlib.sha256(payload).digest() != expected_digest:
        raise ValueError("profile package checksum mismatch")
    profile = json.loads(payload.decode("utf-8"))
    return load_host_profile(profile) if kind == KIND["host"] else load_toy_profile(profile)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    arguments = parser.parse_args()
    output = compile_profile(arguments.source, arguments.destination)
    print(output)


if __name__ == "__main__":
    main()

