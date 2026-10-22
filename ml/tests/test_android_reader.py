"""Tests for the Raw Format v1 reader against the committed golden sessions.

``protocol/golden/expected.json`` is written by the Kotlin generator (``:protocol:generateGolden``)
from its own reader; matching it here is the cross-language conformance check.
"""

from __future__ import annotations

import json
import zipfile
from pathlib import Path

import numpy as np
import pytest

from workout_ml.sensors import CSF_COLUMNS, load_profile, to_csf
from workout_ml.sensors import android as A
from workout_ml.sensors.android import (
    SessionFormatError,
    read_session,
    recording_stats,
    resolve_segments,
)

GOLDEN = Path(__file__).resolve().parents[2] / "protocol" / "golden"
EXPECTED = json.loads((GOLDEN / "expected.json").read_text(encoding="utf-8"))
ZIPS = {"gym": GOLDEN / "session_gym_v1.zip", "calibration": GOLDEN / "session_calibration_v1.zip"}


def _entries(name: str) -> dict[str, bytes]:
    with zipfile.ZipFile(ZIPS[name]) as zf:
        return {i.filename: zf.read(i) for i in zf.infolist()}


def _write_zip(path: Path, entries: dict[str, bytes]) -> Path:
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as zf:
        for k, v in entries.items():
            zf.writestr(k, v)
    return path


def _assert_close(actual, expected):
    if isinstance(expected, dict):
        assert actual.keys() == expected.keys()
        for k in expected:
            _assert_close(actual[k], expected[k])
    elif expected is None or isinstance(expected, (str, bool, int)):
        assert actual == expected
    else:
        assert actual == pytest.approx(expected, rel=1e-9, abs=1e-12)


@pytest.mark.parametrize("name", ["gym", "calibration"])
def test_golden_matches_expected_json(name):
    exp = EXPECTED[name]
    s = read_session(ZIPS[name])
    assert s.session_id == exp["session_id"]
    assert s.kind == exp["kind"]
    assert s.meta["complete"] is exp["complete"]
    assert (s.labels or {}).get("participant_id") == exp["participant_id"]
    assert s.meta["sample_counts"] == exp["sample_counts"]
    assert {"acc": s.report["acc_rows"], "gyr": s.report["gyr_rows"]} == exp["rows"]

    segs = s.resolved_segments()
    assert len(segs) == len(exp["segments"])
    t0 = s.meta["clock_anchor_start"]["elapsed_realtime_ns"]
    assert t0  # sensor clock is not Unix time
    for seg, e in zip(segs, exp["segments"]):
        assert seg.segment_id == e["segment_id"]
        assert seg.kind == e["kind"]
        assert (seg.t_start_ns, seg.t_end_ns) == (e["t_start_ns"], e["t_end_ns"])
        assert (s.to_unix_ns(seg.t_start_ns), s.to_unix_ns(seg.t_end_ns)) == (
            e["t_start_unix_ns"],
            e["t_end_unix_ns"],
        )
        assert seg.source == e["source"]
        assert seg.step == e["step"]
        assert seg.exercise == e["exercise"]
        assert seg.load_kg == e["load_kg"]
        assert seg.category == e["category"]
        assert seg.reps_user == e["reps_user"]
        assert seg.rpe == e["rpe"]
        assert seg.implicit is e["implicit"]

    # Per-segment sample counts through the public API.
    sets = s.segments()
    assert [x.meta["segment_id"] for x in sets] == [e["segment_id"] for e in exp["segments"]]
    for ss, e in zip(sets, exp["segments"]):
        assert (len(ss.acc), len(ss.gyr)) == (e["acc_n"], e["gyr_n"])

    _assert_close(recording_stats(s), exp["stats"])


def test_streams_follow_the_sensor_spec_contract():
    s = read_session(ZIPS["gym"])
    for stream, unit in ((s.acc, "m/s^2"), (s.gyr, "rad/s")):
        assert stream.frame == "android"
        assert stream.unit == unit
        assert stream.t_ns.dtype == np.int64
        assert (np.diff(stream.t_ns) > 0).all()  # sorted, no duplicates
    # Unix epoch ns, from the start anchor (2025-06-15T15:06:40Z + 50 ms).
    assert s.acc.t_ns[0] == 1_750_000_000_000_000_000 + 50_000_000 + (
        s.acc.t_ns[0] - 1_750_000_000_050_000_000
    )
    assert abs(s.acc.t_ns[0] - 1_750_000_000_050_000_000) <= 3_000_000


def test_segments_meta_ready_for_the_pipeline():
    s = read_session(ZIPS["gym"])
    sets = s.segments(kinds=["set"])
    assert [x.meta["label"] for x in sets] == ["bench", "squat", "dead", "ohp"]
    first = sets[0].meta
    assert first["category"] == "heavy"
    assert first["reps_user"] == 5
    assert first["participant"] == "P01"
    assert first["segment_id"] == 1
    assert first["session_id"] == s.session_id
    rests = s.segments(kinds="rest")
    assert [r.meta["label"] for r in rests] == ["rest"] * 4
    assert all(r.meta["implicit"] for r in rests)
    # Override: set 5 starts at 177.5 s, not the watch's 175 s.
    ohp = sets[3]
    assert ohp.acc.t_ns[0] >= s.to_unix_ns(s.resolved_segments()[-1].t_start_ns)


def test_to_csf_end_to_end_on_a_set_segment():
    s = read_session(ZIPS["gym"])
    squat = s.segments(kinds=["set"])[1]
    df = to_csf(squat, load_profile("galaxy-watch-prior-v0"))
    assert list(df.columns) == CSF_COLUMNS
    assert 100 <= len(df) <= 160  # 30 s of 200 ms bins, minus edge bins
    assert np.isfinite(df.to_numpy()).all()
    assert df.attrs["csf"]["profile_id"] == "galaxy-watch-prior-v0"
    # Gravity (about 9.7 m/s^2 on the device y axis) shows up as roughly 1 g in magnitude.
    mag = np.sqrt((df[["acc_x", "acc_y", "acc_z"]] ** 2).sum(axis=1)).mean()
    assert 0.8 < mag < 1.8


def test_calibration_golden_has_steps_and_no_implicit_rest():
    s = read_session(ZIPS["calibration"])
    segs = s.resolved_segments()
    assert len(segs) == 10
    assert all(g.kind == "calibration" and g.source == "auto" and g.step for g in segs)
    assert not any(g.implicit for g in segs)
    assert segs[0].step == "table_flat_face_up"


def test_duplicates_gaps_and_order_are_reported():
    st = recording_stats(read_session(ZIPS["gym"]))
    assert st["acc"]["duplicates"] == 1
    assert st["acc"]["gaps_gt_100ms"] == 1
    assert st["acc"]["rate_hz"] == pytest.approx(50, rel=0.01)
    assert st["clock_drift_ms"] == pytest.approx(12.0)
    assert st["acc_frac_above_1p9g"] > 0


def test_truncated_csv_recovers_all_complete_rows(tmp_path):
    entries = _entries("gym")
    full_rows = len(entries["acc.csv"].decode().splitlines()) - 1
    entries["acc.csv"] = entries["acc.csv"][:-5]  # cut mid-row: drops the final newline too
    entries["gyr.csv"] = entries["gyr.csv"][:-1]  # only the newline missing
    # Crash-style meta: no end anchor, incomplete.
    meta = json.loads(entries["meta.json"])
    for k in ("clock_anchor_end", "sample_counts", "ended_at_utc"):
        del meta[k]
    meta["complete"] = False
    entries["meta.json"] = json.dumps(meta).encode()
    s = read_session(_write_zip(tmp_path / "cut.zip", entries))
    assert s.report["acc_rows"] == full_rows - 1
    assert s.report["gyr_rows"] == full_rows - 1
    assert s.report["dropped_truncated_rows"] == 2
    assert s.meta["complete"] is False
    assert recording_stats(s)["clock_drift_ms"] is None
    assert len(s.acc) == EXPECTED["gym"]["stats"]["acc"]["n"] - 1


def test_unsorted_and_duplicate_rows_are_repaired(tmp_path):
    entries = _entries("gym")
    entries["acc.csv"] = b"t_ns,x,y,z\n30,3,0,0\n10,1,0,0\n20,2,0,0\n20,99,0,0\n"
    entries["gyr.csv"] = b"t_ns,x,y,z\n"
    entries["segments.csv"] = b"segment_id,t_start_ns,t_end_ns,source,step\n"
    s = read_session(_write_zip(tmp_path / "mini.zip", entries))
    assert s.acc.xyz[:, 0].tolist() == [1.0, 2.0, 3.0]  # first occurrence of t=20 wins
    assert s.report["acc_duplicates"] == 1
    assert s.report["acc_out_of_order"] == 1
    assert len(s.gyr) == 0


def test_unpacked_directory_is_readable(tmp_path):
    for name, data in _entries("calibration").items():
        (tmp_path / name).write_bytes(data)
    assert read_session(tmp_path).kind == "calibration"


@pytest.mark.parametrize(
    "mutate",
    [
        lambda m, labels: m.update(kind="yoga"),
        lambda m, labels: m.update(raw_format=2),
        lambda m, labels: m.update(wrist="right"),
        lambda m, labels: m.pop("clock_anchor_start"),
        lambda m, labels: m.pop("clock_anchor_end"),  # complete=true needs it
        lambda m, labels: labels["segments"][0].update(exercise="curl"),
        lambda m, labels: labels["segments"][0].update(kind="superset"),
        lambda m, labels: labels.pop("participant_id"),
    ],
)
def test_schema_violations_raise(tmp_path, mutate):
    entries = _entries("gym")
    meta, labels = json.loads(entries["meta.json"]), json.loads(entries["labels.json"])
    mutate(meta, labels)
    entries["meta.json"], entries["labels.json"] = (
        json.dumps(meta).encode(),
        json.dumps(labels).encode(),
    )
    with pytest.raises(SessionFormatError):
        read_session(_write_zip(tmp_path / "bad.zip", entries))


def test_structural_errors_raise(tmp_path):
    entries = _entries("gym")
    labels = json.loads(entries["labels.json"])
    labels["session_id"] = "someone-else"
    with pytest.raises(SessionFormatError, match="does not match"):
        read_session(
            _write_zip(tmp_path / "a.zip", {**entries, "labels.json": json.dumps(labels).encode()})
        )
    with pytest.raises(SessionFormatError, match="meta.json missing"):
        read_session(_write_zip(tmp_path / "b.zip", {"acc.csv": entries["acc.csv"]}))
    with pytest.raises(SessionFormatError, match="header"):
        read_session(_write_zip(tmp_path / "c.zip", {**entries, "acc.csv": b"a,b,c,d\n1,2,3,4\n"}))
    with pytest.raises(SessionFormatError, match="unsafe"):
        read_session(_write_zip(tmp_path / "d.zip", {**entries, "../evil.txt": b"x"}))


def test_large_clock_drift_is_corrected_small_is_only_reported(tmp_path):
    entries = _entries("gym")
    meta = json.loads(entries["meta.json"])
    base = read_session(ZIPS["gym"])
    meta["clock_anchor_end"]["unix_time_ns"] += 100_000_000  # drift 12 ms -> 112 ms
    entries["meta.json"] = json.dumps(meta).encode()
    s = read_session(_write_zip(tmp_path / "drift.zip", entries))
    assert s.report["drift_corrected"] is True and base.report["drift_corrected"] is False
    shift = s.acc.t_ns[-1] - base.acc.t_ns[-1]
    assert 110_000_000 < shift <= 112_000_000  # end maps onto the end anchor
    assert abs(s.acc.t_ns[0] - base.acc.t_ns[0]) < 1_000_000


def test_resolve_segments_splits_rest_around_other_and_applies_overrides():
    sec = 1_000_000_000
    marks = [
        {
            "segment_id": i,
            "t_start_ns": a * sec,
            "t_end_ns": b * sec,
            "source": "watch",
            "step": None,
        }
        for i, (a, b) in enumerate([(0, 10), (20, 30), (50, 60), (40, 45)], start=1)
    ]
    kinds = ["set", "set", "set", "other"]
    labels = {"segments": [{"segment_id": i, "kind": k} for i, k in enumerate(kinds, start=1)]}
    rests = [s for s in resolve_segments("gym", marks, labels) if s.implicit]
    assert [(r.t_start_ns // sec, r.t_end_ns // sec) for r in rests] == [
        (12, 18),
        (32, 38),
        (47, 48),
    ]
    assert [r.segment_id for r in rests] == [-1, -2, -3]
    labels["segments"][1]["t_start_ns"] = 25 * sec  # override moves set 2's start
    rests = [s for s in resolve_segments("gym", marks, labels) if s.implicit]
    assert (rests[0].t_start_ns // sec, rests[0].t_end_ns // sec) == (12, 23)
    # No labels: gym marks are "unlabeled" and produce no rest; calibration defaults to its kind.
    assert {s.kind for s in resolve_segments("gym", marks, None)} == {"unlabeled"}
    assert {s.kind for s in resolve_segments("calibration", marks, None)} == {"calibration"}


def test_cli_prints_stats_and_segment_table(capsys):
    assert A.main([str(ZIPS["gym"])]) == 0
    out = capsys.readouterr().out
    assert "rate_hz" in out and "implicit" in out and "bench" in out
    assert A.schema_dir().joinpath("meta.schema.json").is_file()
