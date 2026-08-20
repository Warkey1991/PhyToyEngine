from __future__ import annotations

import ctypes
import os
from pathlib import Path

import numpy as np
import pytest

from phytoy_ref.pipeline import ReferencePipeline
from phytoy_ref.profiles import load_host_profile, load_toy_profile


LIBRARY_PATH = os.environ.get("PHYTOY_CPP_LIBRARY")
PROFILE_DIR = os.environ.get("PHYTOY_CPP_PROFILE_DIR")
pytestmark = pytest.mark.skipif(
    not LIBRARY_PATH or not PROFILE_DIR,
    reason="native library conformance environment is not configured",
)


class Plane(ctypes.Structure):
    _fields_ = [("data", ctypes.POINTER(ctypes.c_float)), ("row_stride_floats", ctypes.c_uint32)]


class Frame(ctypes.Structure):
    _fields_ = [
        ("abi_version", ctypes.c_uint32),
        ("format", ctypes.c_int),
        ("width", ctypes.c_uint32),
        ("height", ctypes.c_uint32),
        ("plane", Plane * 3),
        ("yuv_full_range", ctypes.c_uint32),
    ]


class Output(ctypes.Structure):
    _fields_ = [
        ("data", ctypes.POINTER(ctypes.c_float)),
        ("capacity_floats", ctypes.c_size_t),
        ("row_stride_floats", ctypes.c_uint32),
    ]


StageCallback = ctypes.CFUNCTYPE(
    None,
    ctypes.c_void_p,
    ctypes.c_int,
    ctypes.POINTER(ctypes.c_float),
    ctypes.c_uint32,
    ctypes.c_uint32,
    ctypes.c_uint32,
)


class Options(ctypes.Structure):
    _fields_ = [
        ("abi_version", ctypes.c_uint32),
        ("seed", ctypes.c_uint64),
        ("stage_callback", StageCallback),
        ("stage_user_data", ctypes.c_void_p),
    ]


def _native_render(source: np.ndarray) -> tuple[np.ndarray, dict[int, np.ndarray]]:
    library = ctypes.CDLL(str(LIBRARY_PATH))
    library.pte_engine_create.argtypes = [ctypes.c_char_p, ctypes.c_char_p, ctypes.POINTER(ctypes.c_void_p)]
    library.pte_engine_create.restype = ctypes.c_int
    library.pte_engine_destroy.argtypes = [ctypes.c_void_p]
    library.pte_engine_render.argtypes = [
        ctypes.c_void_p,
        ctypes.POINTER(Frame),
        ctypes.POINTER(Options),
        ctypes.POINTER(Output),
    ]
    library.pte_engine_render.restype = ctypes.c_int
    library.pte_engine_last_error.argtypes = [ctypes.c_void_p]
    library.pte_engine_last_error.restype = ctypes.c_char_p

    profile_dir = Path(str(PROFILE_DIR))
    engine = ctypes.c_void_p()
    status = library.pte_engine_create(
        os.fsencode(profile_dir / "host_generic_srgb.ptp"),
        os.fsencode(profile_dir / "toy_fixed_focus_conformance.ptp"),
        ctypes.byref(engine),
    )
    assert status == 0

    contiguous = np.ascontiguousarray(source, dtype=np.float32)
    output_array = np.empty_like(contiguous)
    planes = (Plane * 3)()
    planes[0] = Plane(contiguous.ctypes.data_as(ctypes.POINTER(ctypes.c_float)), contiguous.shape[1] * 3)
    frame = Frame(1, 1, contiguous.shape[1], contiguous.shape[0], planes, 0)
    output = Output(
        output_array.ctypes.data_as(ctypes.POINTER(ctypes.c_float)),
        output_array.size,
        contiguous.shape[1] * 3,
    )
    stages: dict[int, np.ndarray] = {}

    @StageCallback
    def callback(_user, stage, data, width, height, channels):
        values = np.ctypeslib.as_array(data, shape=(width * height * channels,))
        stages[stage] = values.copy().reshape(height, width, channels)

    options = Options(1, 1234, callback, None)
    try:
        status = library.pte_engine_render(engine, ctypes.byref(frame), ctypes.byref(options), ctypes.byref(output))
        assert status == 0, library.pte_engine_last_error(engine).decode("utf-8")
    finally:
        library.pte_engine_destroy(engine)
    return output_array, stages


def test_python_cpp_noiseless_stage_conformance(project_root: Path) -> None:
    height, width = 18, 22
    yy, xx = np.mgrid[0:height, 0:width]
    source = np.stack(
        [xx / (width - 1), yy / (height - 1), 0.2 + 0.5 * xx / (width - 1)], axis=-1
    )
    host = load_host_profile(project_root / "profiles/authoring/host_generic_srgb.json")
    toy = load_toy_profile(project_root / "profiles/authoring/toy_fixed_focus_conformance.json")
    reference = ReferencePipeline(host, toy).render_srgb(source, seed=1234)
    native_output, native_stages = _native_render(source)

    names = {
        1: "01_scene_linear",
        2: "02_target_optics",
        3: "03_target_sensor_dn",
        4: "04_target_isp_linear",
        5: "05_output_srgb",
    }
    limits = {1: 2e-5, 2: 8e-5, 3: 0.51, 4: 2e-3, 5: 3e-3}
    for stage, name in names.items():
        native = native_stages[stage]
        expected = reference.stages[name]
        if native.shape[-1] == 1 and expected.ndim == 2:
            native = native[..., 0]
        difference = np.max(np.abs(native - expected))
        assert difference <= limits[stage], f"{name} max difference {difference}"
    assert np.max(np.abs(native_output - reference.output_srgb)) <= limits[5]
