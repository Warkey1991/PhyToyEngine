import json

import pytest

from phytoy_ref.profile_package import compile_profile, load_compiled_profile
from phytoy_ref.profiles import ProfileError, load_host_profile, load_toy_profile


def test_sample_profiles_validate(host_srgb_path, host_raw_path, host_device_raw_path, toy_path):
    assert load_host_profile(host_srgb_path)["input"]["mode"] == "srgb"
    assert load_host_profile(host_raw_path)["input"]["mode"] == "raw"
    device = load_host_profile(host_device_raw_path)
    assert device["raw"]["cfa"] == "GBRG"
    assert device["provenance"]["device_model"] == "Samsung SM-S9210"
    assert load_toy_profile(toy_path)["capture_medium_family"] == "digital_sensor_v1"


def test_profile_package_round_trip(tmp_path, toy_path):
    destination = tmp_path / "toy.ptp"
    compile_profile(toy_path, destination)
    loaded = load_compiled_profile(destination)
    assert loaded["id"] == "phytoy.toy.fixed_focus_alpha"


def test_profile_package_detects_corruption(tmp_path, toy_path):
    destination = tmp_path / "toy.ptp"
    compile_profile(toy_path, destination)
    data = bytearray(destination.read_bytes())
    data[-1] ^= 0x01
    destination.write_bytes(data)
    with pytest.raises(ValueError, match="checksum"):
        load_compiled_profile(destination)


def test_tone_curve_must_be_monotonic(toy_path):
    profile = json.loads(toy_path.read_text())
    profile["isp"]["tone_curve"][2][1] = -1.0
    with pytest.raises(ProfileError, match="monotonic"):
        load_toy_profile(profile)


def test_unknown_profile_field_is_rejected(toy_path):
    profile = json.loads(toy_path.read_text())
    profile["sensor"]["typo_read_niose_e"] = 1.0
    with pytest.raises(ProfileError, match="unknown profile field"):
        load_toy_profile(profile)


def test_negative_noise_is_rejected(toy_path):
    profile = json.loads(toy_path.read_text())
    profile["sensor"]["read_noise_e"] = -0.1
    with pytest.raises(ProfileError, match="nonnegative"):
        load_toy_profile(profile)
