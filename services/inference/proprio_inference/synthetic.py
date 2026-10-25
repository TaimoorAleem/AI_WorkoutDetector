"""Synthetic MetaMotion-like training data for a throwaway model (no real data, no DVC).

Used by ``train_model --synthetic`` so the local stack and CI can run end to end without the
MetaMotion dataset. A model trained on this is a *fixture*: it knows nothing about real lifting
(its metadata name is ``synthetic-fixture``). Mirrors the generator in ``ml/tests/watch_synth.py``.
"""

from __future__ import annotations

import numpy as np
import pandas as pd
from workout_ml.sensors import RawStream, SensorSet, load_profile, to_csf

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

SYNTHETIC_NAME = "synthetic-fixture"
SYNTHETIC_VERSION = "synthetic-1"
# One rotation copy keeps the fixture quick to train (about 30 s); quality is irrelevant here.
SYNTHETIC_AUGMENTATION = {"copies": 1, "max_deg": 30.0, "seed": 1}


def canonical_set(label: str, t0_ns: int, duration_s: float, rng) -> SensorSet:
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


def mm_cleaned(seed: int = 0, sets_per_label: int = 3, duration_s: float = 18.0) -> pd.DataFrame:
    """Synthetic 'cleaned MetaMotion' CSF frame (same columns as DataLoader + clean)."""
    rng = np.random.default_rng(seed)
    participants = ("A", "B")
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
