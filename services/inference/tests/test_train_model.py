"""``train_model --synthetic``: a clearly labelled fixture bundle, no real data needed."""

from __future__ import annotations

from workout_ml.watch.predict import ModelBundle

from proprio_inference import synthetic, train_model


def test_synthetic_model_is_labelled_and_loadable(tmp_path):
    out = tmp_path / "m"
    assert train_model.main(["--out", str(out), "--synthetic"]) == 0
    bundle = ModelBundle.load(out)
    assert bundle.name == synthetic.SYNTHETIC_NAME == "synthetic-fixture"
    assert bundle.version == synthetic.SYNTHETIC_VERSION
    assert set(bundle.classes) == {*synthetic.LIFTS, "rest"}


def test_if_missing_keeps_an_existing_model(tmp_path, model):
    out = model.save(tmp_path / "m")
    before = (out / "model.joblib").stat().st_mtime_ns
    assert train_model.main(["--out", str(out), "--synthetic", "--if-missing"]) == 0
    assert (out / "model.joblib").stat().st_mtime_ns == before
    assert ModelBundle.load(out).name == "fixture-classifier"
