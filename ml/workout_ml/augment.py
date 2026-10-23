"""Rotation augmentation (D-018): random proper rotations applied to the sensor frame.

Each augmented copy of a set is the original set with acc and gyro triples multiplied by
one random rotation R (axis uniform on the sphere, angle uniform in [0, max_deg]); the same
R is used for acc and gyro and is applied *before* feature engineering. Augmentation is for
training data only: callers must never augment held-out data.

Row-order and RNG scheme (the order matters for reproducing the R2w8 study exactly): one
``default_rng(seed)``; copy-major; within a copy the sets in sorted id order each draw
(axis, then angle).
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from scipy.spatial.transform import Rotation

ACC_COLS = ["acc_x", "acc_y", "acc_z"]
GYR_COLS = ["gyr_x", "gyr_y", "gyr_z"]
SET_ID_OFFSET = 1_000_000  # integer set ids: copy k of set s gets s + (k + 1) * offset


def rotation_matrix(axis, theta_deg):
    """3x3 rotation of ``theta_deg`` degrees about ``axis`` (normalised)."""
    axis = np.asarray(axis, dtype=float)
    axis = axis / np.linalg.norm(axis)
    return Rotation.from_rotvec(axis * np.deg2rad(theta_deg)).as_matrix()


def random_unit_axes(n, rng):
    v = rng.normal(size=(n, 3))
    return v / np.linalg.norm(v, axis=1, keepdims=True)


def random_rotations(n, max_deg, rng):
    """``n`` random rotation matrices (axis uniform on the sphere, angle ~ U[0, max_deg])."""
    out = []
    for _ in range(n):
        axis = random_unit_axes(1, rng)[0]
        theta = rng.uniform(0.0, max_deg)
        out.append(rotation_matrix(axis, theta))
    return out


def rotate_sensor_frame(df, R, rows=None):
    """Copy of ``df`` with acc and gyro triples multiplied by ``R`` (v' = R v).

    ``rows``: optional positional index array; only those rows are rotated.
    """
    out = df.copy()
    sel = slice(None) if rows is None else rows
    for cols in (ACC_COLS, GYR_COLS):
        values = out[cols].to_numpy(dtype=float).copy()
        values[sel] = values[sel] @ R.T
        out[cols] = values
    return out


def _copy_set_id(set_id, copy_index):
    if isinstance(set_id, (int, np.integer)):
        return int(set_id) + (copy_index + 1) * SET_ID_OFFSET
    return f"{set_id}__aug{copy_index + 1}"


def augment_sets(cleaned_df, copies, max_deg, seed):
    """``copies`` randomly rotated copies of every set (originals are not included).

    Returns a DataFrame, copy-major (copy 1 for all sets, then copy 2, ...), with the same
    columns as ``cleaned_df`` plus ``aug_of`` (the source set id), ``aug_copy`` (1-based copy
    number); ``set`` holds a new unique id per copy and ``participant`` is unchanged, so a
    copy can always be mapped back to its source set and participant.
    """
    if copies <= 0:
        return cleaned_df.iloc[:0].assign(aug_of=pd.Series(dtype=object), aug_copy=0)
    rng = np.random.default_rng(seed)
    set_rows = cleaned_df.groupby("set", sort=True).indices
    parts = []
    for k in range(copies):
        out = cleaned_df.copy()
        rotations = random_rotations(len(set_rows), max_deg, rng)
        for (_, rows), R in zip(set_rows.items(), rotations, strict=True):
            out = rotate_sensor_frame(out, R, rows)
        out["aug_of"] = cleaned_df["set"].to_numpy()
        out["aug_copy"] = k + 1
        out["set"] = [_copy_set_id(s, k) for s in cleaned_df["set"].to_numpy()]
        parts.append(out)
    return pd.concat(parts)


def engineer_augmented(aug_df, engineer):
    """Feature-engineer an ``augment_sets`` result copy by copy (so row order is copy-major).

    ``engineer``: callable DataFrame -> featured DataFrame (per-set, as for the originals).
    The result carries ``aug_of`` and ``aug_copy`` columns.
    """
    if aug_df.empty:
        return aug_df
    parts = []
    for copy_no, sub in aug_df.groupby("aug_copy", sort=True):
        feat = engineer(sub.drop(columns=["aug_of", "aug_copy"]))
        source = sub.groupby("set")["aug_of"].first()
        feat["aug_of"] = feat["set"].map(source).to_numpy()
        feat["aug_copy"] = copy_no
        parts.append(feat)
    return pd.concat(parts)
