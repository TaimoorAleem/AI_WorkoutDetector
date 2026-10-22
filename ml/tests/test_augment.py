"""Fast synthetic tests for rotation augmentation (D-018)."""

import numpy as np
import pandas as pd

from workout_ml import pipeline
from workout_ml.augment import ACC_COLS, GYR_COLS, augment_sets, engineer_augmented
from workout_ml.models import lopo

SENSORS = ACC_COLS + GYR_COLS


def _cleaned(n=30, participants="ABC", sets_per=2, seed=0):
    rng = np.random.default_rng(seed)
    frames, set_id = [], 0
    for p in participants:
        for _ in range(sets_per):
            set_id += 1
            df = pd.DataFrame(rng.normal(size=(n, 6)), columns=SENSORS)
            df["participant"], df["label"], df["category"], df["set"] = p, "bench", "heavy", set_id
            frames.append(df)
    return pd.concat(frames, ignore_index=True)


def test_k_copies_per_set_norms_preserved_unique_ids():
    df = _cleaned()
    aug = augment_sets(df, copies=4, max_deg=30.0, seed=1)
    n_sets = df["set"].nunique()
    assert aug["set"].nunique() == 4 * n_sets
    assert not set(aug["set"]) & set(df["set"])
    assert (aug.groupby("aug_of").size() == 4 * 30).all()
    # each copy maps to its source set and participant
    src_p = df.groupby("set")["participant"].first()
    assert (aug["participant"].to_numpy() == src_p.loc[aug["aug_of"]].to_numpy()).all()
    tiled = pd.concat([df] * 4, ignore_index=True)
    for cols in (ACC_COLS, GYR_COLS):
        np.testing.assert_allclose(
            np.linalg.norm(aug[cols].to_numpy(), axis=1),
            np.linalg.norm(tiled[cols].to_numpy(), axis=1),
            atol=1e-12,
        )
    assert not np.allclose(aug[SENSORS].to_numpy(), tiled[SENSORS].to_numpy())


def test_one_proper_rotation_per_set_copy_within_angle_bound():
    df = _cleaned(sets_per=1, participants="A")
    aug = augment_sets(df, copies=3, max_deg=30.0, seed=5)
    for _, sub in aug.groupby("set"):
        o = df[ACC_COLS].to_numpy()
        R = np.linalg.lstsq(o, sub[ACC_COLS].to_numpy(), rcond=None)[0].T  # a = o @ R.T
        np.testing.assert_allclose(R @ R.T, np.eye(3), atol=1e-9)
        assert np.isclose(np.linalg.det(R), 1.0)
        # the same R acts on gyro
        np.testing.assert_allclose(
            sub[GYR_COLS].to_numpy(), df[GYR_COLS].to_numpy() @ R.T, atol=1e-9
        )
        assert np.degrees(np.arccos((np.trace(R) - 1) / 2)) <= 30.0 + 1e-6


def test_seed_is_deterministic():
    df = _cleaned()
    a = augment_sets(df, 3, 30.0, seed=7)
    b = augment_sets(df, 3, 30.0, seed=7)
    c = augment_sets(df, 3, 30.0, seed=8)
    pd.testing.assert_frame_equal(a, b)
    assert not np.allclose(a[SENSORS].to_numpy(), c[SENSORS].to_numpy())


def test_no_held_out_copy_in_fold_training_rows():
    df = _cleaned()
    aug = augment_sets(df, 2, 30.0, seed=1)
    X = df[SENSORS].reset_index(drop=True)
    y = df["label"].to_numpy()
    participants = df["participant"].to_numpy()
    augment = lopo.AugmentedTrain(
        aug[SENSORS].reset_index(drop=True), aug["label"].to_numpy(), aug["participant"].to_numpy()
    )
    for train, test in lopo.lopo_splits(participants):
        held_out = participants[test][0]
        X_tr, y_tr, g_tr = lopo.training_rows(X, y, participants, train, held_out, augment)
        assert held_out not in set(g_tr)
        assert len(X_tr) == len(train) * (1 + 2)  # originals + K copies of the train sets
        assert len(X_tr) == len(y_tr) == len(g_tr)
    # test rows are the held-out originals only (selected by `test`, never augmented)
    assert len(test) == (participants == held_out).sum()


def test_engineer_augmented_is_copy_major_with_aug_of():
    df = _cleaned(n=12, participants="AB", sets_per=2)
    aug = augment_sets(df, 2, 30.0, seed=1)
    feat = engineer_augmented(aug, lambda sub: sub.copy())
    assert list(feat["aug_copy"].unique()) == [1, 2]
    assert set(feat["aug_of"]) == set(df["set"])


def test_no_augment_flag_produces_no_copies():
    assert pipeline.parse_args(["--no-augment"]).no_augment is True
    assert pipeline.parse_args([]).no_augment is False
    assert len(augment_sets(_cleaned(), 0, 30.0, seed=1)) == 0


def test_run_experiment_logs_augmentation(tmp_path, monkeypatch):
    import mlflow

    from workout_ml.models.experiment import run_experiment

    uri = f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}"
    monkeypatch.setenv("MLFLOW_TRACKING_URI", uri)
    base = _tiny_featured()
    aug = base.copy()
    aug["aug_of"] = aug["set"]
    aug["aug_copy"] = 1
    aug["set"] = aug["set"] + 1000
    outcome = run_experiment(
        base,
        models=["DT"],
        artifact_root=tmp_path / "mlartifacts",
        report_path=tmp_path / "r.md",
        augmented_df=aug,
        augmentation={"enabled": True, "copies": 1, "max_deg": 30.0, "seed": 1},
    )
    mlflow.set_tracking_uri(uri)
    exp = mlflow.get_experiment_by_name("proprio-classifier")
    runs = mlflow.search_runs([exp.experiment_id])
    assert (runs["tags.augmentation"] == "on").all()
    assert (runs["params.aug_copies"] == "1").all()
    assert "Training augmentation: **on**" in (tmp_path / "r.md").read_text(encoding="utf-8")
    assert outcome.leaderboard["lopo_set_acc_mean"].notna().all()


def _tiny_featured():
    rng = np.random.default_rng(0)
    rows, set_id = [], 0
    for p in "ABC":
        for k, label in enumerate(["bench", "squat"]):
            for _ in range(2):
                set_id += 1
                x = rng.normal(size=(20, 6))
                x[:, k] += 4
                for r in x:
                    rows.append([*r, p, label, "heavy", set_id])
    df = pd.DataFrame(rows, columns=[*SENSORS, "participant", "label", "category", "set"])
    df["acc_r"] = np.sqrt(df.acc_x**2 + df.acc_y**2 + df.acc_z**2)
    df["gyr_r"] = np.sqrt(df.gyr_x**2 + df.gyr_y**2 + df.gyr_z**2)
    df["acc_x_temp_mean_ws_5"] = df["acc_x"].rolling(5, min_periods=1).mean()
    df["acc_x_freq_0.5_Hz_ws_10"] = df["acc_y"] * 2
    df["acc_x_pse"] = df["acc_z"] ** 2
    return df
