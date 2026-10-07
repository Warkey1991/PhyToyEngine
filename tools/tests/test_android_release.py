"""Failure-focused tests for the release packaging gate, with no signing keys."""
from pathlib import Path
import importlib.util
import struct
import unittest

spec = importlib.util.spec_from_file_location("check_android_release", Path(__file__).parents[1] / "check_android_release.py")
release = importlib.util.module_from_spec(spec)
spec.loader.exec_module(release)


def field(number, value):
    def integer(value):
        output = bytearray()
        while value >= 128:
            output.append((value & 127) | 128)
            value >>= 7
        output.append(value)
        return bytes(output)
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, bytes):
        return integer(number << 3 | 2) + integer(len(value)) + value
    return integer(number << 3) + integer(value)


def elf(alignment=16384, relro_end=16384, load_flags=5, stack_flags=6):
    header = b"\x7fELF\x02\x01" + b"\0" * 10
    header += struct.pack("<HHIQQQIHHHHHH", 3, 183, 1, 0, 64, 0, 0, 64, 56, 3, 0, 0, 0)
    segments = struct.pack("<IIQQQQQQ", 1, load_flags, 0, 0, 0, 64 + 56 * 3, 64 + 56 * 3, alignment)
    segments += struct.pack("<IIQQQQQQ", 0x6474E552, 4, 0, 0, 0, 0, relro_end, 1)
    segments += struct.pack("<IIQQQQQQ", 0x6474E551, stack_flags, 0, 0, 0, 0, 0, 16)
    return header + segments


class ReleaseGateTest(unittest.TestCase):
    def test_channel_rejects_cross_store_permission(self):
        metadata = {"store_channel": "galaxy", "permissions": list(release.BILLING_PERMISSIONS.values())}
        with self.assertRaisesRegex(release.ReleaseError, "mixes"):
            release.audit_channel(metadata, "galaxy", False)

    def test_channel_rejects_wrong_or_missing_channel(self):
        metadata = {"store_channel": "play", "permissions": [release.BILLING_PERMISSIONS["play"]]}
        self.assertEqual(release.audit_channel(metadata, "play", True), "play")
        with self.assertRaisesRegex(release.ReleaseError, "does not match"):
            release.audit_channel(metadata, "galaxy", False)
        with self.assertRaisesRegex(release.ReleaseError, "missing"):
            release.audit_channel({"permissions": []}, None, True)

    def test_apk_extracts_channel_and_billing_permission(self):
        badging = "package: name='com.example.camera' versionCode='18' versionName='0.17.0'\nsdkVersion:'26'\ntargetSdkVersion:'37'\n"
        tree = ('  E: uses-permission (line=2)\n    A: android:name(0x01)="com.android.vending.BILLING"\n'
                '  E: application (line=3)\n    E: meta-data (line=4)\n'
                '      A: android:name(0x01)="phytoy.store_channel"\n      A: android:value(0x02)="play"\n')
        data = release.parse_apk_manifest(badging, tree)
        self.assertEqual(data["store_channel"], "play")
        self.assertEqual(data["permissions"], [release.BILLING_PERMISSIONS["play"]])

    def test_elf_valid_16kb(self):
        self.assertEqual(release.elf_audit(elf())["load_alignments"], [16384])

    def test_rejects_4kb_elf(self):
        with self.assertRaisesRegex(release.ReleaseError, "LOAD segment"):
            release.elf_audit(elf(alignment=4096))

    def test_allows_safe_partial_relro_page(self):
        self.assertFalse(release.elf_audit(elf(relro_end=4096))["relro_protection_overlap"])

    def test_rejects_overprotecting_writable_memory(self):
        with self.assertRaisesRegex(release.ReleaseError, "RELRO"):
            release.elf_audit(elf(relro_end=64, load_flags=6))

    def test_rejects_write_execute_segment(self):
        with self.assertRaisesRegex(release.ReleaseError, "writable and executable"):
            release.elf_audit(elf(load_flags=7))

    def test_rejects_executable_stack(self):
        with self.assertRaisesRegex(release.ReleaseError, "executable stack"):
            release.elf_audit(elf(stack_flags=7))

    def test_rejects_truncated_program_table(self):
        with self.assertRaisesRegex(release.ReleaseError, "program table"):
            release.elf_audit(elf()[:100])

    def test_aab_alignment_16kb(self):
        config = field(2, field(2, field(1, 1) + field(2, 2)))
        self.assertEqual(release.aab_alignment(config), 16384)

    def test_aab_rejects_default_4kb(self):
        config = field(2, field(2, field(1, 1)))
        with self.assertRaisesRegex(release.ReleaseError, "PAGE_ALIGNMENT"):
            release.aab_alignment(config)

    def test_rejects_truncated_proto(self):
        with self.assertRaises(release.ReleaseError):
            release.proto_fields(b"\x12\x08short")

    def test_xml_reads_compiled_false(self):
        attribute = field(1, release.ANDROID_NS) + field(2, "debuggable") + field(6, field(7, field(8, 0)))
        node = field(1, field(3, "application") + field(4, attribute))
        self.assertEqual(release.proto_xml(node)["attrs"][(release.ANDROID_NS, "debuggable")], "false")

    def test_apk_aapt_modern_security_flags(self):
        badging = "package: name='com.example.camera' versionCode='16' versionName='0.15.0-rc1'\nminSdkVersion:'26'\ntargetSdkVersion:'37'\n"
        tree = f"A: {release.ANDROID_NS}:debuggable(0x01)=true\nA: {release.ANDROID_NS}:usesCleartextTraffic(0x02)=false\n"
        tree += (f'  E: uses-feature (line=2)\n    A: {release.ANDROID_NS}:name(0x03)="android.hardware.vulkan.version"\n'
                 f'    A: {release.ANDROID_NS}:version(0x04)=4198400\n    A: {release.ANDROID_NS}:required(0x05)=true\n')
        data = release.parse_apk_manifest(badging, tree)
        self.assertTrue(data["debuggable"])
        self.assertFalse(data["cleartext"])
        self.assertEqual(data["min_sdk"], 26)
        self.assertEqual(data["features"]["android.hardware.vulkan.version"], {"required": True, "version": 4198400})

    def test_apk_aapt_legacy_security_flags(self):
        badging = "package: name='com.example.camera' versionCode='16' versionName='0.15.0-rc1'\nsdkVersion:'26'\ntargetSdkVersion:'37'\n"
        tree = "A: android:testOnly(0x01)=(type 0x12)0xffffffff\nA: android:usesCleartextTraffic(0x02)=(type 0x12)0x0\n"
        data = release.parse_apk_manifest(badging, tree)
        self.assertTrue(data["test_only"])
        self.assertFalse(data["cleartext"])


if __name__ == "__main__":
    unittest.main()
