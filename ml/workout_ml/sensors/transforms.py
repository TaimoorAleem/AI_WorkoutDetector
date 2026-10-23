"""Unit scaling, rotation and clipping (steps 1-3 of to_csf) and their inverses."""

from __future__ import annotations

import numpy as np

from workout_ml.sensors.profiles import Profile, validate_rotation


def apply_unit_scale(xyz: np.ndarray, scale: float) -> np.ndarray:
    return np.asarray(xyz, dtype=np.float64) * scale


def invert_unit_scale(xyz: np.ndarray, scale: float) -> np.ndarray:
    return np.asarray(xyz, dtype=np.float64) / scale


def apply_rotation(xyz: np.ndarray, R) -> np.ndarray:
    """canonical = R @ device, per sample (rows are samples, hence ``xyz @ R.T``)."""
    return np.asarray(xyz, dtype=np.float64) @ validate_rotation(R).T


def invert_rotation(xyz: np.ndarray, R) -> np.ndarray:
    """device = R^T @ canonical, per sample."""
    return np.asarray(xyz, dtype=np.float64) @ validate_rotation(R)


def clip_acc(xyz: np.ndarray, limit_g: float | None) -> np.ndarray:
    """Clip accelerometer components to +-limit_g (no-op when limit is None)."""
    if limit_g is None:
        return xyz
    return np.clip(xyz, -limit_g, limit_g)


def to_canonical_values(xyz: np.ndarray, profile: Profile, sensor: str) -> np.ndarray:
    """Steps 1-3: units, rotation, optional accelerometer clip."""
    out = apply_unit_scale(xyz, profile.sensor_spec(sensor).unit_scale)
    out = apply_rotation(out, profile.rotation)
    if sensor == "acc":
        out = clip_acc(out, profile.clip_acc_g)
    return out


def from_canonical(xyz_canonical: np.ndarray, profile: Profile, sensor: str) -> np.ndarray:
    """Inverse of steps 1-2: canonical (g or deg/s, MetaMotion frame) -> device units and frame.

    ``device = R^T @ canonical``, then divide by ``unit_scale``. Clipping is not invertible
    and is not undone.
    """
    out = invert_rotation(xyz_canonical, profile.rotation)
    return invert_unit_scale(out, profile.sensor_spec(sensor).unit_scale)
