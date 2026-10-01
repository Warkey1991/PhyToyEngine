"""Fixed-order PhyToyEngine reference render graph."""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from .isp import run_isp
from .normalize import normalize_raw, normalize_srgb, normalize_yuv420
from .optics import apply_optics
from .profiles import load_host_profile, load_toy_profile
from .sensor import simulate_sensor
from .sampling import profile_for_resolution


@dataclass(frozen=True)
class RenderResult:
    output_srgb: np.ndarray
    stages: dict[str, np.ndarray]
    seed: int
    host_profile_id: str
    toy_profile_id: str


class ReferencePipeline:
    def __init__(self, host_profile: dict, toy_profile: dict):
        self.host_profile = load_host_profile(host_profile)
        self.toy_profile = load_toy_profile(toy_profile)

    def render_srgb(self, encoded: np.ndarray, *, seed: int = 1) -> RenderResult:
        scene = normalize_srgb(encoded, self.host_profile)
        return self._render_scene(scene, seed=seed)

    def render_yuv420(
        self,
        y_plane: np.ndarray,
        uv_plane: np.ndarray,
        *,
        full_range: bool = True,
        seed: int = 1,
    ) -> RenderResult:
        scene = normalize_yuv420(
            y_plane, uv_plane, self.host_profile, full_range=full_range
        )
        return self._render_scene(scene, seed=seed)

    def render_raw(self, raw: np.ndarray, *, seed: int = 1) -> RenderResult:
        scene = normalize_raw(raw, self.host_profile)
        return self._render_scene(scene, seed=seed)

    def _render_scene(self, scene: np.ndarray, *, seed: int) -> RenderResult:
        toy = profile_for_resolution(self.toy_profile, scene.shape[1], scene.shape[0])
        stages: dict[str, np.ndarray] = {"01_scene_linear": scene.copy()}
        optical = apply_optics(scene, toy)
        stages["02_target_optics"] = optical.copy()
        sensor_dn = simulate_sensor(optical, toy, seed=seed)
        stages["03_target_sensor_dn"] = sensor_dn.copy()
        toned, output = run_isp(sensor_dn, toy)
        stages["04_target_isp_linear"] = toned.copy()
        stages["05_output_srgb"] = output.copy()
        return RenderResult(
            output_srgb=output,
            stages=stages,
            seed=seed,
            host_profile_id=self.host_profile["id"],
            toy_profile_id=self.toy_profile["id"],
        )
