"""Reader for MetaMotion (MbientLab) accelerometer / gyroscope CSV exports."""

from __future__ import annotations

import logging
from pathlib import Path

import numpy as np
import pandas as pd

from workout_ml.sensors.types import RawStream, SensorSet

logger = logging.getLogger(__name__)

EPOCH_COL = "epoch (ms)"


def _read_stream(path, sensor: str, unit: str) -> RawStream:
    df = pd.read_csv(path)
    if EPOCH_COL not in df.columns:
        raise ValueError(f"{path}: missing column {EPOCH_COL!r}")
    axes = [c for c in df.columns if c.endswith("-axis (g)") or c.endswith("-axis (deg/s)")]
    if len(axes) != 3:
        raise ValueError(f"{path}: expected 3 '*-axis' columns, found {axes}")
    t_ns = df[EPOCH_COL].to_numpy(dtype=np.int64) * 1_000_000
    xyz = df[axes].to_numpy(dtype=np.float64)
    # Readers own sorting and de-duplication of timestamps.
    order = np.argsort(t_ns, kind="stable")
    if not (order == np.arange(len(order))).all():
        logger.warning(f"{Path(path).name}: timestamps were not sorted; sorting")
        t_ns, xyz = t_ns[order], xyz[order]
    keep = np.concatenate([[True], np.diff(t_ns) > 0]) if len(t_ns) else np.empty(0, bool)
    if not keep.all():
        logger.warning(f"{Path(path).name}: dropping {int((~keep).sum())} duplicate timestamps")
        t_ns, xyz = t_ns[keep], xyz[keep]
    return RawStream(sensor=sensor, t_ns=t_ns, xyz=xyz, unit=unit, frame="metamotion")


def read_metamotion(acc_path, gyr_path, meta: dict | None = None) -> SensorSet:
    """Read an accelerometer/gyroscope CSV pair into a SensorSet (native units: g, deg/s)."""
    meta = dict(meta or {})
    meta.setdefault("source_file", Path(acc_path).name)
    meta.setdefault("source_file_gyr", Path(gyr_path).name)
    return SensorSet(
        acc=_read_stream(acc_path, "acc", "g"),
        gyr=_read_stream(gyr_path, "gyr", "deg/s"),
        meta=meta,
    )
