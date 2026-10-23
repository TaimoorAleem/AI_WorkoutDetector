"""Synthetic fixtures for the watch harness tests: MetaMotion-like sets, Raw v1 zips with a
known watch->MetaMotion rotation, and synthetic calibration sessions. No real data."""

from __future__ import annotations

import io
import json
import zipfile
from pathlib import Path

import numpy as np
import pandas as pd
from scipy.spatial.transform import Rotation

from workout_ml.sensors import (
    RawStream,
    SensorSet,
    load_profile,
    profile_with_rotation,
    simulate_android,
    to_csf,
)

G = 9.80665
# PROJECT.md 6.1 per-exercise mean gravity vectors (MetaMotion frame, g)
GRAVITY = {
    "bench": (-0.11, 0.94, -0.17),
    "dead": (0.04, -1.02, -0.02),
    "ohp": (-0.24, 0.92, -0.12),
    "rest": (0.52, -0.50, 0.30),
    "row": (0.05, -1.02, 0.04),
    "squat": (0.17, 0.66, 0.66),
}
LIFTS = ["bench", "squat", "dead", "ohp", "row"]
T0_UNIX = 1_750_000_000_000_000_000
ELAPSED0 = 1_000_000_000_000


def known_rotation(angle_deg=40.0, seed=3) -> np.ndarray:
    """A fixed proper rotation (watch -> MetaMotion) away from the prior."""
    rng = np.random.default_rng(seed)
    axis = rng.normal(size=3)
    axis /= np.linalg.norm(axis)
    return Rotation.from_rotvec(axis * np.deg2rad(angle_deg)).as_matrix()


def canonical_set(label, t0_ns, duration_s, rng) -> SensorSet:
    """A MetaMotion-frame set (acc 12.5 Hz in g, gyr 25 Hz in deg/s)."""
    amp = np.random.default_rng(sum(map(ord, label)))
    a_amp = amp.uniform(0.05, 0.5, 3)
    g_amp = amp.uniform(5, 80, 3)
    f = 0.35 + 0.05 * (sum(map(ord, label)) % 5)
    phase = rng.uniform(0, 2 * np.pi, 3)
    streams = {}
    for sensor, hz in (("acc", 12.5), ("gyr", 25.0)):
        n = int(duration_s * hz)
        t = np.arange(n) / hz
        base = np.array(GRAVITY[label]) if sensor == "acc" else np.zeros(3)
        amp_v = a_amp if sensor == "acc" else g_amp
        xyz = base + amp_v * np.sin(2 * np.pi * f * t[:, None] + phase)
        xyz += rng.normal(0, 0.01 if sensor == "acc" else 1.0, xyz.shape)
        streams[sensor] = RawStream(
            sensor,
            t0_ns + np.round(t * 1e9).astype(np.int64),
            xyz,
            "g" if sensor == "acc" else "deg/s",
            "metamotion",
        )
    return SensorSet(acc=streams["acc"], gyr=streams["gyr"], meta={"label": label})


def mm_cleaned(rng, sets_per_label=4, duration_s=20.0, participants=("A", "B")) -> pd.DataFrame:
    """Synthetic 'cleaned MetaMotion' CSF frame (same columns as DataLoader + clean)."""
    prof = load_profile("metamotion-r-2019")
    frames, sid = [], 0
    for label in [*LIFTS, "rest"]:
        for k in range(sets_per_label):
            sid += 1
            df = to_csf(canonical_set(label, T0_UNIX + sid * 10**12, duration_s, rng), prof)
            df["participant"] = participants[k % len(participants)]
            df["label"] = label
            df["category"] = "heavy" if k % 2 == 0 else "medium"
            df["set"] = sid
            frames.append(df)
    out = pd.concat(frames).sort_index(kind="stable")
    out.index.name = "epoch (ms)"
    return out


# --------------------------------------------------------------------------- Raw v1 zips


def _csv(t_ns, xyz) -> bytes:
    buf = io.StringIO()
    buf.write("t_ns,x,y,z\n")
    for t, (x, y, z) in zip(t_ns, xyz):
        buf.write(f"{int(t)},{x:.7g},{y:.7g},{z:.7g}\n")
    return buf.getvalue().encode()


def _meta(session_id, kind, t_first, t_last, n_acc, n_gyr, model="SM-R920") -> dict:
    sensor = {
        "type": 1, "name": "s", "vendor": "v", "version": 1, "resolution": 0.01,
        "maximum_range": 78.0, "min_delay_us": 2000, "max_delay_us": 500000, "fifo_reserved": 0,
        "fifo_max": 0, "requested_period_us": 20000, "max_report_latency_us": 0,
        "is_wake_up": False,
    }  # fmt: skip
    dur = t_last - t_first
    return {
        "raw_format": 1,
        "session_id": session_id,
        "kind": kind,
        "complete": True,
        "app": {"package": "dev.proprio.wear", "version_name": "0", "version_code": 1},
        "device": {
            "manufacturer": "samsung",
            "model": model,
            "product": "p",
            "sdk_int": 34,
            "release": "14",
            "fingerprint": "f",
        },  # fmt: skip
        "wrist": "left",
        "clock_anchor_start": {"elapsed_realtime_ns": ELAPSED0, "unix_time_ns": T0_UNIX},
        "clock_anchor_end": {
            "elapsed_realtime_ns": ELAPSED0 + dur,
            "unix_time_ns": T0_UNIX + dur,
        },
        "sensors": {"acc": dict(sensor, type=1), "gyr": dict(sensor, type=4)},
        "sample_counts": {"acc": n_acc, "gyr": n_gyr},
        "started_at_utc": "2025-06-15T15:06:40Z",
        "ended_at_utc": "2025-06-15T15:10:40Z",
    }


def _to_elapsed(t_unix_ns):
    return np.asarray(t_unix_ns, dtype=np.int64) - T0_UNIX + ELAPSED0


def _write_zip(path, meta, acc, gyr, marks, labels=None) -> Path:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    seg = "segment_id,t_start_ns,t_end_ns,source,step\n" + "".join(
        f"{sid},{a},{b},watch,{step or ''}\n" for sid, a, b, step in marks
    )
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        zf.writestr("meta.json", json.dumps(meta))
        zf.writestr("acc.csv", _csv(*acc))
        zf.writestr("gyr.csv", _csv(*gyr))
        zf.writestr("segments.csv", seg)
        if labels is not None:
            zf.writestr("labels.json", json.dumps(labels))
    return path


def gym_zip(
    path,
    R_true,
    rng,
    *,
    session_id="aaaaaaaa-0000-4000-8000-000000000001",
    lifts=("bench", "squat", "dead", "ohp", "row"),
    sets_per_lift=2,
    set_s=16.0,
    rest_s=14.0,
    with_other=True,
    reps_for=lambda label, k: 5 if k % 2 == 0 else 10,
    model="SM-R920",
) -> Path:
    """A gym session as the watch would record it: simulate_android with known ``R_true``."""
    prior = load_profile("galaxy-watch-prior-v0")
    dev_profile = profile_with_rotation(prior, R_true)
    acc_t, acc_x, gyr_t, gyr_x = [], [], [], []
    marks, label_segs = [], []
    t = T0_UNIX + 5 * 10**9
    sid = 0
    order = [(lift, k) for k in range(sets_per_lift) for lift in lifts]
    for i, (lift, k) in enumerate(order):
        if i > 0:  # gap: rest data, or an 'other' segment followed by rest
            if with_other and i == 2:
                sid += 1
                _add(
                    canonical_set("rest", t, 8.0, rng), dev_profile, rng, acc_t, acc_x, gyr_t, gyr_x
                )
                marks.append((sid, int(_to_elapsed(t)), int(_to_elapsed(t + int(8e9))), None))
                label_segs.append({"segment_id": sid, "kind": "other", "note": "walking"})
                t += int(8e9)
            _add(
                canonical_set("rest", t, rest_s, rng), dev_profile, rng, acc_t, acc_x, gyr_t, gyr_x
            )
            t += int(rest_s * 1e9)
        sid += 1
        _add(canonical_set(lift, t, set_s, rng), dev_profile, rng, acc_t, acc_x, gyr_t, gyr_x)
        marks.append((sid, int(_to_elapsed(t)), int(_to_elapsed(t + int(set_s * 1e9))), None))
        reps = reps_for(lift, k)
        label_segs.append(
            {
                "segment_id": sid, "kind": "set", "exercise": lift, "load_kg": 50.0,
                "category": "heavy" if reps <= 5 else "medium", "reps_user": reps, "rpe": 7,
                "note": "",
            }
        )  # fmt: skip
        t += int(set_s * 1e9)
    acc = (np.concatenate(acc_t), np.concatenate(acc_x))
    gyr = (np.concatenate(gyr_t), np.concatenate(gyr_x))
    acc = (_to_elapsed(acc[0]), acc[1])
    gyr = (_to_elapsed(gyr[0]), gyr[1])
    meta = _meta(
        session_id, "gym", int(acc[0][0]), int(acc[0][-1]), len(acc[0]), len(gyr[0]), model
    )
    labels = {
        "labels_format": 1,
        "session_id": session_id,
        "participant_id": "P01",
        "consent": {"training_use": True, "recorded_at_utc": "2025-06-15T15:05:00Z"},
        "segments": label_segs,
    }
    return _write_zip(path, meta, acc, gyr, marks, labels)


def _add(canon, dev_profile, rng, acc_t, acc_x, gyr_t, gyr_x):
    ss = simulate_android(canon, dev_profile, rng, rate_hz=50.0)
    acc_t.append(ss.acc.t_ns)
    acc_x.append(ss.acc.xyz)
    gyr_t.append(ss.gyr.t_ns)
    gyr_x.append(ss.gyr.xyz)


# --------------------------------------------------------------------------- calibration


def calibration_zip(
    path, *, flip_gyro_axis=None, acc_scale=1.0, session_id="cccccccc-0000-4000-8000-000000000002"
) -> Path:
    """A synthetic calibration session in the Android device frame (known units and signs).

    ``flip_gyro_axis`` ('x'|'y'|'z') negates that gyro axis (a sign bug); ``acc_scale`` scales
    the accelerometer (a units bug).
    """
    rng = np.random.default_rng(1)
    hz = 50.0
    up = {
        "table_flat_face_up": (2, 1), "face_up": (2, 1), "face_down": (2, -1),
        "buttons_up": (0, 1), "buttons_down": (0, -1), "twelve_up": (1, 1), "twelve_down": (1, -1),
    }  # fmt: skip
    steps = [(s, 20.0 if s.startswith("table") else 10.0) for s in up]
    steps += [("rot_x", 10.0), ("rot_y", 10.0), ("rot_z", 10.0)]
    t0 = 0
    acc_t, acc_x, gyr_x, marks = [], [], [], []
    for sid, (name, dur) in enumerate(steps, start=1):
        n = int(dur * hz)
        tt = np.arange(n) / hz
        if name in up:
            ax, sg = up[name]
            g0 = np.zeros(3)
            g0[ax] = sg
            acc = np.tile(g0, (n, 1)) * G
            gyr = np.zeros((n, 3))
        else:
            axis = np.eye(3)["xyz".index(name[-1])]
            rate = np.deg2rad(9.0)  # 90 deg in 10 s
            g0 = np.array([0.0, 0.0, 1.0])  # starts face up
            rots = Rotation.from_rotvec(axis[None, :] * (rate * tt)[:, None])
            acc = rots.inv().apply(g0) * G
            gyr = np.tile(axis * rate, (n, 1))
        acc = acc * acc_scale + rng.normal(0, 0.03, acc.shape)
        gyr = gyr + rng.normal(0, 0.002, gyr.shape)
        if flip_gyro_axis:
            gyr[:, "xyz".index(flip_gyro_axis)] *= -1
        t_ns = (t0 + np.round(tt * 1e9)).astype(np.int64)
        acc_t.append(t_ns)
        acc_x.append(acc)
        gyr_x.append(gyr)
        marks.append((sid, ELAPSED0 + int(t_ns[0]), ELAPSED0 + int(t_ns[-1]) + int(1e9 / hz), name))
        t0 = int(t_ns[-1]) + int(3e9)
    t_all = np.concatenate(acc_t) + ELAPSED0
    meta = _meta(session_id, "calibration", int(t_all[0]), int(t_all[-1]), len(t_all), len(t_all))
    return _write_zip(
        path, meta, (t_all, np.concatenate(acc_x)), (t_all, np.concatenate(gyr_x)), marks
    )
