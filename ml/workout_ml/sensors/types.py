"""Data model for raw sensor streams (see docs/sensor_spec.md section 2)."""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np

SENSORS = ("acc", "gyr")
UNITS = {"acc": ("g", "m/s^2"), "gyr": ("deg/s", "rad/s")}
FRAMES = ("metamotion", "android")


@dataclass(frozen=True)
class RawStream:
    """One sensor in its native units and device frame.

    Attributes:
        sensor: ``"acc"`` or ``"gyr"``.
        t_ns: sample timestamps, int64 ns since the Unix epoch (UTC), strictly increasing.
        xyz: float64 samples, shape (n, 3), in source units and the source device frame.
        unit: ``"g"``, ``"m/s^2"``, ``"deg/s"`` or ``"rad/s"``.
        frame: ``"metamotion"`` or ``"android"``.

    Readers must sort and de-duplicate timestamps before constructing a stream.
    """

    sensor: str
    t_ns: np.ndarray
    xyz: np.ndarray
    unit: str
    frame: str

    def __post_init__(self):
        if self.sensor not in SENSORS:
            raise ValueError(f"sensor must be one of {SENSORS}, got {self.sensor!r}")
        if self.unit not in UNITS[self.sensor]:
            raise ValueError(
                f"unit {self.unit!r} is not valid for {self.sensor!r}; expected {UNITS[self.sensor]}"
            )
        if self.frame not in FRAMES:
            raise ValueError(f"frame must be one of {FRAMES}, got {self.frame!r}")
        t = np.asarray(self.t_ns)
        xyz = np.asarray(self.xyz)
        if t.ndim != 1 or xyz.ndim != 2 or xyz.shape[1] != 3 or xyz.shape[0] != t.shape[0]:
            raise ValueError(
                f"shape mismatch: t_ns {t.shape}, xyz {xyz.shape} (need (n,) and (n, 3))"
            )
        if not np.issubdtype(t.dtype, np.integer):
            raise ValueError(f"t_ns must be an integer array, got {t.dtype}")
        t = t.astype(np.int64, copy=False)
        xyz = xyz.astype(np.float64, copy=False)
        if not np.isfinite(xyz).all():
            raise ValueError("xyz contains non-finite values")
        if t.size > 1 and not (np.diff(t) > 0).all():
            raise ValueError("t_ns must be strictly increasing")
        object.__setattr__(self, "t_ns", t)
        object.__setattr__(self, "xyz", xyz)

    def __len__(self):
        return int(self.t_ns.shape[0])


@dataclass
class SensorSet:
    """One recorded set: an accelerometer stream, a gyroscope stream and metadata."""

    acc: RawStream
    gyr: RawStream
    meta: dict = field(default_factory=dict)

    def __post_init__(self):
        if self.acc.sensor != "acc" or self.gyr.sensor != "gyr":
            raise ValueError("SensorSet needs acc=RawStream('acc'), gyr=RawStream('gyr')")
