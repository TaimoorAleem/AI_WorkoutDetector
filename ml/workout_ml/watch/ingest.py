"""Watch ingest: raw session zips -> CSF-1 DataFrame, and the calibration check.

``load_watch_sessions`` mirrors ``DataLoader.load_data`` (200 ms bins, six sensor columns plus
``participant``, ``label``, ``category``, ``set``) and adds the columns the evaluation needs.

Label rules (spec 3.1):
- ``set`` segment: label = exercise. A set without an exercise cannot be scored and is skipped
  (counted in ``df.attrs["skipped"]``). A set with an exercise but no ``reps_user`` is kept
  with ``confirmed=False`` (excluded from rep evaluation only).
- implicit/explicit ``rest``: label ``rest``. ``other``: label ``other`` (unseen in training).
- ``unlabeled`` gym marks and discarded segments (reader default) are not loaded.
- Segments shorter than ``min_bins`` 200 ms bins are skipped: the per-set filters and rolling
  windows need a minimum length.
"""

from __future__ import annotations

import argparse
import json
import logging
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.spatial.transform import Rotation

from workout_ml.sensors import load_profile, to_csf
from workout_ml.sensors.android import GRAVITY, read_session
from workout_ml.sensors.profiles import Profile
from workout_ml.watch.datasets import dataset_paths

logger = logging.getLogger(__name__)

LOADED_KINDS = ("set", "rest", "other")
MIN_BINS = 30  # 6 s at 5 Hz; low-pass filtfilt (order 5) needs > 18 samples
ALLOWED = {"set": {"bench", "squat", "dead", "ohp", "row"}}

# calibration_check constants
POSES = {  # step -> (axis index, sign): gravity (specific force) direction in the device frame
    "face_up": (2, +1),
    "face_down": (2, -1),
    "buttons_up": (0, +1),
    "buttons_down": (0, -1),
    "twelve_up": (1, +1),
    "twelve_down": (1, -1),
}
ROT_STEPS = ("rot_x", "rot_y", "rot_z")
ACC_GATE_G, ACC_GATE_TOL = 1.0, 0.03
BIAS_GATE_DPS = 1.0
SIGN_MARGIN_DEG = 10.0  # |err(flip axis) - err(as is)| needed to call an axis identifiable
OFF_AXIS_MAX_DEG = 15.0


def _as_profile(profile) -> Profile:
    return profile if isinstance(profile, Profile) else load_profile(profile)


def load_watch_sessions(
    dataset,
    profile,
    *,
    root=None,
    datasets_file=None,
    min_bins: int = MIN_BINS,
    kinds=LOADED_KINDS,
) -> pd.DataFrame:
    """All labeled segments of a watch dataset as one CSF-1 DataFrame.

    Args:
        dataset: a name from ``data/watch_datasets.yaml`` or a list of session zip paths.
        profile: a ``Profile`` or profile id/path used by ``to_csf`` (e.g. the fitted one).

    Returns:
        DataFrame indexed by 200 ms bin, with acc_*/gyr_*, participant, label, category, set
        (``<session8>-<segment_id>``), session_id, segment_id, kind, reps_user, source,
        implicit and confirmed. ``attrs``: ``skipped`` (list of (set, reason)), ``sessions``
        (ids), ``csf`` (profile id / transform id). Empty frame (same columns) when the
        dataset has no sessions.
    """
    profile = _as_profile(profile)
    paths = dataset_paths(dataset, root, datasets_file)
    frames, skipped, session_ids = [], [], []
    for path in paths:
        session = read_session(path)
        session_ids.append(session.session_id)
        for ss in session.segments(kinds=kinds):
            m = ss.meta
            sid = m["set"]
            if m["kind"] == "set" and not m["label"]:
                skipped.append((sid, "set without exercise"))
                continue
            if len(ss.acc) < 2 or len(ss.gyr) < 2:
                skipped.append((sid, "fewer than 2 samples"))
                continue
            df = to_csf(ss, profile)
            if len(df) < min_bins:
                skipped.append((sid, f"only {len(df)} bins"))
                continue
            df["participant"] = m["participant"] or "unknown"
            df["label"] = m["label"]
            df["category"] = m["category"] or ""
            df["set"] = sid
            df["session_id"] = m["session_id"]
            df["segment_id"] = m["segment_id"]
            df["kind"] = m["kind"]
            df["reps_user"] = np.nan if m["reps_user"] is None else float(m["reps_user"])
            df["source"] = m["source"] or ""
            df["implicit"] = bool(m["implicit"])
            df["confirmed"] = bool(m["kind"] == "set" and m["reps_user"] is not None)
            frames.append(df)
    if frames:
        out = pd.concat(frames)
        out.index.name = "epoch (ms)"
    else:
        out = _empty_frame()
    out.attrs["csf"] = {"profile_id": profile.profile_id, "transform_id": profile.transform_id}
    out.attrs["skipped"] = skipped
    out.attrs["sessions"] = session_ids
    if skipped:
        logger.warning("skipped %d segment(s): %s", len(skipped), skipped[:5])
    return out


def _empty_frame() -> pd.DataFrame:
    cols = [
        "acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z", "participant", "label", "category",
        "set", "session_id", "segment_id", "kind", "reps_user", "source", "implicit", "confirmed",
    ]  # fmt: skip
    return pd.DataFrame({c: pd.Series(dtype=object) for c in cols})


# --------------------------------------------------------------------------- calibration check


def _trim(xyz: np.ndarray, frac: float = 0.2) -> np.ndarray:
    """Drop the first/last fraction of a hold step (hand settling)."""
    n = len(xyz)
    return xyz[int(n * frac) : int(n * (1 - frac))]


def _integrate_gravity_error(seg, gyr_xyz: np.ndarray) -> float:
    """Median angle (deg) between the gravity direction predicted by integrating ``gyr_xyz``
    (rad/s, body frame) and the accelerometer's, over one rotation step."""
    tg = seg.gyr.t_ns.astype(np.float64) / 1e9
    ta = seg.acc.t_ns.astype(np.float64) / 1e9
    dt = np.diff(tg)
    incs = Rotation.from_rotvec(gyr_xyz[:-1] * dt[:, None])
    R = Rotation.identity()
    mats = [R]
    for inc in incs:
        R = R * inc  # body-frame increment
        mats.append(R)
    a = seg.acc.xyz / np.linalg.norm(seg.acc.xyz, axis=1, keepdims=True)
    g0 = a[: max(5, int(0.2 * 50))].mean(0)
    g0 /= np.linalg.norm(g0)
    idx = np.searchsorted(tg, ta).clip(0, len(mats) - 1)
    pred = np.array([mats[i].inv().apply(g0) for i in idx])
    err = np.degrees(np.arccos(np.clip((pred * a).sum(1), -1, 1)))
    return float(np.median(err))


def calibration_check(session_path) -> dict:
    """Formalised U1 analysis of a calibration session (``docs/reports/u1-calibration.md``).

    Checks, in the Android device frame (+x crown at 3 o'clock, +y 12 o'clock, +z out of screen):
    static |a| and gyro bias/noise on ``table_flat_face_up``; the six static poses on their
    expected signed axis; per-axis gyro-sign identifiability from single-axis flips on ``rot_*``.

    Returns a JSON-serialisable dict with ``gates`` (booleans) and ``passed``.
    """
    session = read_session(session_path)
    if session.kind != "calibration":
        raise ValueError(f"{session_path}: not a calibration session (kind={session.kind!r})")
    by_step = {s.meta["step"]: s for s in session.segments(kinds=["calibration"])}
    out: dict = {"session_id": session.session_id, "steps": sorted(by_step), "gates": {}}

    # 1. static magnitude, gyro bias and noise on the table
    if "table_flat_face_up" in by_step:
        t = by_step["table_flat_face_up"]
        acc = _trim(t.acc.xyz, 0.05) / GRAVITY
        gyr = np.degrees(_trim(t.gyr.xyz, 0.05))
        norm = float(np.linalg.norm(acc, axis=1).mean())
        bias = gyr.mean(0)
        out["static"] = {
            "abs_a_g": norm,
            "acc_mean_g": acc.mean(0).tolist(),
            "acc_std_mg": (acc.std(0) * 1000).tolist(),
            "gyro_bias_dps": bias.tolist(),
            "gyro_bias_norm_dps": float(np.linalg.norm(bias)),
            "gyro_noise_std_dps": gyr.std(0).tolist(),
        }
        out["gates"]["static_magnitude"] = bool(abs(norm - ACC_GATE_G) <= ACC_GATE_TOL)
        out["gates"]["gyro_bias"] = bool(np.linalg.norm(bias) < BIAS_GATE_DPS)
    else:
        out["gates"]["static_magnitude"] = None
        out["gates"]["gyro_bias"] = None

    # 2. six static poses
    poses = {}
    for step, (ax, sign) in POSES.items():
        if step not in by_step:
            continue
        m = (_trim(by_step[step].acc.xyz) / GRAVITY).mean(0)
        u = m / np.linalg.norm(m)
        dom = int(np.argmax(np.abs(m)))
        off = float(np.degrees(np.arccos(np.clip(sign * u[ax], -1, 1))))
        poses[step] = {
            "mean_g": m.tolist(),
            "abs_g": float(np.linalg.norm(m)),
            "expected": f"{'+' if sign > 0 else '-'}{'xyz'[ax]}",
            "ok": bool(dom == ax and np.sign(m[dom]) == sign and off <= OFF_AXIS_MAX_DEG),
            "off_axis_deg": off,
        }
    out["poses"] = poses
    out["gates"]["poses"] = bool(len(poses) == len(POSES) and all(p["ok"] for p in poses.values()))

    # 3. gyro sign: single-axis flip test on the rot_* steps
    sign_steps = {}
    per_axis = {a: {"identifiable": False, "sign_ok": True} for a in "xyz"}
    for step in ROT_STEPS:
        if step not in by_step:
            continue
        seg = by_step[step]
        w = seg.gyr.xyz
        e0 = _integrate_gravity_error(seg, w)
        flips = {}
        for i, a in enumerate("xyz"):
            wf = w.copy()
            wf[:, i] = -wf[:, i]
            flips[a] = _integrate_gravity_error(seg, wf)
        swing = float(
            np.degrees(
                np.arccos(
                    np.clip(
                        (seg.acc.xyz / np.linalg.norm(seg.acc.xyz, axis=1, keepdims=True))
                        @ (seg.acc.xyz[:5].mean(0) / np.linalg.norm(seg.acc.xyz[:5].mean(0))),
                        -1,
                        1,
                    )
                ).max()
            )
        )
        sign_steps[step] = {"err_as_is_deg": e0, "err_flipped_deg": flips, "swing_deg": swing}
        for a in "xyz":
            if abs(flips[a] - e0) >= SIGN_MARGIN_DEG:
                per_axis[a]["identifiable"] = True
                if flips[a] < e0:
                    per_axis[a]["sign_ok"] = False
    out["gyro_sign"] = {"steps": sign_steps, "axes": per_axis}
    ident = [a for a in "xyz" if per_axis[a]["identifiable"]]
    out["gyro_sign"]["identifiable_axes"] = ident
    out["gates"]["gyro_sign"] = bool(len(ident) >= 2 and all(per_axis[a]["sign_ok"] for a in ident))
    out["gates"]["flips_detectable"] = bool(len(ident) >= 2)
    out["passed"] = bool(all(v is True for v in out["gates"].values()))
    out["recording"] = _recording_summary(session)
    return out


def _recording_summary(session) -> dict:
    from workout_ml.sensors.android import recording_stats

    st = recording_stats(session)
    return {
        "rate_hz": st["acc"]["rate_hz"],
        "dt_p99_ms": st["acc"]["dt_p99_ms"],
        "gaps_gt_100ms": st["acc"]["gaps_gt_100ms"] + st["gyr"]["gaps_gt_100ms"],
        "clock_drift_ms": st["clock_drift_ms"],
        "acc_frac_above_1p9g": st["acc_frac_above_1p9g"],
    }


def session_quality(paths) -> list[dict]:
    """Per-session data-quality rows (rate, gaps, drift) for the report."""
    rows = []
    for p in paths:
        s = read_session(p)
        r = _recording_summary(s)
        r.update(session_id=s.session_id, kind=s.kind, duration_s=float(np.ptp(s.acc.t_ns)) / 1e9)
        rows.append(r)
    return rows


def calibration_dataset_check(dataset="watch-calib-v1", root=None, datasets_file=None) -> dict:
    """``calibration_check`` over every session of a dataset (``{"sessions": [...], "passed"}``)."""
    results = [calibration_check(p) for p in dataset_paths(dataset, root, datasets_file)]
    return {
        "dataset": dataset if isinstance(dataset, str) else "paths",
        "sessions": results,
        "passed": bool(results) and all(r["passed"] for r in results),
    }


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Watch calibration check (U1 analysis).")
    p.add_argument("--calibration-check", default="watch-calib-v1", help="dataset name or zip path")
    p.add_argument("--out", default=None, help="write the result as JSON")
    args = p.parse_args(argv)
    target = args.calibration_check
    dataset = [target] if target.endswith(".zip") else target
    result = calibration_dataset_check(dataset)
    text = json.dumps(result, indent=2)
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    print(text)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
