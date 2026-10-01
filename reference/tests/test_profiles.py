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
    toy = load_toy_profile(toy_path)
    assert toy["capture_medium_family"] == "digital_sensor_v1"
    assert toy["id"] == "phytoy.toy.digital_01"
    assert toy["version"] == "1.0.0"
    assert toy["provenance"]["profile_type"] == "designed"
    assert toy["provenance"]["calibration_status"] == "synthetic_locked"


def test_tuned_digital_01_profile_validates(tuned_toy_path):
    toy = load_toy_profile(tuned_toy_path)
    assert toy["id"] == "phytoy.toy.digital_01"
    assert toy["version"] == "1.1.0"
    assert toy["provenance"]["calibration_status"] == "synthetic_tuned"


def test_outdoor_tuned_digital_01_profile_validates(outdoor_tuned_toy_path):
    toy = load_toy_profile(outdoor_tuned_toy_path)
    assert toy["id"] == "phytoy.toy.digital_01"
    assert toy["version"] == "1.2.0"
    assert toy["provenance"]["calibration_status"] == "synthetic_tuned"


@pytest.mark.parametrize(
    ("filename", "profile_id", "target_name"),
    [
        ("toy_phytoy_plastic_82_v1.json", "phytoy.toy.plastic_82", "PhyToy Plastic 82"),
        ("toy_phytoy_street_84_v1.json", "phytoy.toy.street_84", "PhyToy Street 84"),
        ("toy_phytoy_fisheye_05_v1.json", "phytoy.toy.fisheye_05", "PhyToy Fisheye 05"),
    ],
)
def test_original_toy_camera_profiles_validate_and_package(
    project_root,
    tmp_path,
    filename,
    profile_id,
    target_name,
):
    source = project_root / "profiles" / "authoring" / filename
    toy = load_toy_profile(source)
    assert toy["id"] == profile_id
    assert toy["version"] == "1.0.0"
    assert toy["capture_medium_family"] == "digital_sensor_v1"
    assert toy["provenance"]["profile_type"] == "designed"
    assert toy["provenance"]["target_name"] == target_name
    assert "replica" in " ".join(toy["provenance"]["notes"]).lower()

    destination = tmp_path / filename.replace(".json", ".ptp")
    compile_profile(source, destination)
    assert load_compiled_profile(destination)["id"] == profile_id


def test_profile_package_round_trip(tmp_path, toy_path):
    destination = tmp_path / "toy.ptp"
    compile_profile(toy_path, destination)
    loaded = load_compiled_profile(destination)
    assert loaded["id"] == "phytoy.toy.digital_01"


def test_measured_profile_requires_immutable_dataset(toy_path):
    profile = json.loads(toy_path.read_text())
    profile["provenance"]["profile_type"] = "measured"
    profile["provenance"]["calibration_status"] = "chart_validated"
    with pytest.raises(ProfileError, match="dataset_id"):
        load_toy_profile(profile)
    profile["provenance"]["dataset_id"] = "dataset.example.v1"
    profile["provenance"]["dataset_sha256"] = "not-a-digest"
    with pytest.raises(ProfileError, match="SHA-256"):
        load_toy_profile(profile)


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
