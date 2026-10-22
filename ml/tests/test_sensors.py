"""Tests for the sensors package (docs/sensor_spec.md section 6; synthetic data only)."""

import copy
import logging

import numpy as np
import pandas as pd
import pytest
import yaml

from workout_ml.sensors import RawStream, SensorSet, from_canonical, load_profile, to_csf
from workout_ml.sensors.profiles import PROFILES_DIR, profile_from_dict
from workout_ml.sensors.resample import (
    bin_mean,
    emulate_source_rate,
    nominal_rate_hz,
    segment_bounds,
)
from workout_ml.sensors.transforms import apply_rotation, apply_unit_scale, invert_unit_scale

G = 9.80665
MM = load_profile("metamotion-r-2019")
GW = load_profile("galaxy-watch-prior-v0")
BIN_NS = 200_000_000
# multiple of 200 ms since the epoch, so test bins line up with sample times
T0_NS = (1_547_476_800_000_000_000 // BIN_NS) * BIN_NS


def random_rotation(rng):
    q, r = np.linalg.qr(rng.normal(size=(3, 3)))
    q = q * np.sign(np.diag(r))
    if np.linalg.det(q) < 0:
        q[:, 0] = -q[:, 0]
    return q


def raw_dict(**overrides):
    d = yaml.safe_load((PROFILES_DIR / "galaxy-watch-prior-v0.yaml").read_text())
    d.update(overrides)
    return d


def stream(sensor, t_ns, xyz, unit=None, frame="metamotion"):
    unit = unit or ("g" if sensor == "acc" else "deg/s")
    return RawStream(sensor, np.asarray(t_ns, dtype=np.int64), np.asarray(xyz), unit, frame)


# ---------------------------------------------------------------- types


def test_rawstream_validation():
    t = np.arange(5, dtype=np.int64) * 80_000_000
    xyz = np.zeros((5, 3))
    assert len(RawStream("acc", t, xyz, "g", "metamotion")) == 5
    with pytest.raises(ValueError):
        RawStream("acc", t[::-1].copy(), xyz, "g", "metamotion")  # decreasing
    with pytest.raises(ValueError):
        RawStream("acc", np.array([0, 1, 1, 2, 3], dtype=np.int64), xyz, "g", "metamotion")
    with pytest.raises(ValueError):
        RawStream("acc", t[:4], xyz, "g", "metamotion")  # shape mismatch
    bad = xyz.copy()
    bad[2, 1] = np.nan
    with pytest.raises(ValueError):
        RawStream("acc", t, bad, "g", "metamotion")
    with pytest.raises(ValueError):
        RawStream("acc", t, xyz, "deg/s", "metamotion")  # unit/sensor mismatch
    with pytest.raises(ValueError):
        RawStream("gyr", t, xyz, "g", "metamotion")
    with pytest.raises(ValueError):
        RawStream("acc", t, xyz, "g", "ios")


# ---------------------------------------------------------------- units


def test_unit_round_trip_and_known_values():
    rng = np.random.default_rng(0)
    x = rng.normal(size=(50, 3)) * 10
    for profile, sensor in ((GW, "acc"), (GW, "gyr")):
        scale = profile.sensor_spec(sensor).unit_scale
        np.testing.assert_allclose(invert_unit_scale(apply_unit_scale(x, scale), scale), x)
    acc_scale = GW.acc.unit_scale
    np.testing.assert_allclose(apply_unit_scale(np.array([[G, 0, 0]]), acc_scale), [[1, 0, 0]])
    np.testing.assert_allclose(
        apply_unit_scale(np.array([[np.pi, 0, 0]]), GW.gyr.unit_scale), [[180, 0, 0]]
    )
    assert MM.acc.unit_scale == 1.0 and MM.gyr.unit_scale == 1.0


def test_to_csf_rejects_wrong_units():
    t = T0_NS + np.arange(10) * 80_000_000
    s = SensorSet(
        stream("acc", t, np.zeros((10, 3)), "m/s^2", "android"), stream("gyr", t, np.zeros((10, 3)))
    )
    with pytest.raises(ValueError):
        to_csf(s, MM)


# ---------------------------------------------------------------- rotation


def test_random_rotations_preserve_norms():
    rng = np.random.default_rng(1)
    x = rng.normal(size=(200, 3))
    for _ in range(10):
        R = random_rotation(rng)
        np.testing.assert_allclose(
            np.linalg.norm(apply_rotation(x, R), axis=1), np.linalg.norm(x, axis=1)
        )


def test_mirror_and_non_orthonormal_rotations_rejected():
    with pytest.raises(ValueError, match="det"):
        profile_from_dict(raw_dict(rotation=[[1, 0, 0], [0, 1, 0], [0, 0, -1]]))
    with pytest.raises(ValueError, match="orthonormal"):
        profile_from_dict(raw_dict(rotation=[[1.1, 0, 0], [0, 1, 0], [0, 0, 1]]))
    with pytest.raises(ValueError):
        apply_rotation(np.zeros((1, 3)), np.diag([1.0, 1.0, -1.0]))


def test_rotation_inverse_via_from_canonical():
    rng = np.random.default_rng(2)
    d = raw_dict(rotation=random_rotation(rng).tolist())
    prof = profile_from_dict(d)
    canon = rng.normal(size=(30, 3))
    for sensor in ("acc", "gyr"):
        dev = from_canonical(canon, prof, sensor)
        # forward steps 1-2 recover the canonical values
        fwd = apply_rotation(
            apply_unit_scale(dev, prof.sensor_spec(sensor).unit_scale), prof.rotation
        )
        np.testing.assert_allclose(fwd, canon, atol=1e-12)


def test_acc_gyro_rotation_consistency():
    """A rigid body spinning at omega: dg/dt = -omega x g holds in every (rotated) frame."""
    rng = np.random.default_rng(3)
    omega = np.array([0.3, -0.5, 0.8])  # rad/s, device frame
    g0 = np.array([0.2, 0.9, -0.3])
    dt, n = 1e-3, 2000
    t = np.arange(n) * dt

    def rodrigues(axis, angle):
        k = axis / np.linalg.norm(axis)
        K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
        return np.eye(3) + np.sin(angle) * K + (1 - np.cos(angle)) * (K @ K)

    # body frame: gravity vector rotates by -omega t
    acc = np.array([rodrigues(omega, -np.linalg.norm(omega) * ti) @ g0 for ti in t])
    gyr = np.tile(omega, (n, 1))
    R = random_rotation(rng)
    acc_c, gyr_c = apply_rotation(acc, R), apply_rotation(gyr, R)
    dgdt = np.gradient(acc_c, dt, axis=0)
    pred = -np.cross(gyr_c, acc_c)
    np.testing.assert_allclose(dgdt[5:-5], pred[5:-5], atol=1e-5)


# ---------------------------------------------------------------- emulation


def _jittered_times(n_sec, rate, jitter_ms, rng):
    n = int(n_sec * rate)
    period = 1e9 / rate
    jit = np.clip(rng.normal(0, jitter_ms * 1e6, n), -0.4 * period, 0.4 * period)
    return (np.arange(n) * period + jit).astype(np.int64)


def _peak_freq(x, fs):
    n = 2**18
    spec = np.abs(np.fft.rfft((x - x.mean()) * np.hanning(len(x)), n=n))
    return np.fft.rfftfreq(n, 1 / fs)[spec.argmax()]


def test_emulation_keeps_frequency_and_amplitude():
    rng = np.random.default_rng(4)
    t = _jittered_times(60, 50.0, 3.0, rng)
    x = np.zeros((len(t), 3))
    x[:, 0] = 1.5 * np.sin(2 * np.pi * 0.5 * t / 1e9)
    t_out, y = emulate_source_rate(t, x, 12.5)
    assert abs(nominal_rate_hz(t) - 50.0) < 0.5  # median of jittered diffs, 0.1 Hz grid
    assert abs(np.median(np.diff(t_out)) / 1e9 - 0.08) < 1e-6
    assert abs(_peak_freq(y[:, 0], 12.5) - 0.5) / 0.5 < 0.02
    amp = np.std(y[:, 0]) * np.sqrt(2)
    assert abs(amp - 1.5) / 1.5 < 0.05


def test_emulation_antialias_attenuates_high_frequencies():
    rng = np.random.default_rng(5)
    t = _jittered_times(60, 50.0, 3.0, rng)

    def amplitude_out(freq):
        x = np.zeros((len(t), 3))
        x[:, 0] = np.sin(2 * np.pi * freq * t / 1e9)
        _, y = emulate_source_rate(t, x, 12.5)
        return np.std(y[:, 0]) * np.sqrt(2)

    db = lambda a: 20 * np.log10(a)  # noqa: E731
    # Order-4 Butterworth (filtfilt) at fc = 0.8 * 6.25 = 5 Hz gives -14.5 dB at 6 Hz
    # (the spec's ">= 20 dB at 6 Hz" is not reachable with these parameters) and -24 dB at 7 Hz.
    assert db(amplitude_out(6.0)) <= -14.0
    assert db(amplitude_out(7.0)) <= -20.0
    assert db(amplitude_out(1.0)) > -0.5  # pass band untouched


def test_gap_splits_segments_and_leaves_hole_empty(caplog):
    rng = np.random.default_rng(6)
    t_all = T0_NS + np.arange(0, 3_000_000_000, 20_000_000, dtype=np.int64)
    hole = (t_all >= T0_NS + 1_000_000_000) & (t_all < T0_NS + 2_000_000_000)
    t = t_all[~hole]
    assert len(segment_bounds(t, 500)) == 2

    def mk(sensor, hz_step):
        tt = t[::hz_step]
        return stream(
            sensor,
            tt,
            rng.normal(size=(len(tt), 3)),
            "m/s^2" if sensor == "acc" else "rad/s",
            "android",
        )

    df = to_csf(SensorSet(mk("acc", 1), mk("gyr", 1)), GW)
    left_s = (df.index - pd.Timestamp(T0_NS, unit="ns")) / pd.Timedelta(seconds=1)
    inside = (left_s > 1.0 + 1e-9) & (left_s + 0.2 <= 2.0 + 1e-9)
    assert len(df) > 0 and not inside.any()
    assert (left_s < 1.0 + 1e-9).any() and (left_s >= 2.0 - 1e-9).any()


def test_too_short_segment_dropped_with_warning(caplog):
    t = T0_NS + np.concatenate(
        [np.arange(0, 1_000_000_000, 20_000_000), [2_000_000_000, 2_020_000_000]]
    )
    x = np.ones((len(t), 3))
    with caplog.at_level(logging.WARNING, logger="workout_ml.sensors.resample"):
        t_out, _ = emulate_source_rate(t.astype(np.int64), x, 12.5)
    assert any("dropping" in r.message for r in caplog.records)
    assert t_out.max() < T0_NS + 1_100_000_000


# ---------------------------------------------------------------- binning


def test_bins_are_epoch_aligned_means_and_need_both_sensors():
    # acc: 3 samples in bin 0, 2 in bin 1, 1 in bin 3. gyr: bin 0, bin 1, bin 2 (none in bin 3)
    acc_t = T0_NS + np.array([10, 50, 150, 210, 390, 650]) * 1_000_000
    acc_x = np.array([[1.0, 2, 3], [2, 3, 4], [6, 7, 8], [1, 1, 1], [3, 3, 3], [9, 9, 9]])
    gyr_t = T0_NS + np.array([20, 220, 430]) * 1_000_000
    gyr_x = np.array([[10.0, 0, 0], [20, 0, 0], [30, 0, 0]])
    df = to_csf(SensorSet(stream("acc", acc_t, acc_x), stream("gyr", gyr_t, gyr_x)), MM)
    assert list(df.columns) == ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]
    assert list(df.index) == [
        pd.Timestamp(T0_NS + 0, unit="ns"),
        pd.Timestamp(T0_NS + BIN_NS, unit="ns"),
    ]
    assert all(i.value % BIN_NS == 0 for i in df.index)
    np.testing.assert_allclose(df.iloc[0][["acc_x", "acc_y", "acc_z"]], [3.0, 4.0, 5.0])
    np.testing.assert_allclose(df.iloc[1][["acc_x", "gyr_x"]], [2.0, 20.0])
    assert df.attrs["csf"]["transform_id"] == MM.transform_id
    assert df.attrs["csf"]["profile_id"] == "metamotion-r-2019"
    assert df.attrs["csf"]["csf_version"] == 1


def test_bin_mean_empty():
    out = bin_mean(np.empty(0, dtype=np.int64), np.empty((0, 3)), "acc")
    assert len(out) == 0 and list(out.columns) == ["acc_x", "acc_y", "acc_z"]


# ---------------------------------------------------------------- profiles


def test_profiles_load_and_transform_id_is_stable():
    for pid in ("metamotion-r-2019", "galaxy-watch-prior-v0"):
        a, b = load_profile(pid), load_profile(PROFILES_DIR / f"{pid}.yaml")
        assert (
            a.profile_id == pid and a.transform_id == b.transform_id and len(a.transform_id) == 12
        )
    assert MM.transform_id != GW.transform_id
    assert MM.rotation_status == "identity" and GW.rotation_status == "prior"
    assert not MM.emulation.enabled and GW.emulation.enabled
    # key order in the YAML must not matter
    d = raw_dict()
    assert profile_from_dict(dict(reversed(list(d.items())))).transform_id == GW.transform_id
    with pytest.raises(FileNotFoundError):
        load_profile("no-such-profile")


def test_changing_a_number_changes_transform_id():
    base = GW.transform_id
    variants = []
    d = raw_dict()
    d["acc"] = {"source_unit": "m/s^2", "unit_scale": 0.1019}
    variants.append(d)
    variants.append(raw_dict(rotation=[[0, 1, 0], [-1, 0, 0], [0, 0, 1]]))
    d = raw_dict()
    d["emulation"] = {**d["emulation"], "anti_alias_order": 3}
    variants.append(d)
    variants.append(raw_dict(clip={"acc_g": 8.0}))
    for v in variants:
        assert profile_from_dict(copy.deepcopy(v)).transform_id != base
    # non-numeric fields do not affect the id
    d = raw_dict()
    d["device"] = {**d["device"], "model": "Watch7"}
    assert profile_from_dict(d).transform_id == base


def test_profile_validation_errors():
    for bad in (
        raw_dict(csf_version=2),
        raw_dict(rotation_status="guess"),
        raw_dict(clip={"acc_g": -1}),
    ):
        with pytest.raises(ValueError):
            profile_from_dict(bad)
    d = raw_dict()
    del d["rotation"]
    with pytest.raises(ValueError):
        profile_from_dict(d)


def test_clip_applies_to_accelerometer_only():
    prof = profile_from_dict(raw_dict(rotation=np.eye(3).tolist(), clip={"acc_g": 1.0}))
    t = T0_NS + np.arange(0, 400_000_000, 20_000_000, dtype=np.int64)
    big = np.full((len(t), 3), 5 * G)
    s = SensorSet(
        stream("acc", t, big, "m/s^2", "android"), stream("gyr", t, big, "rad/s", "android")
    )
    df = to_csf(s, prof)
    assert np.allclose(df[["acc_x", "acc_y", "acc_z"]], 1.0)
    assert df["gyr_x"].abs().max() > 100


# ---------------------------------------------------------------- MetaMotion regression


def _old_merge_sensors(acc_df, gyr_df):
    """Frozen copy of the pre-S1 DataLoader.merge_sensors."""
    names = [
        "acc_x",
        "acc_y",
        "acc_z",
        "gyr_x",
        "gyr_y",
        "gyr_z",
        "participant",
        "label",
        "category",
        "set",
    ]
    acc_by_set = {k: g for k, g in acc_df.groupby("set")}
    merged = []
    for set_id, gyr in gyr_df.groupby("set"):
        m = pd.concat([acc_by_set[set_id].iloc[:, :3], gyr], axis=1, sort=True)
        m.columns = names
        merged.append(m)
    return pd.concat(merged).sort_index(kind="stable")


def _old_resample_data(df, sampling_rule="200ms"):
    """Frozen copy of the pre-S1 DataLoader.resample_data."""
    sampling_dict = {c: "mean" for c in ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]}
    sampling_dict.update(
        {"participant": "last", "label": "last", "category": "last", "set": "last"}
    )
    sampling_dict = {k: v for k, v in sampling_dict.items() if k in df.columns}
    days = [g for n, g in df.groupby(pd.Grouper(freq="D"))]
    out = pd.concat([day.resample(sampling_rule).apply(sampling_dict).dropna() for day in days])
    out["set"] = out["set"].astype("int")
    return out


def _old_read(path, participant, label, category, set_id):
    df = pd.read_csv(path)
    df["participant"], df["label"], df["category"], df["set"] = participant, label, category, set_id
    df.index = pd.to_datetime(df["epoch (ms)"], unit="ms")
    return df.drop(columns=["epoch (ms)", "time (01:00)", "elapsed (s)"])


def _write_csv(path, header, times_ms, rng):
    rows = [header]
    for t in times_ms:
        v = rng.normal(size=3).tolist()
        rows.append(f"{t},x,0,{v[0]!r},{v[1]!r},{v[2]!r}")
    path.write_text("\n".join(rows) + "\n")


ACC_H = "epoch (ms),time (01:00),elapsed (s),x-axis (g),y-axis (g),z-axis (g)"
GYR_H = "epoch (ms),time (01:00),elapsed (s),x-axis (deg/s),y-axis (deg/s),z-axis (deg/s)"


def test_metamotion_path_matches_legacy_merge_and_resample(tmp_path):
    from workout_ml.sensors import read_metamotion

    rng = np.random.default_rng(7)
    # set 1 crosses UTC midnight and has a 3 s hole; set 2 is on another day
    midnight = int(pd.Timestamp("2019-01-15").value // 1_000_000)
    specs = [
        ("A", "bench", "heavy", midnight - 7_123),
        ("B", "squat", "medium", midnight + 86_400_000 + 12_345),
    ]
    old_acc, old_gyr, new_frames = [], [], []
    for k, (part, label, cat, t0) in enumerate(specs, start=1):
        acc_t = t0 + np.arange(0, 30_000, 80)
        gyr_t = t0 + np.arange(0, 30_000, 40)
        if k == 1:
            acc_t = acc_t[(acc_t < t0 + 10_000) | (acc_t >= t0 + 13_000)]
            gyr_t = gyr_t[(gyr_t < t0 + 10_000) | (gyr_t >= t0 + 13_000)]
        pa, pg = tmp_path / f"{k}_acc.csv", tmp_path / f"{k}_gyr.csv"
        _write_csv(pa, ACC_H, acc_t, rng)
        _write_csv(pg, GYR_H, gyr_t, rng)
        old_acc.append(_old_read(pa, part, label, cat, k))
        old_gyr.append(_old_read(pg, part, label, cat, k))
        df = to_csf(read_metamotion(pa, pg, {"set": k}), MM)
        for col, val in (("participant", part), ("label", label), ("category", cat), ("set", k)):
            df[col] = val
        new_frames.append(df)

    expected = _old_resample_data(_old_merge_sensors(pd.concat(old_acc), pd.concat(old_gyr)))
    got = pd.concat(new_frames).sort_index(kind="stable")
    expected.index.name = None
    pd.testing.assert_frame_equal(
        got, expected, check_exact=False, rtol=1e-12, check_freq=False, check_index_type=False
    )
    assert len(got) > 100
