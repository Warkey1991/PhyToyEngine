from pathlib import Path

import pytest


@pytest.fixture(scope="session")
def project_root() -> Path:
    return Path(__file__).resolve().parents[2]


@pytest.fixture(scope="session")
def host_srgb_path(project_root: Path) -> Path:
    return project_root / "profiles/authoring/host_generic_srgb.json"


@pytest.fixture(scope="session")
def host_raw_path(project_root: Path) -> Path:
    return project_root / "profiles/authoring/host_reference_raw.json"


@pytest.fixture(scope="session")
def host_device_raw_path(project_root: Path) -> Path:
    return project_root / "profiles/authoring/host_samsung_sm_s9210_camera0_raw.json"


@pytest.fixture(scope="session")
def toy_path(project_root: Path) -> Path:
    return project_root / "profiles/authoring/toy_phytoy_digital_01_v1.json"
