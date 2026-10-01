#!/usr/bin/env python3
"""Audit built release APK/AAB files; never sign, upload, or install them.

Uses standard-library ELF/protobuf readers and Android SDK build tools. The AAB
field numbers follow google/bundletool config.proto and AOSP aapt2 Resources.proto.
This packaging gate does not replace device acceptance or Play policy review.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import sys
import zipfile

PAGE_SIZE = 16384
ANDROID_NS = "http://schemas.android.com/apk/res/android"
REQUIRED_LIBRARIES = {"libphytoy_core.so", "libphytoy_android.so", "libc++_shared.so"}


class ReleaseError(ValueError):
    pass


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ReleaseError(message)


def run(command: list[str]) -> str:
    result = subprocess.run(command, text=True, capture_output=True, check=False)
    require(result.returncode == 0, f"{Path(command[0]).name} failed: {(result.stderr or result.stdout).strip()}")
    return result.stdout


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    for shift in range(0, 70, 7):
        require(offset < len(data), "Truncated protobuf varint")
        byte = data[offset]
        offset += 1
        value |= (byte & 127) << shift
        if byte < 128:
            return value, offset
    raise ReleaseError("Invalid protobuf varint")


def proto_fields(data: bytes) -> dict[int, list[int | bytes]]:
    fields: dict[int, list[int | bytes]] = {}
    offset = 0
    while offset < len(data):
        key, offset = varint(data, offset)
        number, wire = key >> 3, key & 7
        require(number > 0, "Invalid protobuf field number")
        if wire == 0:
            value, offset = varint(data, offset)
        elif wire == 2:
            size, offset = varint(data, offset)
            require(offset + size <= len(data), "Truncated protobuf field")
            value = data[offset:offset + size]
            offset += size
        elif wire in (1, 5):
            size = 8 if wire == 1 else 4
            require(offset + size <= len(data), "Truncated fixed protobuf field")
            value = data[offset:offset + size]
            offset += size
        else:
            raise ReleaseError(f"Unsupported protobuf wire type {wire}")
        fields.setdefault(number, []).append(value)
    return fields


def one(fields: dict, number: int, default=None):
    values = fields.get(number, [])
    require(len(values) <= 1, f"Duplicate singular protobuf field {number}")
    return values[0] if values else default


def proto_xml(data: bytes, depth: int = 0) -> dict:
    require(depth < 64, "Manifest nesting exceeds audit limit")
    node = proto_fields(data)
    element_data = one(node, 1)
    if element_data is None:
        return {}
    element = proto_fields(element_data)
    attrs = {}
    for raw in element.get(4, []):
        attr = proto_fields(raw)
        namespace = one(attr, 1, b"").decode()
        name = one(attr, 2, b"").decode()
        value = one(attr, 3, b"").decode()
        if not value and 6 in attr:
            item = proto_fields(one(attr, 6))
            if 7 in item:
                primitive = proto_fields(one(item, 7))
                if 8 in primitive:
                    value = "true" if one(primitive, 8) else "false"
                elif 6 in primitive or 7 in primitive:
                    value = str(one(primitive, 6, one(primitive, 7)))
            elif 2 in item:
                value = one(proto_fields(one(item, 2)), 1, b"").decode()
        attrs[(namespace, name)] = value
    return {"name": one(element, 3, b"").decode(), "attrs": attrs,
            "children": [proto_xml(child, depth + 1) for child in element.get(5, [])]}


def aab_manifest(data: bytes) -> dict:
    root = proto_xml(data)
    require(root.get("name") == "manifest", "AAB manifest has no manifest root")
    children = root["children"]
    app = next((x for x in children if x.get("name") == "application"), {})
    sdk = next((x for x in children if x.get("name") == "uses-sdk"), {})
    require(app and sdk, "AAB manifest is missing application or uses-sdk")
    attrs, app_attrs, sdk_attrs = root["attrs"], app["attrs"], sdk["attrs"]
    features = {}
    for child in children:
        if child.get("name") == "uses-feature":
            feature_attrs = child["attrs"]
            name = feature_attrs.get((ANDROID_NS, "name"))
            if name:
                features[name] = {"required": feature_attrs.get((ANDROID_NS, "required"), "true") == "true",
                                  "version": int(feature_attrs.get((ANDROID_NS, "version"), "0"), 0)}
    return {"application_id": attrs.get(("", "package")),
            "version_code": int(attrs[(ANDROID_NS, "versionCode")]),
            "version_name": attrs[(ANDROID_NS, "versionName")],
            "min_sdk": int(sdk_attrs[(ANDROID_NS, "minSdkVersion")]),
            "target_sdk": int(sdk_attrs[(ANDROID_NS, "targetSdkVersion")]),
            "debuggable": app_attrs.get((ANDROID_NS, "debuggable"), "false") == "true",
            "test_only": app_attrs.get((ANDROID_NS, "testOnly"), "false") == "true",
            "cleartext": app_attrs.get((ANDROID_NS, "usesCleartextTraffic"), "true") == "true",
            "features": features}


def aab_alignment(data: bytes) -> int:
    config = proto_fields(data)
    optimizations = proto_fields(one(config, 2, b""))
    native = proto_fields(one(optimizations, 2, b""))
    require(one(native, 1, 0) == 1, "AAB must request uncompressed native libraries")
    alignment = one(native, 2, 0)
    require(alignment in (2, 3), "AAB does not request PAGE_ALIGNMENT_16K or better")
    return 16384 if alignment == 2 else 65536


def elf_audit(data: bytes) -> dict:
    require(len(data) >= 64 and data[:4] == b"\x7fELF", "Native library is not an ELF file")
    require(data[4:6] == b"\x02\x01", "Release native libraries must be little-endian ELF64")
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    require(header[1] == 183, "Release native library is not AArch64")
    phoff, phsize, phcount = header[4], header[8], header[9]
    require(phsize >= 56 and phoff + phsize * phcount <= len(data), "Invalid ELF program table")
    loads, relro, writable_loads = [], [], []
    for index in range(phcount):
        kind, flags, offset, address, _, file_size, memory_size, alignment = struct.unpack_from(
            "<IIQQQQQQ", data, phoff + index * phsize)
        if kind == 1:
            require(alignment >= PAGE_SIZE, "ELF LOAD segment is not 16 KB aligned")
            require((address - offset) % PAGE_SIZE == 0, "ELF LOAD offset/address alignment differs")
            require(offset + file_size <= len(data), "ELF LOAD segment exceeds file size")
            require(flags & 3 != 3, "ELF LOAD segment is both writable and executable")
            loads.append(alignment)
            if flags & 2:
                writable_loads.append((address, address + memory_size))
        elif kind == 0x6474E552:
            relro.append((address, address + memory_size))
        elif kind == 0x6474E551:
            require(flags & 1 == 0, "ELF requests an executable stack")
    require(loads, "ELF has no LOAD segments")
    require(relro, "ELF has no GNU_RELRO protection")
    # Bionic rounds RELRO protection to whole runtime pages. NDK libc++ may end
    # RELRO inside a 16 KB page while leaving a safe gap before the next RW LOAD.
    # Reject actual overlap with writable memory, not a harmless partial end page.
    # See AOSP bionic/linker/linker_phdr.cpp, _phdr_table_set_gnu_relro_prot.
    for start, end in relro:
        protected_start = start // PAGE_SIZE * PAGE_SIZE
        protected_end = (end + PAGE_SIZE - 1) // PAGE_SIZE * PAGE_SIZE
        for load_start, load_end in writable_loads:
            overlap_start, overlap_end = max(load_start, protected_start), min(load_end, protected_end)
            require(overlap_start >= overlap_end or (overlap_start >= start and overlap_end <= end),
                    "ELF 16 KB RELRO protection overlaps non-RELRO writable memory")
    return {"load_alignments": loads, "relro_ranges": relro,
            "relro_protection_overlap": False, "sha256": sha256(data)}


def zip_data_offset(path: Path, info: zipfile.ZipInfo) -> int:
    with path.open("rb") as source:
        source.seek(info.header_offset)
        header = source.read(30)
    require(len(header) == 30 and header[:4] == b"PK\x03\x04", "Invalid ZIP local header")
    name_size, extra_size = struct.unpack_from("<HH", header, 26)
    return info.header_offset + 30 + name_size + extra_size


def build_tool(sdk: Path, name: str) -> str:
    versions = sorted((sdk / "build-tools").glob("*"),
                      key=lambda p: tuple(int(x) for x in re.findall(r"\d+", p.name)), reverse=True)
    for version in versions:
        tool = version / (name + (".bat" if os.name == "nt" and name == "apksigner" else ""))
        if tool.is_file():
            return str(tool)
    raise ReleaseError(f"Android SDK build tool {name} is missing")


def parse_apk_manifest(badging: str, tree: str) -> dict:
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    require(package is not None, "APK package metadata could not be decoded")
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    target = re.search(r"^targetSdkVersion:'(\d+)'", badging, re.M)
    require(sdk is not None and target is not None, "APK SDK metadata is missing")
    def boolean(name: str, default: bool) -> bool:
        match = re.search(rf"A: (?:android|{re.escape(ANDROID_NS)}):{name}\([^)]*\)=([^\n]+)", tree)
        if not match:
            return default
        value = match[1].strip()
        if value in ("true", "false"):
            return value == "true"
        typed = re.fullmatch(r"\(type 0x12\)(0x[0-9a-f]+)", value, re.I)
        require(typed is not None, f"Unknown AAPT boolean representation for {name}")
        return bool(int(typed[1], 16))
    features = {}
    for block in re.finditer(r"(?m)^([ \t]*)E: uses-feature[^\n]*\n((?:\1  +[^\n]*\n?)*)", tree):
        name = re.search(r':name\([^)]*\)="([^"]+)"', block[2])
        if name:
            required = re.search(r':required\([^)]*\)=([^\n]+)', block[2])
            version = re.search(r':version\([^)]*\)=([^\n]+)', block[2])
            required_value = required[1].strip() if required else "true"
            required_value = required_value.replace("(type 0x12)", "")
            version_value = version[1].strip() if version else "0"
            version_value = re.sub(r"\(type 0x[0-9a-f]+\)", "", version_value)
            features[name[1]] = {"required": required_value not in ("false", "0x0", "0"),
                                 "version": int(version_value, 0)}
    return {"application_id": package[1], "version_code": int(package[2]), "version_name": package[3],
            "min_sdk": int(sdk[1]), "target_sdk": int(target[1]),
            "debuggable": boolean("debuggable", False), "test_only": boolean("testOnly", False),
            "cleartext": boolean("usesCleartextTraffic", True), "features": features}


def apk_manifest(path: Path, aapt2: str) -> dict:
    badging = run([aapt2, "dump", "badging", str(path)])
    tree = run([aapt2, "dump", "xmltree", str(path), "--file", "AndroidManifest.xml"])
    return parse_apk_manifest(badging, tree)


def audit_archive(path: Path, kind: str, asset_dir: Path) -> dict:
    require(path.is_file(), f"Missing {kind.upper()}: {path}")
    prefix = "base/" if kind == "aab" else ""
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        require(len(names) == len(set(names)), "Duplicate ZIP entry names")
        libs = [x for x in names if x.startswith(prefix + "lib/") and x.endswith(".so")]
        require({x.split("/")[-2] for x in libs} == {"arm64-v8a"}, "Release must contain the validated arm64-v8a ABI only")
        require({Path(x).name for x in libs} == REQUIRED_LIBRARIES, "Unexpected or missing native runtime libraries")
        native = {}
        for name in libs:
            native[Path(name).name] = elf_audit(archive.read(name))
            if kind == "apk":
                info = archive.getinfo(name)
                require(info.compress_type == zipfile.ZIP_STORED, f"APK native library is compressed: {name}")
                offset = zip_data_offset(path, info)
                require(offset % PAGE_SIZE == 0, f"APK native library ZIP offset is not 16 KB aligned: {name}")
                native[Path(name).name]["zip_offset"] = offset
        profiles = {}
        for source in sorted(asset_dir.glob("*.ptp")):
            name = prefix + "assets/phytoy/" + source.name
            require(name in names, f"Missing packaged profile {source.name}")
            digest = sha256(archive.read(name))
            require(digest == sha256(source.read_bytes()), f"Packaged profile differs from source asset: {source.name}")
            profiles[source.name] = digest
        require(profiles, "No camera profile assets found")
        result = {"path": str(path.resolve()), "sha256": sha256(path.read_bytes()),
                  "bytes": path.stat().st_size, "native_libraries": native, "profiles": profiles}
        has_jar_signature = any(re.match(r"META-INF/[^/]+\.(RSA|DSA|EC)$", name, re.I) for name in names)
        if kind == "apk":
            with path.open("rb") as source:
                source.seek(max(0, archive.start_dir - 16))
                has_signing_block = source.read(16) == b"APK Sig Block 42"
            result["has_signature"] = has_jar_signature or has_signing_block
        if kind == "aab":
            result["manifest"] = aab_manifest(archive.read("base/manifest/AndroidManifest.xml"))
            result["generated_apk_page_alignment"] = aab_alignment(archive.read("BundleConfig.pb"))
            result["has_jar_signature"] = has_jar_signature
    return result


def audit(args: argparse.Namespace) -> dict:
    root = Path(__file__).resolve().parents[1]
    require(args.minimum_target_sdk >= 36, "Minimum target SDK cannot weaken the current API 36 release policy")
    sdk = args.sdk or os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not sdk:
        local = root / "android/local.properties"
        if local.is_file():
            match = re.search(r"^sdk.dir=(.+)$", local.read_text(), re.M)
            sdk = match[1].replace("\\:", ":").replace("\\\\", "\\") if match else None
    require(bool(sdk), "Set ANDROID_SDK_ROOT or pass --sdk")
    sdk = Path(sdk)
    apk = audit_archive(args.apk, "apk", root / "android/sdk/src/main/assets/phytoy")
    aab = audit_archive(args.aab, "aab", root / "android/sdk/src/main/assets/phytoy")
    apk["manifest"] = apk_manifest(args.apk, build_tool(sdk, "aapt2"))
    require(apk["manifest"] == aab["manifest"], "APK and AAB manifest metadata differ")
    for name in REQUIRED_LIBRARIES:
        require(apk["native_libraries"][name]["sha256"] == aab["native_libraries"][name]["sha256"],
                f"APK and AAB native library differs: {name}")
    metadata = apk["manifest"]
    require(metadata["target_sdk"] >= args.minimum_target_sdk, "Target SDK is below the current release policy")
    require(metadata["min_sdk"] == 26, "Unexpected minimum SDK")
    require(metadata["version_code"] > 0 and metadata["version_name"], "Invalid release version")
    require(not metadata["debuggable"] and not metadata["test_only"], "Debuggable/test-only app cannot pass release audit")
    require(not metadata["cleartext"], "Release manifest permits cleartext traffic")
    require(metadata["features"].get("android.hardware.vulkan.version") == {"required": True, "version": 4198400},
            "Release must require Vulkan API 1.1 in its manifest (feature level is a separate declaration)")
    require(metadata["features"].get("android.hardware.camera.any", {}).get("required") is True,
            "Release must require a camera")
    if args.expected_application_id:
        require(metadata["application_id"] == args.expected_application_id, "Application ID is not the expected permanent ID")
    if args.store_ready:
        require(args.expected_application_id and args.expected_cert_sha256,
                "Store packaging audit requires --expected-application-id and --expected-cert-sha256")
        require(not re.search(r"\.(sample|debug|benchmark)(\.|$)", metadata["application_id"], re.I),
                "Development application ID cannot pass the store packaging gate")
    run([build_tool(sdk, "zipalign"), "-c", "-P", "16", "4", str(args.apk)])
    signed = subprocess.run([build_tool(sdk, "apksigner"), "verify", "--print-certs", str(args.apk)],
                            text=True, capture_output=True, check=False)
    apk["signed"] = signed.returncode == 0
    require(apk["signed"] == apk["has_signature"], "APK signature is present but invalid, or signing state is inconsistent")
    require(apk["signed"] == aab["has_jar_signature"], "APK and AAB signing states differ")
    if apk["signed"]:
        output = signed.stdout
        require("CN=Android Debug" not in output, "Release APK uses an Android debug certificate")
        require(len(re.findall(r"certificate SHA-256 digest:", output)) == 1, "Expected exactly one APK signing certificate")
        digest = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-f]+)", output, re.I)
        require(digest is not None, "APK signing certificate digest is missing")
        apk["certificate_sha256"] = digest[1].lower()
        jarsigner = shutil.which("jarsigner")
        keytool = shutil.which("keytool")
        require(jarsigner is not None and keytool is not None, "Signed AAB verification requires JDK jarsigner and keytool")
        jar_output = run([jarsigner, "-J-Duser.language=en", "-verify", str(args.aab)])
        require("jar verified." in jar_output and "unsigned entries" not in jar_output.lower(),
                "AAB JAR signature verification failed or includes unsigned entries")
        cert_output = run([keytool, "-J-Duser.language=en", "-printcert", "-jarfile", str(args.aab)])
        cert = re.search(r"SHA256:\s*([0-9A-F:]+)", cert_output)
        require(cert is not None, "AAB signing certificate digest is missing")
        aab["certificate_sha256"] = cert[1].replace(":", "").lower()
        require(aab["certificate_sha256"] == apk["certificate_sha256"], "APK and AAB signing certificates differ")
        if args.expected_cert_sha256:
            require(apk["certificate_sha256"] == args.expected_cert_sha256.replace(":", "").lower(),
                    "Signing certificate is not the expected release certificate")
    else:
        require(args.allow_unsigned and not args.store_ready, "Unsigned candidates require --allow-unsigned and cannot be store-ready")
        require(not args.expected_cert_sha256, "Expected signing certificate supplied for an unsigned candidate")
    return {"status": "passed", "scope": "store_packaging" if args.store_ready else "release_candidate_packaging",
            "signed": apk["signed"], "store_ready": args.store_ready,
            "minimum_target_sdk": args.minimum_target_sdk, "apk": apk, "aab": aab,
            "device_acceptance": "separate_required_gate"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--aab", type=Path, required=True)
    parser.add_argument("--sdk", type=Path)
    parser.add_argument("--allow-unsigned", action="store_true")
    parser.add_argument("--store-ready", action="store_true")
    parser.add_argument("--expected-application-id")
    parser.add_argument("--expected-cert-sha256")
    parser.add_argument("--minimum-target-sdk", type=int, default=36)
    parser.add_argument("--output", type=Path, help="Write a public artifact manifest containing hashes, not secrets")
    args = parser.parse_args()
    try:
        result = audit(args)
    except (ReleaseError, OSError, ValueError, KeyError, struct.error, zipfile.BadZipFile) as error:
        result = {"status": "failed", "error": str(error)}
    encoded = json.dumps(result, indent=2) + "\n"
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(encoded)
    print(encoded, end="")
    return 0 if result["status"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
