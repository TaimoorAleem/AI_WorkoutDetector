"""End to end on synthetic sessions: zips written with a known R -> ingest -> fit -> evaluate."""

import json
from pathlib import Path

import mlflow
import numpy as np
import pytest
from scipy.spatial.transform import Rotation
from watch_synth import calibration_zip, gym_zip, known_rotation, mm_cleaned

from workout_ml.watch import calibrate, evaluate
from workout_ml.watch.metrics import suffix_status

GYM_ID = "aaaaaaaa-0000-4000-8000-000000000001"
CAL_ID = "cccccccc-0000-4000-8000-000000000002"


@pytest.fixture(scope="module")
def synth(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("watch")
    rng = np.random.default_rng(0)
    R = known_rotation(40.0)
    gym_zip(tmp / "gym" / f"proprio-session-{GYM_ID}.zip", R, rng, session_id=GYM_ID)
    calibration_zip(tmp / "calibration" / f"proprio-session-{CAL_ID}.zip", session_id=CAL_ID)
    ds = tmp / "datasets.yaml"
    ds.write_text(
        f"watch-calib-v1: {{sessions: [{CAL_ID}]}}\n"
        f"watch-fit-v1: {{sessions: [{GYM_ID}]}}\n"
        "watch-val-v1: {sessions: []}\n"
    )
    return {"tmp": tmp, "R": R, "ds": ds, "mm": mm_cleaned(rng, sets_per_label=3, duration_s=18.0)}


def test_in_sample_end_to_end(synth, tmp_path):
    uri = f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}"
    report = tmp_path / "report.md"
    metrics = tmp_path / "metrics.json"
    res = evaluate.run(
        "watch-fit-v1",
        in_sample=True,
        models=["M1", "M3", "M4"],
        mm_df=synth["mm"],
        watch_root=synth["tmp"],
        datasets_file=synth["ds"],
        aug_cfg={"copies": 1, "max_deg": 30.0, "seed": 1},
        tracking_uri=uri,
        artifact_root=tmp_path / "art",
        report_path=report,
        metrics_out=metrics,
        baselines={"M1": 0.986},
    )
    assert res["status"] == "ok"
    # R recovered within 2 degrees
    err = np.degrees(Rotation.from_matrix(res["fit_res"].R.T @ synth["R"]).magnitude())
    assert err < 2.0
    assert res["fit_res"].residual_rms_g < 0.03
    # report: banner, suffixed statuses, sections, skipped optional model
    text = report.read_text(encoding="utf-8")
    assert "IN-SAMPLE (optimistic)" in text
    assert "(in-sample)" in text
    for section in ("## Calibration", "## Rotation fit", "## Gates", "bench <-> OHP", "## Rejection",
                    "## Rep counter", "## Domain diagnostics", "## Recording quality"):  # fmt: skip
        assert section in text
    assert "M4 skipped" in text
    # every gate status carries the suffix; the fitted profile beats the prior on exercise accuracy
    for r in res["rows"].values():
        for g in r["gates"].values():
            assert suffix_status(g["status"], True).endswith("(in-sample)")
    for row in json.loads(metrics.read_text())["rows"].values():
        assert all(s.endswith("(in-sample)") for s in row["gates"].values())
    fitted, prior = res["rows"][("M1", "fitted")], res["rows"][("M1", "prior")]
    assert fitted["set_acc"] > 0.9 and fitted["set_acc"] > prior["set_acc"]
    assert fitted["gates"]["calibration"]["status"] == "PASS"
    assert fitted["rejection"]["rate"] >= 0.8
    flat = json.loads(metrics.read_text())
    assert flat["mode"] == "in-sample" and "M1|fitted" in flat["rows"]
    # MLflow: one parent, one child per model x profile, gates as tags
    mlflow.set_tracking_uri(uri)
    exp = mlflow.get_experiment_by_name("proprio-domain-shift")
    runs = mlflow.search_runs([exp.experiment_id])
    parent = runs[runs["run_id"] == res["run_ids"]["parent"]].iloc[0]
    assert parent["tags.mode"] == "in-sample" and parent["tags.git.sha"]
    kids = runs[runs["tags.mlflow.parentRunId"] == res["run_ids"]["parent"]]
    assert len(kids) == 2 * 2
    assert kids["tags.gate.set_accuracy"].str.contains("in-sample").all()
    arts = {a.path for a in mlflow.MlflowClient().list_artifacts(res["run_ids"]["parent"])}
    assert {"report.md", f"{calibrate.FITTED_PROFILE_ID}.yaml"} <= arts


def test_no_gym_sessions_yet_exits_cleanly(synth, tmp_path, capsys):
    out = tmp_path / "m.json"
    rc = evaluate.main(
        ["--fit", "watch-val-v1", "--eval", "watch-val-v1", "--datasets-file", str(synth["ds"]),
         "--watch-root", str(synth["tmp"]), "--metrics-out", str(out)]
    )  # fmt: skip
    assert rc == 0
    assert "no gym sessions yet" in capsys.readouterr().out
    assert json.loads(out.read_text())["status"] == "no gym sessions yet"


def test_calibrate_cli_with_empty_fit_dataset(tmp_path, capsys):
    out = tmp_path / "fit.json"
    assert calibrate.main(["--fit", "watch-fit-v1", "--out", str(out)]) == 0
    assert "no gym sessions yet" in capsys.readouterr().out
    assert json.loads(Path(out).read_text())["status"] == "no gym sessions yet"
