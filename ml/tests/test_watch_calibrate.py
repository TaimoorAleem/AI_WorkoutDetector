"""Kabsch rotation fit (spec section 4): recovery, conditioning warning, mirror flag, profile."""

import numpy as np
import pandas as pd
import pytest
from scipy.spatial.transform import Rotation
from watch_synth import GRAVITY, known_rotation

from workout_ml.sensors import load_profile
from workout_ml.watch.calibrate import (
    FITTED_PROFILE_ID,
    fit_rotation,
    fitted_profile,
    identity_profile,
    kabsch,
    write_fitted_profile,
)


def _frame(vectors: dict, rng, noise=0.0, n_sets=3, rows=30) -> pd.DataFrame:
    parts, sid = [], 0
    for label, v in vectors.items():
        for _ in range(n_sets):
            sid += 1
            acc = np.asarray(v) + rng.normal(0, noise, (rows, 3))
            df = pd.DataFrame(acc, columns=["acc_x", "acc_y", "acc_z"])
            gyr = rng.normal(0, 10, (rows, 3)) * (1 + len(label))
            for i, c in enumerate(["gyr_x", "gyr_y", "gyr_z"]):
                df[c] = gyr[:, i]
            df["label"], df["set"], df["kind"] = label, sid, "rest" if label == "rest" else "set"
            parts.append(df)
    return pd.concat(parts, ignore_index=True)


def _angle(Ra, Rb):
    return np.degrees(Rotation.from_matrix(Ra.T @ Rb).magnitude())


def test_kabsch_recovers_known_rotation_with_noise():
    rng = np.random.default_rng(0)
    R = known_rotation(55.0)
    mm = _frame(GRAVITY, rng, noise=0.02)
    watch = _frame({k: R.T @ np.array(v) for k, v in GRAVITY.items()}, rng, noise=0.02)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert _angle(fit.R, R) < 1.0
    assert fit.residual_rms_g < 0.03
    assert np.linalg.det(fit.R) == pytest.approx(1.0)
    assert not fit.flags["mirror_preferred"]
    assert not any("poorly conditioned" in w for w in fit.warnings)
    assert fit.exercises == sorted(GRAVITY)
    assert fit.weights["bench"] == 3.0


def test_near_collinear_gravity_warns():
    rng = np.random.default_rng(1)
    vecs = {
        "bench": (0.01, 0.95, 0.0),
        "ohp": (0.0, 0.92, 0.01),
        "dead": (0.01, -1.0, 0.0),
        "row": (0.0, -1.02, -0.01),
    }
    R = known_rotation(30.0)
    mm = _frame(vecs, rng)
    watch = _frame({k: R.T @ np.array(v) for k, v in vecs.items()}, rng)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert any("poorly conditioned" in w for w in fit.warnings)
    assert fit.lever_min_g < 0.15


def test_mirrored_truth_flags_det_minus_one():
    rng = np.random.default_rng(2)
    T = known_rotation(20.0) @ np.diag([1.0, 1.0, -1.0])  # det = -1: a mirrored watch
    assert np.linalg.det(T) < 0
    mm = _frame(GRAVITY, rng, noise=0.005)
    watch = _frame({k: T.T @ np.array(v) for k, v in GRAVITY.items()}, rng, noise=0.005)
    fit = fit_rotation(mm, watch, prior_R=np.eye(3))
    assert fit.flags["mirror_preferred"]
    assert fit.unconstrained["det"] < 0
    assert fit.unconstrained["residual_rms_g"] < fit.residual_rms_g
    assert any("ESCALATE" in w for w in fit.warnings)
    assert np.linalg.det(fit.R) == pytest.approx(1.0)


def test_fit_needs_two_labels():
    rng = np.random.default_rng(3)
    mm = _frame({"bench": GRAVITY["bench"]}, rng)
    with pytest.raises(ValueError, match="at least 2"):
        fit_rotation(mm, mm.copy(), prior_R=np.eye(3))


def test_kabsch_weights_pull_towards_heavy_vectors():
    A = np.array([[1.0, 0, 0], [0, 1, 0], [0, 0, 1], [1, 1, 0]])
    R_true = known_rotation(25.0)
    B = A @ R_true.T
    B[3] += [0.1, -0.1, 0.0]  # one inconsistent pair
    R_w, _, _ = kabsch(A, B, [1, 1, 1, 100])
    R_u, _, _ = kabsch(A, B, [1, 1, 1, 0.01])
    err = lambda R: np.linalg.norm(A[3] @ R.T - B[3])  # noqa: E731
    assert err(R_w) < err(R_u)


def test_profile_roundtrip(tmp_path):
    rng = np.random.default_rng(4)
    R = known_rotation(35.0)
    mm = _frame(GRAVITY, rng, noise=0.01)
    watch = _frame({k: R.T @ np.array(v) for k, v in GRAVITY.items()}, rng, noise=0.01)
    fit = fit_rotation(mm, watch)
    path = write_fitted_profile(fit, ["sess-1"], path=tmp_path / f"{FITTED_PROFILE_ID}.yaml")
    loaded = load_profile(path)
    assert loaded.rotation_status == "fitted"
    assert loaded.device["model"] == "SM-R920"
    assert loaded.calibration["fit_sessions"] == ["sess-1"]
    assert loaded.calibration["residual_g"] == pytest.approx(fit.residual_rms_g, abs=1e-4)
    np.testing.assert_allclose(loaded.rotation, fit.R, atol=1e-12)
    assert loaded.transform_id != load_profile("galaxy-watch-prior-v0").transform_id
    mem = fitted_profile(fit, ["sess-1"])
    assert mem.transform_id == loaded.transform_id  # same numbers -> same transform id
    np.testing.assert_allclose(identity_profile().rotation, np.eye(3))
