"""Watch ingest (golden gym session) and calibration_check (synthetic calibration sessions)."""

import json
import zipfile
from pathlib import Path

import numpy as np
import pytest
from watch_synth import calibration_zip

from workout_ml.sensors import load_profile
from workout_ml.watch.datasets import dataset_paths, load_dataset_map
from workout_ml.watch.ingest import calibration_check, load_watch_sessions

GOLDEN = Path(__file__).resolve().parents[2] / "protocol" / "golden"
GYM = GOLDEN / "session_gym_v1.zip"
PRIOR = load_profile("galaxy-watch-prior-v0")


def _patched(tmp_path, labels_fn, name="patched.zip"):
    with zipfile.ZipFile(GYM) as zf:
        entries = {i.filename: zf.read(i) for i in zf.infolist()}
    labels = json.loads(entries["labels.json"])
    labels_fn(labels)
    entries["labels.json"] = json.dumps(labels).encode()
    out = tmp_path / name
    with zipfile.ZipFile(out, "w") as zf:
        for k, v in entries.items():
            zf.writestr(k, v)
    return out


def test_golden_gym_labels_rest_other():
    df = load_watch_sessions([GYM], PRIOR)
    segs = df.groupby("set").first()
    assert set(segs["kind"]) == {"set", "rest", "other"}
    assert sorted(segs.loc[segs["kind"] == "set", "label"]) == ["bench", "dead", "ohp", "squat"]
    assert (segs.loc[segs["kind"] == "rest", "label"] == "rest").all()
    assert (segs.loc[segs["kind"] == "rest", "implicit"]).all()
    assert segs.loc[segs["kind"] == "other", "label"].tolist() == ["other"]
    assert len(segs[segs["kind"] == "rest"]) == 4
    assert segs.loc[segs["kind"] == "set", "confirmed"].all()
    assert not segs.loc[segs["kind"] != "set", "confirmed"].any()
    assert set(df["participant"]) == {"P01"}
    assert {"session_id", "segment_id", "kind", "reps_user", "source", "implicit"} <= set(df)
    assert df.attrs["csf"]["profile_id"] == "galaxy-watch-prior-v0"
    assert df[["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]].notna().all().all()


def test_unconfirmed_discarded_and_exerciseless_sets(tmp_path):
    def patch(labels):
        segs = {s["segment_id"]: s for s in labels["segments"]}
        segs[1].pop("reps_user")  # bench: no rep count -> flagged, still evaluated
        segs[2]["kind"] = "other"  # discarded = kind other + note "discarded" (phone convention)
        segs[2]["note"] = "discarded"
        segs[4].pop("exercise")  # a set without exercise cannot be scored

    df = load_watch_sessions([_patched(tmp_path, patch)], PRIOR)
    segs = df.groupby("set").first()
    bench = segs[segs["label"] == "bench"].iloc[0]
    assert bench["kind"] == "set" and not bench["confirmed"]
    assert (segs["label"] == "squat").sum() == 0  # discarded -> excluded
    assert "dead" not in set(segs["label"])
    assert any("without exercise" in why for _, why in df.attrs["skipped"])


def test_dataset_map_and_empty_dataset(tmp_path):
    ds = load_dataset_map()
    assert set(ds) >= {"watch-calib-v1", "watch-fit-v1", "watch-val-v1", "watch-u3-v1"}
    assert ds["watch-calib-v1"] == ["9390dce6-8af1-44b7-9306-31994e90e314"]
    assert dataset_paths("watch-fit-v1") == []
    empty = load_watch_sessions("watch-fit-v1", PRIOR)
    assert empty.empty and "label" in empty.columns
    with pytest.raises(KeyError):
        dataset_paths("nope")


def test_calibration_check_passes_on_known_units_and_signs(tmp_path):
    r = calibration_check(calibration_zip(tmp_path / "c.zip"))
    assert r["passed"], r["gates"]
    assert r["static"]["abs_a_g"] == pytest.approx(1.0, abs=0.01)
    assert r["static"]["gyro_bias_norm_dps"] < 0.5
    assert all(p["ok"] for p in r["poses"].values()) and len(r["poses"]) == 6
    assert {"x", "y"} <= set(r["gyro_sign"]["identifiable_axes"])  # rot_z cannot test z
    json.dumps(r)  # serialisable


@pytest.mark.parametrize("axis", ["x", "y"])
def test_calibration_check_detects_sign_flip(tmp_path, axis):
    r = calibration_check(calibration_zip(tmp_path / "f.zip", flip_gyro_axis=axis))
    assert not r["gates"]["gyro_sign"]
    assert r["gyro_sign"]["axes"][axis]["identifiable"]
    assert not r["gyro_sign"]["axes"][axis]["sign_ok"]
    assert not r["passed"]


def test_calibration_check_detects_units_bug(tmp_path):
    r = calibration_check(calibration_zip(tmp_path / "u.zip", acc_scale=1.1))
    assert not r["gates"]["static_magnitude"]
    assert r["static"]["abs_a_g"] == pytest.approx(1.1, abs=0.01)
    assert np.isfinite(r["static"]["abs_a_g"])


def test_calibration_check_rejects_gym_session():
    with pytest.raises(ValueError, match="not a calibration"):
        calibration_check(GYM)
