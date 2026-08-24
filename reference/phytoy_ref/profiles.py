"""Authoring profile loading and strict semantic validation."""

from __future__ import annotations

import json
import re
from pathlib import Path
from typing import Any

import numpy as np


class ProfileError(ValueError):
    pass


def _read_json(path_or_profile: str | Path | dict[str, Any]) -> dict[str, Any]:
    if isinstance(path_or_profile, dict):
        return path_or_profile
    with Path(path_or_profile).open("r", encoding="utf-8") as stream:
        return json.load(stream)


def _require(profile: dict, path: str) -> Any:
    value: Any = profile
    for component in path.split("."):
        if not isinstance(value, dict) or component not in value:
            raise ProfileError(f"missing required profile field: {path}")
        value = value[component]
    return value


def _finite_array(profile: dict, path: str, shape: tuple[int, ...] | None = None) -> np.ndarray:
    try:
        array = np.asarray(_require(profile, path), dtype=np.float64)
    except (TypeError, ValueError) as error:
        raise ProfileError(f"profile field is not a rectangular numeric array: {path}") from error
    if not np.all(np.isfinite(array)):
        raise ProfileError(f"profile field contains NaN/Inf: {path}")
    if shape is not None and array.shape != shape:
        raise ProfileError(f"profile field {path} must have shape {shape}, got {array.shape}")
    return array


def _finite_scalar(profile: dict, path: str) -> float:
    value = _require(profile, path)
    if isinstance(value, bool):
        raise ProfileError(f"profile field must be numeric: {path}")
    try:
        result = float(value)
    except (TypeError, ValueError) as error:
        raise ProfileError(f"profile field must be numeric: {path}") from error
    if not np.isfinite(result):
        raise ProfileError(f"profile field contains NaN/Inf: {path}")
    return result


def _reject_unknown(mapping: dict, allowed: set[str], path: str) -> None:
    if not isinstance(mapping, dict):
        raise ProfileError(f"profile field must be an object: {path}")
    unknown = sorted(set(mapping) - allowed)
    if unknown:
        raise ProfileError(f"unknown profile field(s) in {path}: {', '.join(unknown)}")


def _validate_common(profile: dict, expected_kind: str) -> None:
    _reject_unknown(
        profile,
        {
            "schema_version", "kind", "id", "version", "input", "normalization",
            "raw", "provenance",
        }
        if expected_kind == "host"
        else {
            "schema_version", "kind", "id", "version", "capture_medium_family",
            "optics", "sensor", "isp", "provenance",
        },
        "root",
    )
    if profile.get("schema_version") != 1:
        raise ProfileError("schema_version must be 1")
    if profile.get("kind") != expected_kind:
        raise ProfileError(f"profile kind must be {expected_kind}")
    if not isinstance(profile.get("id"), str) or not profile["id"]:
        raise ProfileError("profile id must be a non-empty string")
    if not isinstance(profile.get("version"), str) or not profile["version"]:
        raise ProfileError("profile version must be a non-empty string")
    if re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", profile["version"]) is None:
        raise ProfileError("profile version must use MAJOR.MINOR.PATCH")


def _validate_provenance(profile: dict, expected_kind: str) -> None:
    if "provenance" not in profile:
        return
    provenance = profile["provenance"]
    common = {"source", "calibration_status", "notes"}
    allowed = common | ({"device_model", "camera_id", "capture_date", "reference_illuminant"}
                        if expected_kind == "host" else {
                            "profile_type", "target_name", "created_utc", "dataset_id",
                            "dataset_sha256", "license",
                        })
    _reject_unknown(provenance, allowed, "provenance")
    if not all(
        isinstance(value, str)
        for key, value in provenance.items()
        if key != "notes"
    ):
        raise ProfileError("provenance scalar fields must be strings")
    if "notes" in provenance and (
        not isinstance(provenance["notes"], list)
        or not all(isinstance(note, str) for note in provenance["notes"])
    ):
        raise ProfileError("provenance.notes must be an array of strings")
    if expected_kind != "toy":
        return
    profile_type = provenance.get("profile_type")
    if profile_type not in {"designed", "measured"}:
        raise ProfileError("toy provenance.profile_type must be designed or measured")
    for field in ("target_name", "source", "calibration_status"):
        if not provenance.get(field):
            raise ProfileError(f"toy provenance.{field} must be a non-empty string")
    if profile_type == "measured":
        if not provenance.get("dataset_id"):
            raise ProfileError("measured toy profiles require provenance.dataset_id")
        digest = provenance.get("dataset_sha256", "")
        if re.fullmatch(r"[0-9a-fA-F]{64}", digest) is None:
            raise ProfileError("measured toy profiles require a SHA-256 dataset digest")


def validate_host_profile(profile: dict) -> dict:
    _validate_common(profile, "host")
    _validate_provenance(profile, "host")
    _reject_unknown(_require(profile, "input"), {"mode", "color_space"}, "input")
    _reject_unknown(
        _require(profile, "normalization"), {"rgb_residual_matrix"}, "normalization"
    )
    mode = _require(profile, "input.mode")
    if mode not in {"srgb", "yuv420", "raw"}:
        raise ProfileError(f"unsupported host input mode: {mode}")
    residual = _finite_array(profile, "normalization.rgb_residual_matrix", (3, 3))
    if abs(np.linalg.det(residual)) < 1e-8:
        raise ProfileError("host residual color matrix is singular")
    if mode == "raw":
        _reject_unknown(
            _require(profile, "raw"),
            {
                "cfa", "black_level", "white_level", "white_balance",
                "camera_to_xyz_d65", "radiometric_scale",
            },
            "raw",
        )
        black = _finite_array(profile, "raw.black_level")
        if black.size not in {1, 4}:
            raise ProfileError("raw.black_level must contain one or four values")
        if np.any(black < 0.0):
            raise ProfileError("raw.black_level must be nonnegative")
        if _finite_scalar(profile, "raw.white_level") <= float(np.max(black)):
            raise ProfileError("raw.white_level must exceed black level")
        if _require(profile, "raw.cfa").upper() not in {"RGGB", "BGGR", "GRBG", "GBRG"}:
            raise ProfileError("unsupported raw CFA")
        if np.any(_finite_array(profile, "raw.white_balance", (3,)) <= 0.0):
            raise ProfileError("raw.white_balance must be positive")
        matrix = _finite_array(profile, "raw.camera_to_xyz_d65", (3, 3))
        if abs(np.linalg.det(matrix)) < 1e-8:
            raise ProfileError("raw camera_to_xyz_d65 matrix is singular")
        if _finite_scalar(profile, "raw.radiometric_scale") <= 0.0:
            raise ProfileError("raw.radiometric_scale must be positive")
    elif "raw" in profile:
        raise ProfileError("raw section is allowed only for raw input mode")
    return profile


def validate_toy_profile(profile: dict) -> dict:
    _validate_common(profile, "toy")
    _validate_provenance(profile, "toy")
    if profile.get("capture_medium_family") != "digital_sensor_v1":
        raise ProfileError("Alpha supports only digital_sensor_v1")

    _reject_unknown(
        _require(profile, "optics"), {"distortion", "ca_scale", "vignette", "psf"}, "optics"
    )
    _reject_unknown(_require(profile, "optics.psf"), {"bases", "coefficients"}, "optics.psf")
    _reject_unknown(
        _require(profile, "sensor"),
        {
            "cfa", "scene_to_sensor", "exposure_scale_e", "full_well_e",
            "conversion_gain_e_per_dn", "black_level_dn", "white_level_dn", "adc_bits",
            "read_noise_e", "row_noise_e", "column_noise_e", "prnu_sigma", "dsnu_e",
            "profile_seed", "noise_enabled",
        },
        "sensor",
    )
    _reject_unknown(
        _require(profile, "isp"),
        {
            "white_balance", "sensor_to_rec2020", "tone_curve", "denoise_sigma",
            "sharpen_amount", "sharpen_radius",
        },
        "isp",
    )

    _finite_array(profile, "optics.distortion", (4,))
    if np.any(_finite_array(profile, "optics.ca_scale", (3,)) <= 0.0):
        raise ProfileError("optics.ca_scale must be positive")
    vignette = _finite_array(profile, "optics.vignette")
    if vignette.shape not in {(2,), (3, 2)}:
        raise ProfileError("optics.vignette must have shape (2,) or (3,2)")
    if np.any(vignette < 0.0):
        raise ProfileError("optics.vignette must be nonnegative")

    bases = _finite_array(profile, "optics.psf.bases")
    coefficients = _finite_array(profile, "optics.psf.coefficients")
    if bases.ndim != 3 or bases.shape[1] % 2 != 1 or bases.shape[2] % 2 != 1:
        raise ProfileError("PSF bases must be Kxoddxodd")
    if bases.shape[0] > 8:
        raise ProfileError("PSF supports at most 8 bases")
    if np.any(bases < 0.0) or np.any(np.sum(bases, axis=(1, 2)) <= 0.0):
        raise ProfileError("PSF bases must be nonnegative with positive energy")
    if coefficients.ndim != 4 or coefficients.shape[:2] != (bases.shape[0], 3):
        raise ProfileError("PSF coefficients must be Kx3xGridHxGridW")
    if np.any(coefficients < 0.0):
        raise ProfileError("PSF coefficients must be nonnegative")

    sensor_matrix = _finite_array(profile, "sensor.scene_to_sensor", (3, 3))
    if abs(np.linalg.det(sensor_matrix)) < 1e-8:
        raise ProfileError("sensor.scene_to_sensor matrix is singular")
    for field in [
        "sensor.exposure_scale_e",
        "sensor.full_well_e",
        "sensor.conversion_gain_e_per_dn",
        "sensor.white_level_dn",
    ]:
        if _finite_scalar(profile, field) <= 0.0:
            raise ProfileError(f"{field} must be positive")
    black_level = _finite_scalar(profile, "sensor.black_level_dn")
    if black_level < 0.0:
        raise ProfileError("sensor.black_level_dn must be nonnegative")
    if _finite_scalar(profile, "sensor.white_level_dn") <= black_level:
        raise ProfileError("sensor white level must exceed black level")
    if _require(profile, "sensor.cfa").upper() not in {"RGGB", "BGGR", "GRBG", "GBRG"}:
        raise ProfileError("unsupported target CFA")
    for field in [
        "sensor.read_noise_e", "sensor.row_noise_e", "sensor.column_noise_e",
        "sensor.prnu_sigma", "sensor.dsnu_e",
    ]:
        if _finite_scalar(profile, field) < 0.0:
            raise ProfileError(f"{field} must be nonnegative")
    adc_bits = _require(profile, "sensor.adc_bits")
    if isinstance(adc_bits, bool) or not isinstance(adc_bits, int) or not 1 <= adc_bits <= 32:
        raise ProfileError("sensor.adc_bits must be an integer in [1, 32]")
    profile_seed = _require(profile, "sensor.profile_seed")
    if isinstance(profile_seed, bool) or not isinstance(profile_seed, int) or profile_seed < 0:
        raise ProfileError("sensor.profile_seed must be a nonnegative integer")
    if "noise_enabled" in profile["sensor"] and not isinstance(
        profile["sensor"]["noise_enabled"], bool
    ):
        raise ProfileError("sensor.noise_enabled must be a boolean")

    if np.any(_finite_array(profile, "isp.white_balance", (3,)) <= 0.0):
        raise ProfileError("isp.white_balance must be positive")
    matrix = _finite_array(profile, "isp.sensor_to_rec2020", (3, 3))
    if abs(np.linalg.det(matrix)) < 1e-8:
        raise ProfileError("ISP sensor_to_rec2020 matrix is singular")
    curve = _finite_array(profile, "isp.tone_curve")
    if curve.ndim != 2 or curve.shape[1] != 2 or np.any(np.diff(curve[:, 0]) <= 0.0):
        raise ProfileError("tone curve x coordinates must be strictly increasing")
    if np.any(np.diff(curve[:, 1]) < 0.0):
        raise ProfileError("tone curve must be monotonic")
    if _finite_scalar(profile, "isp.denoise_sigma") < 0.0:
        raise ProfileError("isp.denoise_sigma must be nonnegative")
    _finite_scalar(profile, "isp.sharpen_amount")
    if _finite_scalar(profile, "isp.sharpen_radius") <= 0.0:
        raise ProfileError("isp.sharpen_radius must be positive")
    return profile


def load_host_profile(path_or_profile: str | Path | dict[str, Any]) -> dict:
    return validate_host_profile(_read_json(path_or_profile))


def load_toy_profile(path_or_profile: str | Path | dict[str, Any]) -> dict:
    return validate_toy_profile(_read_json(path_or_profile))
