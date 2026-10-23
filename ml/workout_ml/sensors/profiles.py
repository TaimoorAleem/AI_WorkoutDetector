"""Device profiles: YAML files with unit scales, rotation, emulation and clip settings."""

from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import yaml

PROFILES_DIR = Path(__file__).parent / "profiles"
SUPPORTED_CSF_VERSION = 1
ROTATION_STATUSES = ("identity", "prior", "fitted")
_ORTHO_TOL = 1e-6


@dataclass(frozen=True)
class SensorSpec:
    source_unit: str
    unit_scale: float


@dataclass(frozen=True)
class Emulation:
    enabled: bool = False
    acc_hz: float | None = None
    gyr_hz: float | None = None
    anti_alias_order: int = 4
    anti_alias_fraction: float = 0.8
    max_gap_ms: float = 500.0

    def target_hz(self, sensor: str) -> float:
        hz = self.acc_hz if sensor == "acc" else self.gyr_hz
        if hz is None:
            raise ValueError(f"emulation has no target rate for {sensor!r}")
        return float(hz)


@dataclass(frozen=True)
class Profile:
    profile_id: str
    csf_version: int
    device: dict
    acc: SensorSpec
    gyr: SensorSpec
    rotation: np.ndarray  # canonical = R @ device
    rotation_status: str
    emulation: Emulation
    clip_acc_g: float | None
    calibration: dict | None = None
    transform_id: str = field(default="")

    def sensor_spec(self, sensor: str) -> SensorSpec:
        return self.acc if sensor == "acc" else self.gyr


def _transform_payload(acc_scale, gyr_scale, rotation, emulation: Emulation, clip_acc_g):
    em = {"enabled": bool(emulation.enabled)}
    if emulation.enabled:
        em.update(
            acc_hz=float(emulation.acc_hz),
            gyr_hz=float(emulation.gyr_hz),
            anti_alias_order=int(emulation.anti_alias_order),
            anti_alias_fraction=float(emulation.anti_alias_fraction),
            max_gap_ms=float(emulation.max_gap_ms),
        )
    return {
        "acc_scale": float(acc_scale),
        "gyr_scale": float(gyr_scale),
        "rotation": [[float(v) for v in row] for row in np.asarray(rotation)],
        "emulation": em,
        "clip_acc_g": None if clip_acc_g is None else float(clip_acc_g),
    }


def compute_transform_id(profile: Profile) -> str:
    """Short hash of the profile's numeric content (scales, R, emulation, clip)."""
    payload = _transform_payload(
        profile.acc.unit_scale,
        profile.gyr.unit_scale,
        profile.rotation,
        profile.emulation,
        profile.clip_acc_g,
    )
    blob = json.dumps(payload, sort_keys=True, separators=(",", ":")).encode()
    return hashlib.sha256(blob).hexdigest()[:12]


def validate_rotation(R) -> np.ndarray:
    """Return R as a (3, 3) float array; raise ValueError unless it is a proper rotation."""
    R = np.asarray(R, dtype=np.float64)
    if R.shape != (3, 3) or not np.isfinite(R).all():
        raise ValueError(f"rotation must be a finite 3x3 matrix, got shape {R.shape}")
    err = np.abs(R.T @ R - np.eye(3)).max()
    if err >= _ORTHO_TOL:
        raise ValueError(f"rotation is not orthonormal (max |R^T R - I| = {err:.3g})")
    det = np.linalg.det(R)
    if det < 0:
        raise ValueError(f"rotation has det = {det:.3f} < 0 (a mirror); mirroring is not allowed")
    return R


def _require(d: dict, key: str, where: str):
    if not isinstance(d, dict) or key not in d:
        raise ValueError(f"profile is missing {where}{key!r}")
    return d[key]


def _sensor_spec(d: dict, sensor: str, allowed_units) -> SensorSpec:
    spec = _require(d, sensor, "")
    unit = _require(spec, "source_unit", f"{sensor}.")
    if unit not in allowed_units:
        raise ValueError(f"{sensor}.source_unit {unit!r} not in {allowed_units}")
    scale = float(_require(spec, "unit_scale", f"{sensor}."))
    if not np.isfinite(scale) or scale <= 0:
        raise ValueError(f"{sensor}.unit_scale must be positive and finite, got {scale}")
    return SensorSpec(source_unit=unit, unit_scale=scale)


def _emulation(d: dict) -> Emulation:
    if not isinstance(d, dict) or "enabled" not in d:
        raise ValueError("profile is missing 'emulation.enabled'")
    if not d["enabled"]:
        return Emulation(enabled=False)
    em = Emulation(
        enabled=True,
        acc_hz=float(_require(d, "acc_hz", "emulation.")),
        gyr_hz=float(_require(d, "gyr_hz", "emulation.")),
        anti_alias_order=int(d.get("anti_alias_order", 4)),
        anti_alias_fraction=float(d.get("anti_alias_fraction", 0.8)),
        max_gap_ms=float(d.get("max_gap_ms", 500.0)),
    )
    if em.acc_hz <= 0 or em.gyr_hz <= 0 or em.max_gap_ms <= 0 or em.anti_alias_order < 1:
        raise ValueError("emulation rates, max_gap_ms and anti_alias_order must be positive")
    if not 0 < em.anti_alias_fraction <= 1:
        raise ValueError("emulation.anti_alias_fraction must be in (0, 1]")
    return em


def profile_from_dict(raw: dict) -> Profile:
    """Validate a parsed YAML mapping and build a Profile (with ``transform_id``)."""
    if not isinstance(raw, dict):
        raise ValueError("profile must be a mapping")
    profile_id = _require(raw, "profile_id", "")
    csf_version = _require(raw, "csf_version", "")
    if csf_version != SUPPORTED_CSF_VERSION:
        raise ValueError(
            f"unsupported csf_version {csf_version!r}; expected {SUPPORTED_CSF_VERSION}"
        )
    status = _require(raw, "rotation_status", "")
    if status not in ROTATION_STATUSES:
        raise ValueError(f"rotation_status {status!r} not in {ROTATION_STATUSES}")
    clip = _require(raw, "clip", "")
    clip_acc = _require(clip, "acc_g", "clip.")
    if clip_acc is not None:
        clip_acc = float(clip_acc)
        if not np.isfinite(clip_acc) or clip_acc <= 0:
            raise ValueError("clip.acc_g must be null or a positive number")
    profile = Profile(
        profile_id=str(profile_id),
        csf_version=int(csf_version),
        device=dict(raw.get("device") or {}),
        acc=_sensor_spec(raw, "acc", ("g", "m/s^2")),
        gyr=_sensor_spec(raw, "gyr", ("deg/s", "rad/s")),
        rotation=validate_rotation(_require(raw, "rotation", "")),
        rotation_status=status,
        emulation=_emulation(_require(raw, "emulation", "")),
        clip_acc_g=clip_acc,
        calibration=raw.get("calibration"),
    )
    object.__setattr__(profile, "transform_id", compute_transform_id(profile))
    return profile


def load_profile(id_or_path) -> Profile:
    """Load and validate a profile by id (bundled ``profiles/<id>.yaml``) or by file path."""
    p = Path(str(id_or_path))
    if p.suffix.lower() in (".yaml", ".yml") or p.is_file():
        path = p
    else:
        path = PROFILES_DIR / f"{id_or_path}.yaml"
    if not path.is_file():
        raise FileNotFoundError(f"profile not found: {id_or_path!r} (looked at {path})")
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    profile = profile_from_dict(raw)
    if path.parent == PROFILES_DIR and path.stem != profile.profile_id:
        raise ValueError(f"profile_id {profile.profile_id!r} does not match file name {path.name}")
    return profile
