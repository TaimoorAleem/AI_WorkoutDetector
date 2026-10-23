import numpy as np
import pandas as pd
import pytest

torch = pytest.importorskip("torch")
pytest.importorskip("onnx")
pytest.importorskip("onnxruntime")

from workout_ml.models import cnn  # noqa: E402

COLS = ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]


def synthetic_df(n_bins=30, participants="ABC", seed=0):
    rng = np.random.default_rng(seed)
    rows, set_id = [], 0
    t = 0
    for p in participants:
        for k, label in enumerate(["bench", "squat"]):
            for _ in range(2):
                set_id += 1
                x = rng.normal(size=(n_bins, 6))
                x[:, k] += 3
                for r in x:
                    rows.append([*r, p, label, "heavy", set_id, t])
                    t += 1
    df = pd.DataFrame(rows, columns=[*COLS, "participant", "label", "category", "set", "t"])
    return df.set_index("t")


def test_windows_do_not_cross_sets_and_labels_are_right():
    df = synthetic_df(n_bins=25)
    W = 20
    win = cnn.build_windows(df, W)
    assert len(win.y) == df["set"].nunique() * (25 - W + 1)
    set_label = df.groupby("set")["label"].first()
    set_part = df.groupby("set")["participant"].first()
    assert (win.y == set_label.loc[win.set_id].to_numpy()).all()
    assert (win.participant == set_part.loc[win.set_id].to_numpy()).all()
    # every window equals a contiguous slice of its own set
    for i in (0, 5, len(win.y) - 1):
        g = df[df["set"] == win.set_id[i]][COLS].to_numpy(np.float32)
        e = win.end_pos[i]
        np.testing.assert_array_equal(win.x[i], g[e - W + 1 : e + 1])
    # a short set contributes nothing
    short = pd.concat([df, df[df["set"] == 1].iloc[:5].assign(set=999, participant="A")])
    assert 999 not in set(cnn.build_windows(short, W).set_id)


def test_norm_stats_use_training_participants_only():
    df = synthetic_df()
    df.loc[df["participant"] == "C", COLS] += 100.0  # a huge shift in C only
    train = cnn.training_bins(df, {"A", "B"})
    norm = cnn.fit_norm(train)
    assert max(abs(m) for m in norm["mean"][:6]) < 10
    full = cnn.fit_norm(cnn.training_bins(df, {"A", "B", "C"}))
    assert max(abs(m) for m in full["mean"][:6]) > 10
    assert len(norm["mean"]) == len(norm["std"]) == 8


def test_rotation_preserves_norms_and_respects_angle_bound():
    g = torch.Generator().manual_seed(0)
    R = cnn.random_rotation_matrices(2000, 30.0, g)
    eye = torch.eye(3).expand_as(R)
    assert torch.allclose(R @ R.transpose(1, 2), eye, atol=1e-5)
    assert torch.allclose(torch.linalg.det(R), torch.ones(2000), atol=1e-5)
    angle = torch.rad2deg(torch.acos(((R.diagonal(dim1=1, dim2=2).sum(1) - 1) / 2).clamp(-1, 1)))
    assert angle.max() <= 30.0 + 1e-2 and angle.min() >= 0
    assert angle.mean() > 10  # not degenerate (U[0, 30] has mean 15)
    x = torch.randn(2000, 20, 6)
    xr = cnn.rotate_windows(x, R)
    assert torch.allclose(cnn.to_channels(x)[..., 6:], cnn.to_channels(xr)[..., 6:], atol=1e-4)
    assert not torch.allclose(x, xr)


def test_model_shapes():
    m = cnn.make_model(cnn.CnnConfig(), 6)
    assert m(torch.zeros(4, 8, 20)).shape == (4, 6)
    x = cnn.preprocess(torch.randn(3, 20, 6), cnn.fit_norm(np.random.randn(50, 6)))
    assert x.shape == (3, 8, 20)


def test_val_participant_is_deterministic_and_not_test():
    df = synthetic_df()
    win = cnn.build_windows(df, 20)
    train = cnn.select(win, win.participant != "A")
    v1 = cnn.choose_val_participant(train.participant, train.y)
    assert v1 in {"B", "C"} and v1 == cnn.choose_val_participant(train.participant, train.y)


def test_onnx_parity(tmp_path):
    torch.manual_seed(0)
    m = cnn.make_model(cnn.CnnConfig(), 6)
    m.eval()
    path = cnn.export_onnx(m, tmp_path / "m.onnx", 20)
    assert cnn.onnx_parity(m, path, 20) < 1e-4


def test_quick_lopo_logs_to_mlflow(tmp_path):
    import mlflow

    df = synthetic_df(n_bins=30)
    cfg = cnn.CnnConfig(max_epochs=2, patience=1, threads=1)
    uri = f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}"
    report = tmp_path / "report.md"
    out = cnn.run_cnn(
        df, variants=("aug", "noaug"), cfg=cfg, tracking_uri=uri,
        artifact_root=tmp_path / "art", report_path=report,
        classical_path=tmp_path / "missing.md",
    )  # fmt: skip
    assert report.exists() and out["parent_run_id"] in report.read_text(encoding="utf-8")
    for v in out["variants"].values():
        folds = v["res"].folds
        assert len(folds) == 3
        # no test participant in its own training or val data is asserted inside the loop;
        # check the recorded val participant differs from the test participant too
        assert (folds["val_participant"] != folds["test_participant"]).all()
        assert v["diff"] < 1e-4
    mlflow.set_tracking_uri(uri)
    exp = mlflow.get_experiment_by_name("proprio-cnn")
    runs = mlflow.search_runs([exp.experiment_id])
    children = runs[runs["tags.mlflow.parentRunId"] == out["parent_run_id"]]
    assert len(children) == 2
    run_id = out["variants"]["aug"]["run_id"]
    names = {a.path for a in mlflow.MlflowClient().list_artifacts(run_id)}
    assert {"fold_metrics.csv", "training_curves.csv", "confusion_matrix.png", "onnx"} <= names
