"""Source-rate emulation (segment, regrid, anti-alias, decimate) and 200 ms binning."""

from __future__ import annotations

import logging

import numpy as np
import pandas as pd
from scipy import signal

logger = logging.getLogger(__name__)

BIN_MS = 200
_NS_PER_MS = 1_000_000


def segment_bounds(t_ns: np.ndarray, max_gap_ms: float) -> list[tuple[int, int]]:
    """Split sample indices at gaps larger than ``max_gap_ms``; returns [(start, stop), ...]."""
    n = len(t_ns)
    if n == 0:
        return []
    breaks = np.flatnonzero(np.diff(t_ns) > max_gap_ms * _NS_PER_MS) + 1
    starts = np.concatenate([[0], breaks])
    stops = np.concatenate([breaks, [n]])
    return [(int(a), int(b)) for a, b in zip(starts, stops)]


def nominal_rate_hz(t_ns: np.ndarray) -> float:
    """``1 / median(diff(t))`` rounded to 0.1 Hz."""
    if len(t_ns) < 2:
        raise ValueError("need at least 2 samples to estimate a rate")
    dt_s = np.median(np.diff(t_ns)) / 1e9
    return round(1.0 / dt_s, 1)


def _antialias_sos(order: int, cutoff_hz: float, fs_hz: float):
    wn = cutoff_hz / (fs_hz / 2.0)
    if wn >= 1.0:
        return None  # nothing above the target band can exist at this rate
    return signal.butter(order, wn, btype="low", output="sos")


def _padlen(sos) -> int:
    """sosfiltfilt's default padding requirement (the signal must be longer than this)."""
    ntaps = 2 * sos.shape[0] + 1
    ntaps -= min(int((sos[:, 2] == 0).sum()), int((sos[:, 5] == 0).sum()))
    return 3 * ntaps


def regrid_segment(t_s: np.ndarray, xyz: np.ndarray, rate_hz: float):
    """Linear interpolation onto a uniform grid at ``rate_hz`` starting at ``t_s[0]``."""
    n = int(np.floor((t_s[-1] - t_s[0]) * rate_hz + 1e-9)) + 1
    grid = t_s[0] + np.arange(n) / rate_hz
    out = np.column_stack([np.interp(grid, t_s, xyz[:, k]) for k in range(3)])
    return grid, out


def decimate_segment(grid_s: np.ndarray, xyz: np.ndarray, target_hz: float):
    """Sample a (uniform-grid) signal at ``target_hz`` by linear interpolation."""
    n = int(np.floor((grid_s[-1] - grid_s[0]) * target_hz + 1e-9)) + 1
    out_t = grid_s[0] + np.arange(n) / target_hz
    out = np.column_stack([np.interp(out_t, grid_s, xyz[:, k]) for k in range(3)])
    return out_t, out


def emulate_source_rate(
    t_ns: np.ndarray,
    xyz: np.ndarray,
    target_hz: float,
    *,
    anti_alias_order: int = 4,
    anti_alias_fraction: float = 0.8,
    max_gap_ms: float = 500.0,
    label: str = "stream",
):
    """Make an irregular stream look like a device sampling at ``target_hz``.

    Per segment (split at gaps > ``max_gap_ms``): regrid at the nominal rate, zero-phase
    Butterworth low-pass at ``anti_alias_fraction * target_hz / 2``, then decimate to
    ``target_hz``. Segments too short for the filter are dropped with a WARNING.

    Returns:
        (t_ns, xyz) of the emulated stream, concatenated over the kept segments.
    """
    t_ns = np.asarray(t_ns, dtype=np.int64)
    rate = nominal_rate_hz(t_ns)
    sos = _antialias_sos(anti_alias_order, anti_alias_fraction * target_hz / 2.0, rate)
    padlen = _padlen(sos) if sos is not None else 0

    out_t, out_x = [], []
    for start, stop in segment_bounds(t_ns, max_gap_ms):
        seg_t = t_ns[start:stop]
        if stop - start < 2:
            logger.warning(f"{label}: dropping a 1-sample segment at t={int(seg_t[0])} ns")
            continue
        t0 = int(seg_t[0])
        t_s = (seg_t - t0) / 1e9
        grid, reg = regrid_segment(t_s, xyz[start:stop], rate)
        if sos is not None:
            if len(grid) <= padlen:
                logger.warning(
                    f"{label}: dropping segment of {len(grid)} samples at t={t0} ns "
                    f"(shorter than filter padding {padlen + 1})"
                )
                continue
            reg = signal.sosfiltfilt(sos, reg, axis=0)
        dec_t, dec = decimate_segment(grid, reg, target_hz)
        out_t.append(t0 + np.rint(dec_t * 1e9).astype(np.int64))
        out_x.append(dec)
    if not out_t:
        return np.empty(0, dtype=np.int64), np.empty((0, 3))
    return np.concatenate(out_t), np.concatenate(out_x)


def bin_mean(t_ns: np.ndarray, xyz: np.ndarray, prefix: str, bin_ms: int = BIN_MS) -> pd.DataFrame:
    """Arithmetic mean per epoch-aligned bin; index = left edge (naive UTC DatetimeIndex)."""
    cols = [f"{prefix}_x", f"{prefix}_y", f"{prefix}_z"]
    if len(t_ns) == 0:
        return pd.DataFrame(columns=cols, index=pd.DatetimeIndex([]), dtype=np.float64)
    width = bin_ms * _NS_PER_MS
    bin_id = np.asarray(t_ns, dtype=np.int64) // width
    df = pd.DataFrame(np.asarray(xyz), columns=cols)
    means = df.groupby(bin_id, sort=True).mean()
    means.index = pd.DatetimeIndex((means.index.to_numpy() * width).astype("datetime64[ns]"))
    return means
