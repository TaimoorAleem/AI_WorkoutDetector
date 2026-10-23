"""analyze_session / ModelBundle: the shared inference path (harness and service, D-012)."""

import numpy as np
import pytest
from watch_synth import gym_zip, known_rotation, mm_cleaned

from workout_ml.sensors import load_profile, profile_with_rotation
from workout_ml.watch import metrics as M
from workout_ml.watch.common import engineer, watch_frame
from workout_ml.watch.predict import ModelBundle, analyze_session, load_model, train_bundle

GYM_ID = "aaaaaaaa-0000-4000-8000-000000000001"
AUG = {"copies": 1, "max_deg": 30.0, "seed": 1}


@pytest.fixture(scope="module")
def synth(tmp_path_factory):
    tmp = tmp_path_factory.mktemp("predict")
    rng = np.random.default_rng(0)
    R = known_rotation(40.0)
    zip_path = gym_zip(tmp / f"proprio-session-{GYM_ID}.zip", R, rng, session_id=GYM_ID)
    profile = profile_with_rotation(load_profile("galaxy-watch-prior-v0"), R)
    mm = mm_cleaned(rng, sets_per_label=3, duration_s=18.0)
    model = train_bundle(mm, aug_cfg=AUG, version="test-1")
    return {"zip": zip_path, "profile": profile, "mm": mm, "model": model, "tmp": tmp}


def test_bundle_metadata_and_roundtrip(synth, tmp_path):
    model = synth["model"]
    assert model.name == "proprio-classifier" and model.version == "test-1"
    assert model.meta["feature_set"] == "basic+derived+pca+temporal"
    assert model.meta["model"] == "KNN" and model.meta["augmentation"]["copies"] == 1
    assert set(model.classes) == {"bench", "squat", "dead", "ohp", "row", "rest"}
    assert model.default_tau == M.DEFAULT_TAU

    model.save(tmp_path / "m")
    again = load_model(str(tmp_path / "m"))
    assert isinstance(again, ModelBundle) and again.columns == model.columns
    feat = engineer(synth["mm"])
    assert (again.predict(feat) == model.predict(feat)).all()


def test_load_model_rejects_unknown(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_model(str(tmp_path / "missing"))


def test_analyze_session_segments(synth):
    res = analyze_session(synth["zip"], synth["model"], synth["profile"], tau=0.6)
    kinds = [r.kind for r in res]
    assert kinds.count("set") == 10 and "other" in kinds and "rest" in kinds
    assert [r.start_s for r in res] == sorted(r.start_s for r in res)
    assert all(r.end_s > r.start_s >= 0 for r in res)
    lifts = [r for r in res if r.kind == "set"]
    ok = sum(r.predicted_kind == "set" for r in lifts)
    assert ok >= 8, [(r.segment_id, r.vote, r.confidence) for r in lifts]
    for r in res:
        if r.predicted_kind == "set":
            assert r.predicted_exercise in {"bench", "squat", "dead", "ohp", "row"}
            assert r.rep_count is not None and r.rep_count >= 0
        else:
            assert r.predicted_exercise is None and r.rep_count is None
        assert r.confidence is None or 0 <= r.confidence <= 1
    ev = res[0].to_event()
    assert {"segment_id", "kind", "start_s", "end_s", "predicted_exercise", "rep_count"} <= set(ev)


def test_same_decisions_as_the_harness(synth):
    """The service path and the Phase 3 harness path give the same per-set decisions."""
    import pandas as pd

    from workout_ml.watch.evaluate import ModelBank

    model = synth["model"]
    res = {r.segment_id: r for r in analyze_session(synth["zip"], model, synth["profile"], 0.6)}
    cleaned = watch_frame([synth["zip"]], synth["profile"])
    feat = engineer(cleaned)
    win = pd.DataFrame({"set": feat["set"].to_numpy(), "pred": model.predict(feat)})
    segs = cleaned.groupby("set")[["kind", "label", "segment_id"]].first()
    win["kind"] = win["set"].map(segs["kind"]).to_numpy()
    win["truth"] = win["set"].map(segs["label"]).to_numpy()
    dec = M.segment_decisions(win, 0.6)
    assert len(dec) > 0
    assert ModelBank  # the same class trained the bundle
    for row in dec.itertuples():
        r = res[int(segs.loc[row.set, "segment_id"])]
        assert r.vote == row.vote
        assert (r.predicted_exercise if r.predicted_kind == "set" else "no") == (
            row.final if row.logged else "no"
        )


def test_tau_rejects_everything_at_one(synth):
    res = analyze_session(synth["zip"], synth["model"], synth["profile"], tau=1.01)
    assert all(r.predicted_kind in (None, "rest") for r in res)


def test_golden_calibration_segments_are_not_classified(synth):
    from pathlib import Path

    golden = (
        Path(__file__).resolve().parents[2] / "protocol" / "golden" / "session_calibration_v1.zip"
    )
    res = analyze_session(golden, synth["model"], "galaxy-watch-prior-v0")
    assert res and all(r.kind == "calibration" and r.predicted_kind is None for r in res)
