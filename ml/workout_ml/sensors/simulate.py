"""Fake-watch generator: canonical (MetaMotion-frame) SensorSet -> Android-style SensorSet.

Implements docs/sensor_spec.md section 5. Used by the signal-level round-trip test and the
model-level round-trip analysis; it is a test tool, not part of the production path.
"""

from __future__ import annotations

import dataclasses
from fractions import Fraction

import numpy as np
from scipy.signal import resample_poly
from scipy.spatial.transform import Rotation

from workout_ml.sensors.profiles import Profile, compute_transform_id, validate_rotation
from workout_ml.sensors.transforms import from_canonical
from workout_ml.sensors.types import RawStream, SensorSet

ANDROID_UNITS = {"acc": "m/s^2", "gyr": "rad/s"}


def random_rotation(rng: np.random.Generator) -> np.ndarray:
    """A proper 3x3 rotation, uniform on SO(3) (Haar), drawn from ``rng``."""
    seed = int(rng.integers(0, 2**32 - 1))
    return Rotation.random(random_state=seed).as_matrix()


def profile_with_rotation(base_profile: Profile, R, profile_id: str | None = None) -> Profile:
    """Copy of ``base_profile`` with rotation ``R`` (validated) and a recomputed transform_id.

    ``profile_id`` defaults to ``<base id>-rot-<transform_id[:8]>``.
    """
    R = validate_rotation(R)
    new = dataclasses.replace(base_profile, rotation=R, transform_id="")
    tid = compute_transform_id(new)
    pid = profile_id or f"{base_profile.profile_id}-rot-{tid[:8]}"
    new = dataclasses.replace(new, profile_id=pid)
    object.__setattr__(new, "transform_id", tid)
    return new


def _native_rate_hz(t_ns: np.ndarray) -> float:
    return round(1e9 / float(np.median(np.diff(t_ns))), 1)


def _upsample(xyz: np.ndarray, native_hz: float, rate_hz: float) -> np.ndarray:
    """Band-limited upsampling to ``rate_hz``; length is (n - 1) * ratio + 1 (no tail overshoot)."""
    ratio = Fraction(rate_hz / native_hz).limit_denominator(100)
    if ratio.numerator == ratio.denominator:
        return xyz.copy()
    # padtype="line" avoids the zero-padding edge transient on a signal with a large DC (gravity).
    up = resample_poly(xyz, ratio.numerator, ratio.denominator, axis=0, padtype="line")
    n_out = int(np.floor((len(xyz) - 1) * float(ratio) + 1e-9)) + 1
    return up[:n_out]


def _timestamps(
    t0_ns: int, n: int, rate_hz: float, jitter_ms: float, offset_ms: float, rng
) -> np.ndarray:
    period_ms = 1000.0 / rate_hz
    jitter = rng.normal(0.0, jitter_ms, size=n) if jitter_ms > 0 else np.zeros(n)
    jitter = np.clip(jitter, -0.4 * period_ms, 0.4 * period_ms)  # keeps t strictly increasing
    t_ms = offset_ms + np.arange(n) * period_ms + jitter
    return t0_ns + np.round(t_ms * 1e6).astype(np.int64)


def simulate_android(
    sensor_set_canonical: SensorSet,
    profile: Profile,
    rng: np.random.Generator,
    *,
    rate_hz: float = 50.0,
    jitter_ms: float = 3.0,
    noise_acc_ms2: float = 0.02,
    noise_gyr_rads: float = 0.002,
    gyro_bias_rads=0.0,
    start_offset_ms=(0.0, 20.0),
    gaps=None,
) -> SensorSet:
    """Render a canonical set as an Android device would record it.

    Args:
        sensor_set_canonical: acc in g and gyr in deg/s, MetaMotion frame (e.g. from
            ``read_metamotion``).
        profile: the simulated device's profile; ``profile.rotation`` is the device->canonical
            rotation that ``to_csf`` will later undo, and the unit scales define Android units.
        rng: source of all randomness (noise, jitter, offsets).
        rate_hz: nominal Android sampling rate for both sensors.
        jitter_ms: sd of per-sample timestamp jitter (clipped to +-0.4 period).
        noise_acc_ms2, noise_gyr_rads: white Gaussian noise sd, in Android units.
        gyro_bias_rads: constant gyro bias (scalar or 3-vector), rad/s.
        start_offset_ms: (low, high) range; acc and gyro each draw an independent start offset.
        gaps: optional ``[(start_s, dur_ms), ...]``; samples of both sensors whose timestamp
            falls in ``[set_start + start_s, ... + dur_ms)`` are removed.

    Timestamps are anchored to each source stream's first timestamp (so 200 ms bins line up
    with the source); values are the band-limited signal at the nominal times, timestamps carry
    the jitter and offset (a reporting-time error, as in batched Android sensor events).
    """
    lo, hi = start_offset_ms
    streams = {}
    for name, src in (("acc", sensor_set_canonical.acc), ("gyr", sensor_set_canonical.gyr)):
        if len(src) < 2:
            raise ValueError(f"{name} stream needs at least 2 samples")
        up = _upsample(src.xyz, _native_rate_hz(src.t_ns), rate_hz)  # canonical, g or deg/s
        dev = from_canonical(up, profile, name)  # device frame, Android units
        if name == "acc":
            dev = dev + rng.normal(0.0, noise_acc_ms2, size=dev.shape)
        else:
            dev = dev + rng.normal(0.0, noise_gyr_rads, size=dev.shape) + np.asarray(gyro_bias_rads)
        offset = float(rng.uniform(lo, hi)) if hi > lo else float(lo)
        t_ns = _timestamps(int(src.t_ns[0]), len(dev), rate_hz, jitter_ms, offset, rng)
        streams[name] = (t_ns, dev)

    if gaps:
        set_start = min(int(streams["acc"][0][0]), int(streams["gyr"][0][0]))
        for name, (t_ns, dev) in streams.items():
            keep = np.ones(len(t_ns), dtype=bool)
            for start_s, dur_ms in gaps:
                g0 = set_start + int(round(start_s * 1e9))
                g1 = g0 + int(round(dur_ms * 1e6))
                keep &= ~((t_ns >= g0) & (t_ns < g1))
            streams[name] = (t_ns[keep], dev[keep])

    out = {
        name: RawStream(sensor=name, t_ns=t_ns, xyz=dev, unit=ANDROID_UNITS[name], frame="android")
        for name, (t_ns, dev) in streams.items()
    }
    meta = dict(sensor_set_canonical.meta)
    meta["simulated"] = True
    meta["sim_profile_id"] = profile.profile_id
    return SensorSet(acc=out["acc"], gyr=out["gyr"], meta=meta)
