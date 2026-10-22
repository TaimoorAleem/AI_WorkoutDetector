"""Tests for the fake-watch generator (docs/sensor_spec.md sections 5 and 6)."""

import numpy as np
import pytest

from workout_ml.sensors import (
    SensorSet,
    load_profile,
    profile_with_rotation,
    random_rotation,
    simulate_android,
    to_csf,
)
from workout_ml.sensors.types import RawStream

T0_NS = 1_570_000_000_123 * 1_000_000
G = 9.80665


def make_canonical(seconds=60.0, acc_hz=12.5, gyr_hz=25.0, seed=0):
    """Canonical set: ~1 g gravity rotating with 0.25-0.8 Hz movement, plus gyro rotation."""
    rng = np.random.default_rng(seed)
    freqs = np.array([0.25, 0.5, 0.8])
    amps = np.array([0.5, 0.3, 0.15])  # rad of wrist rotation per axis
    phase = rng.uniform(0, 2 * np.pi, 3)
    g0 = np.array([0.2, 0.95, -0.2])
    g0 /= np.linalg.norm(g0)

    def rotvec(t):
        return amps * np.sin(2 * np.pi * freqs * t[:, None] + phase)

    def rotvec_rate(t):
        return amps * 2 * np.pi * freqs * np.cos(2 * np.pi * freqs * t[:, None] + phase)

    def acc_at(t):
        rv = rotvec(t)
        # small-angle rotation of gravity: g + rv x g, then renormalise to 1 g; plus linear accel
        a = g0 + np.cross(rv, g0)
        a /= np.linalg.norm(a, axis=1, keepdims=True)
        return a + 0.1 * np.sin(2 * np.pi * 0.6 * t[:, None] + phase)

    def gyr_at(t):
        return np.degrees(rotvec_rate(t))

    t_acc = np.arange(int(seconds * acc_hz)) / acc_hz
    t_gyr = np.arange(int(seconds * gyr_hz)) / gyr_hz
    acc = RawStream(
        "acc", T0_NS + (t_acc * 1e9).round().astype(np.int64), acc_at(t_acc), "g", "metamotion"
    )
    gyr = RawStream(
        "gyr", T0_NS + (t_gyr * 1e9).round().astype(np.int64), gyr_at(t_gyr), "deg/s", "metamotion"
    )
    return SensorSet(acc=acc, gyr=gyr, meta={"participant": "A", "label": "bench", "set": 1})


@pytest.fixture(scope="module")
def canonical():
    return make_canonical()


@pytest.fixture(scope="module")
def galaxy():
    return load_profile("galaxy-watch-prior-v0")


@pytest.fixture(scope="module")
def metamotion():
    return load_profile("metamotion-r-2019")


def test_random_rotation_is_proper_and_seeded():
    R1 = random_rotation(np.random.default_rng(1))
    R2 = random_rotation(np.random.default_rng(1))
    R3 = random_rotation(np.random.default_rng(2))
    np.testing.assert_array_equal(R1, R2)
    assert not np.allclose(R1, R3)
    for R in (R1, R3):
        np.testing.assert_allclose(R.T @ R, np.eye(3), atol=1e-12)
        assert np.linalg.det(R) == pytest.approx(1.0)


def test_profile_with_rotation(galaxy):
    R = random_rotation(np.random.default_rng(3))
    p = profile_with_rotation(galaxy, R)
    np.testing.assert_allclose(p.rotation, R)
    assert p.transform_id and p.transform_id != galaxy.transform_id
    assert p.profile_id != galaxy.profile_id
    assert p.acc == galaxy.acc and p.emulation == galaxy.emulation
    assert profile_with_rotation(galaxy, R, profile_id="x").profile_id == "x"
    # same R -> same transform_id; different R -> different
    assert profile_with_rotation(galaxy, R).transform_id == p.transform_id
    p2 = profile_with_rotation(galaxy, random_rotation(np.random.default_rng(4)))
    assert p2.transform_id != p.transform_id
    with pytest.raises(ValueError):
        profile_with_rotation(galaxy, np.diag([1.0, 1.0, -1.0]))


def test_output_units_frame_and_timestamps(canonical, galaxy):
    sim = simulate_android(canonical, galaxy, np.random.default_rng(0))
    assert sim.acc.unit == "m/s^2" and sim.gyr.unit == "rad/s"
    assert sim.acc.frame == sim.gyr.frame == "android"
    assert sim.meta["label"] == "bench" and sim.meta["simulated"] is True
    # 50 Hz, strictly increasing (RawStream validates), about 4x / 2x the source sample counts
    for stream, src in ((sim.acc, canonical.acc), (sim.gyr, canonical.gyr)):
        assert np.all(np.diff(stream.t_ns) > 0)
        assert (
            abs(len(stream) - (len(src) - 1) * (50.0 / (12.5 if src.sensor == "acc" else 25.0))) < 5
        )
    # units: |acc| is about 1 g = 9.8 m/s^2 (rotation preserves norms)
    assert np.linalg.norm(sim.acc.xyz, axis=1).mean() == pytest.approx(G, rel=0.1)


def test_jitter_within_bounds(canonical, galaxy):
    sim = simulate_android(
        canonical, galaxy, np.random.default_rng(0), jitter_ms=3.0, start_offset_ms=(0, 0)
    )
    for stream, src in ((sim.acc, canonical.acc), (sim.gyr, canonical.gyr)):
        k = np.arange(len(stream))
        dev_ms = (stream.t_ns - src.t_ns[0]) / 1e6 - k * 20.0
        assert np.abs(dev_ms).max() <= 0.4 * 20.0 + 1e-6  # clip at 0.4 period
        assert 1.5 < dev_ms.std() < 3.5  # about the requested sd
    nojit = simulate_android(
        canonical, galaxy, np.random.default_rng(0), jitter_ms=0.0, start_offset_ms=(0, 0)
    )
    np.testing.assert_array_equal(np.diff(nojit.acc.t_ns), 20_000_000)


def test_start_offsets_differ_and_are_bounded(canonical, galaxy):
    diffs = []
    for seed in range(5):
        sim = simulate_android(canonical, galaxy, np.random.default_rng(seed), jitter_ms=0.0)
        off_acc = (sim.acc.t_ns[0] - canonical.acc.t_ns[0]) / 1e6
        off_gyr = (sim.gyr.t_ns[0] - canonical.gyr.t_ns[0]) / 1e6
        assert 0 <= off_acc <= 20 and 0 <= off_gyr <= 20
        diffs.append(off_acc - off_gyr)
    assert all(abs(d) > 1e-6 for d in diffs)


def test_gaps_remove_samples(canonical, galaxy):
    gaps = [(10.0, 1000.0)]
    sim = simulate_android(canonical, galaxy, np.random.default_rng(0), gaps=gaps)
    base = simulate_android(canonical, galaxy, np.random.default_rng(0))
    start = min(base.acc.t_ns[0], base.gyr.t_ns[0])
    g0, g1 = start + 10_000_000_000, start + 11_000_000_000
    for s, b in ((sim.acc, base.acc), (sim.gyr, base.gyr)):
        assert not ((s.t_ns >= g0) & (s.t_ns < g1)).any()
        assert ((b.t_ns >= g0) & (b.t_ns < g1)).sum() >= 49
        assert len(s) == len(b) - ((b.t_ns >= g0) & (b.t_ns < g1)).sum()
    # no CSF bins inside the hole
    csf = to_csf(sim, galaxy)
    idx_ns = csf.index.values.astype("datetime64[ns]").astype(np.int64)
    inside = (idx_ns >= g0 + 200_000_000) & (idx_ns + 200_000_000 <= g1 - 200_000_000)
    assert not inside.any()


def test_determinism_and_seed_sensitivity(canonical, galaxy):
    a = simulate_android(canonical, galaxy, np.random.default_rng(7))
    b = simulate_android(canonical, galaxy, np.random.default_rng(7))
    c = simulate_android(canonical, galaxy, np.random.default_rng(8))
    np.testing.assert_array_equal(a.acc.xyz, b.acc.xyz)
    np.testing.assert_array_equal(a.gyr.t_ns, b.gyr.t_ns)
    assert not np.array_equal(a.acc.xyz, c.acc.xyz)


def test_gyro_bias_is_added(canonical, galaxy):
    base = simulate_android(canonical, galaxy, np.random.default_rng(0), noise_gyr_rads=0.0)
    biased = simulate_android(
        canonical, galaxy, np.random.default_rng(0), noise_gyr_rads=0.0, gyro_bias_rads=0.01
    )
    np.testing.assert_allclose(biased.gyr.xyz - base.gyr.xyz, 0.01, atol=1e-12)


def _rmse(a, b):
    ja = a.join(b, how="inner", lsuffix="_a", rsuffix="_b")
    out = {}
    for sensor in ("acc", "gyr"):
        d = np.concatenate(
            [(ja[f"{sensor}_{ax}_a"] - ja[f"{sensor}_{ax}_b"]).to_numpy() for ax in "xyz"]
        )
        out[sensor] = float(np.sqrt(np.mean(d**2)))
    return out, len(ja)


@pytest.mark.parametrize("seed", [0, 1, 2, 3, 4])
def test_signal_level_round_trip(canonical, galaxy, metamotion, seed):
    """Spec section 6: acc RMSE <= 0.03 g, gyro RMSE <= 3 deg/s over shared bins."""
    rng = np.random.default_rng(seed)
    P = profile_with_rotation(galaxy, random_rotation(rng))
    sim = simulate_android(canonical, P, rng)
    rt = to_csf(sim, P)
    ref = to_csf(canonical, metamotion)
    err, n_shared = _rmse(rt, ref)
    print(
        f"seed {seed}: shared bins {n_shared}/{len(ref)}  acc {err['acc']:.4f} g  gyr {err['gyr']:.3f} deg/s"
    )
    assert n_shared >= len(ref) - 3
    assert err["acc"] <= 0.03
    assert err["gyr"] <= 3.0
