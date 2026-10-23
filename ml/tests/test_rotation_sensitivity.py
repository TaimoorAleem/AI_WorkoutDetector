"""Fast synthetic tests for the rotation-sensitivity analysis."""

import numpy as np
import pandas as pd

from workout_ml import config
from workout_ml.analysis import rotation_sensitivity as rs

SENSORS = ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]


def _synthetic(n=60, n_sets=2, seed=0):
    rng = np.random.default_rng(seed)
    frames = []
    for s in range(n_sets):
        df = pd.DataFrame(rng.normal(size=(n, 6)), columns=SENSORS)
        df["acc_y"] += 1.0
        df["participant"] = "A"
        df["label"] = "bench"
        df["category"] = "heavy"
        df["set"] = f"set{s}"
        df.index = pd.date_range("2019-01-01", periods=n, freq="200ms")
        frames.append(df)
    return pd.concat(frames)


def test_rotation_preserves_norms():
    df = _synthetic()
    R = rs.rotation_matrix([1, 2, 3], 20)
    out = rs.rotate_sensor_columns(df, R)
    for cols in (rs.ACC_COLS, rs.GYR_COLS):
        np.testing.assert_allclose(
            np.linalg.norm(out[cols].to_numpy(), axis=1),
            np.linalg.norm(df[cols].to_numpy(), axis=1),
            atol=1e-12,
        )
    assert not np.allclose(out[SENSORS].to_numpy(), df[SENSORS].to_numpy())


def test_zero_rotation_is_identity():
    df = _synthetic()
    out = rs.rotate_sensor_columns(df, rs.rotation_matrix([0, 1, 0], 0))
    np.testing.assert_allclose(out[SENSORS].to_numpy(), df[SENSORS].to_numpy(), atol=1e-15)
    theta0 = [c for c in rs.build_conditions() if c[0] == 0]
    assert len(theta0) == 1 and np.allclose(theta0[0][2], np.eye(3))


def test_conditions_layout_and_determinism():
    conds = rs.build_conditions()
    assert len(conds) == 1 + 6 * (6 + rs.N_RANDOM_AXES)
    again = rs.build_conditions()
    for a, b in zip(conds, again, strict=True):
        np.testing.assert_array_equal(a[2], b[2])
    # rotation angle encoded in R matches theta
    for theta, _, R in conds:
        assert np.isclose(np.degrees(np.arccos((np.trace(R) - 1) / 2)), theta, atol=1e-6)


def test_magnitude_features_are_rotation_invariant():
    df = _synthetic()
    R = rs.rotation_matrix([0.3, -1, 0.5], 30)
    base = rs.engineer(df)
    rotated = rs.engineer(rs.rotate_sensor_columns(df, R))
    cols = rs.magnitude_columns(base.columns)
    assert len(cols) == 2 + 2 * 2 * 4
    np.testing.assert_allclose(rotated[cols].to_numpy(), base[cols].to_numpy(), atol=1e-9)
    # while the raw-axis features do change
    assert not np.allclose(rotated["acc_x"].to_numpy(), base["acc_x"].to_numpy())
    assert config.FEATURE_CONFIG["rolling_windows"] == [5, 10]


def test_random_set_rotation_stays_within_angle():
    df = _synthetic(n_sets=3)
    out = rs.rotate_sets_randomly(df, np.random.default_rng(1), max_deg=15.0)
    for _, rows in df.groupby("set").indices.items():
        a = df[rs.ACC_COLS].to_numpy()[rows]
        b = out[rs.ACC_COLS].to_numpy()[rows]
        np.testing.assert_allclose(np.linalg.norm(a, axis=1), np.linalg.norm(b, axis=1), atol=1e-12)


def test_kabsch_residual():
    assert np.isclose(rs.kabsch_residual_g(0), 0.0)
    assert np.isclose(rs.kabsch_residual_g(8.6), 0.15, atol=1e-3)


def _table(rows):
    return pd.DataFrame(
        rows, columns=["variant", "theta", "axis", "set_acc", "recall_bench", "recall_ohp"]
    )


def test_theta_tol_hand_made_table():
    base = ("m", 0, "none", 0.96, 0.80, 0.70)
    rows = [
        base,
        ("m", 2, "+x", 0.95, 0.78, 0.69),  # ok
        ("m", 2, "+y", 0.96, 0.80, 0.70),  # ok
        ("m", 5, "+x", 0.94, 0.76, 0.66),  # drops 2.0 pp / 4 pp / 4 pp: still ok
        ("m", 5, "+y", 0.95, 0.80, 0.70),
        ("m", 10, "+x", 0.96, 0.80, 0.70),  # fine on x...
        ("m", 10, "+y", 0.95, 0.74, 0.70),  # ...but bench drops 6 pp on y: fail
        ("m", 15, "+x", 0.90, 0.80, 0.70),  # set acc fails
    ]
    t = _table(rows)
    assert rs.theta_tol(t, "m") == 5.0
    # restricting to +x only: 10 deg passes, 15 fails
    assert rs.theta_tol(t, "m", axes={"+x"}) == 10.0
    # nothing passes beyond theta=0 -> 0
    bad = _table([base, ("m", 2, "+x", 0.90, 0.80, 0.70)])
    assert rs.theta_tol(bad, "m") == 0.0
    # OHP recall criterion
    ohp = _table([base, ("m", 2, "+x", 0.96, 0.80, 0.64)])
    assert rs.theta_tol(ohp, "m") == 0.0


def test_aggregate_and_theta_tol_are_keyed_by_model():
    def fold(model, theta, axis, acc, bench, ohp):
        return {
            "model": model, "variant": "baseline", "theta": theta, "axis": axis,
            "test_participant": "A", "set_acc": acc, "window_macro_f1": acc,
            "window_acc": acc, "recall_bench": bench, "recall_ohp": ohp,
            "n_bench": 10, "n_ohp": 10, "bench_to_ohp": 0, "ohp_to_bench": 0,
        }  # fmt: skip

    folds = pd.DataFrame(
        [
            fold("NN", 0, "none", 0.96, 0.8, 0.7),
            fold("NN", 2, "+x", 0.50, 0.1, 0.7),
            fold("KNN", 0, "none", 0.96, 0.8, 0.7),
            fold("KNN", 2, "+x", 0.96, 0.8, 0.7),
        ]
    )
    table = rs.aggregate(folds)
    assert rs.combos_in(table) == [("NN", "baseline"), ("KNN", "baseline")]
    assert rs.theta_tol(table, "baseline", model="NN") == 0.0
    assert rs.theta_tol(table, "baseline", model="KNN") == 2.0
    # a legacy cache without a model column is treated as NN
    legacy = rs.aggregate(folds[folds["model"] == "NN"].drop(columns="model"))
    assert set(legacy["model"]) == {"NN"}
