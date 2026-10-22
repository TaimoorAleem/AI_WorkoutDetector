import mlflow
import numpy as np
import pandas as pd

from workout_ml.models.experiment import run_experiment
from workout_ml.models.lopo import RAW_COLUMNS


def tiny_featured_df():
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
    df = pd.DataFrame(rows, columns=[*RAW_COLUMNS, "participant", "label", "category", "set"])
    df["acc_r"] = np.sqrt(df.acc_x**2 + df.acc_y**2 + df.acc_z**2)
    df["gyr_r"] = np.sqrt(df.gyr_x**2 + df.gyr_y**2 + df.gyr_z**2)
    df["acc_x_temp_mean_ws_5"] = df["acc_x"].rolling(5, min_periods=1).mean()
    df["acc_x_freq_0.5_Hz_ws_10"] = df["acc_y"] * 2
    df["acc_x_pse"] = df["acc_z"] ** 2
    return df


def test_mlflow_end_to_end_on_tiny_dataset(tmp_path, monkeypatch):
    uri = f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}"
    monkeypatch.setenv("MLFLOW_TRACKING_URI", uri)
    report = tmp_path / "report.md"
    outcome = run_experiment(
        tiny_featured_df(),
        models=["DT", "NN"],
        artifact_root=tmp_path / "mlartifacts",
        report_path=report,
        rep_summary=None,
    )
    assert report.exists()
    assert outcome.parent_run_id in report.read_text(encoding="utf-8")

    mlflow.set_tracking_uri(uri)
    exp = mlflow.get_experiment_by_name("proprio-classifier")
    runs = mlflow.search_runs([exp.experiment_id])
    parent = runs[runs["run_id"] == outcome.parent_run_id].iloc[0]
    assert parent["tags.phase"] == "0"
    assert parent["tags.data.raw.md5"]
    assert "tags.git.sha" in runs.columns
    children = runs[runs["tags.mlflow.parentRunId"] == outcome.parent_run_id]
    assert len(children) == 4 * 2  # 4 feature sets x 2 models
    assert children["metrics.lopo_window_macro_f1_mean"].notna().all()

    client = mlflow.MlflowClient()
    artifacts = {a.path for a in client.list_artifacts(outcome.best_run_id)}
    assert {"fold_metrics.csv", "confusion_matrix.png"} <= artifacts
    # MLflow 3 stores log_model output as a LoggedModel linked to the run
    logged = mlflow.search_logged_models(experiment_ids=[exp.experiment_id], output_format="list")
    mine = [m for m in logged if m.source_run_id == outcome.best_run_id]
    assert len(mine) == 1
    model = mlflow.pyfunc.load_model(f"models:/{mine[0].model_id}")
    inputs = model.metadata.get_input_schema().input_names()
    assert len(model.predict(tiny_featured_df()[inputs])) > 0
