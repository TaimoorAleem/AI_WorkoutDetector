import logging

import pandas as pd

from workout_ml.data.preprocessor import DataLoader

ACC_COLS = "epoch (ms),time (01:00),elapsed (s),x-axis (g),y-axis (g),z-axis (g)"
GYR_COLS = "epoch (ms),time (01:00),elapsed (s),x-axis (deg/s),y-axis (deg/s),z-axis (deg/s)"
T0_MS = 1547476969165


def _write(path, header, step_ms, n, t0):
    rows = [header]
    for i in range(n):
        epoch = t0 + i * step_ms
        ts = pd.Timestamp(epoch, unit="ms").strftime("%Y-%m-%dT%H.%M.%S.%f")[:-3]
        rows.append(f"{epoch},{ts},{i * step_ms / 1000:.3f},0.5,1.0,-0.5")
    path.write_text("\n".join(rows) + "\n")


def _stem(prefix, k):
    return f"{prefix}_MetaWear_2019-01-14T14.22.{k:02d}.165_C42732BE255C"


def _acc(stem, version="1.4.4"):
    return f"{stem}_Accelerometer_12.500Hz_{version}.csv"


def _gyr(stem, version="1.4.4"):
    return f"{stem}_Gyroscope_25.000Hz_{version}.csv"


def _make(tmp_path, order, *, only_acc=(), duplicates=()):
    """Write sets in the given creation order; returns {prefix: stem}."""
    stems = {}
    for k, prefix in enumerate(order):
        stems[prefix] = _stem(prefix, k)
    for prefix in order:
        stem = stems[prefix]
        t0 = T0_MS + 600_000 * sorted(stems).index(prefix)
        _write(tmp_path / _acc(stem), ACC_COLS, 80, 100, t0)
        if prefix not in only_acc:
            _write(tmp_path / _gyr(stem), GYR_COLS, 40, 200, t0)
        if prefix in duplicates:
            _write(tmp_path / _acc(stem, "1.4.41"), ACC_COLS, 80, 100, t0 + 86_400_000)
            _write(tmp_path / _gyr(stem, "1.4.41"), GYR_COLS, 40, 200, t0 + 86_400_000)
    return stems


def _set_by_stem_label(df):
    return df.groupby("label")["set"].first().to_dict()


def test_set_ids_are_deterministic_and_independent_of_file_order(tmp_path):
    names = ["A-bench-heavy", "B-squat-medium", "C-row-heavy"]
    d1, d2 = tmp_path / "one", tmp_path / "two"
    d1.mkdir()
    d2.mkdir()
    _make(d1, names)
    _make(d2, list(reversed(names)))
    df1 = DataLoader(d1).load_data()
    df2 = DataLoader(d2).load_data()
    # ids are 1..n over the sorted stems
    assert _set_by_stem_label(df1) == {"bench": 1, "squat": 2, "row": 3}
    assert _set_by_stem_label(df1) == _set_by_stem_label(df2)


def test_load_sets_returns_sensor_sets_with_filename_metadata(tmp_path):
    _make(tmp_path, ["A-bench-heavy", "B-squat-medium2"])
    sets = DataLoader(tmp_path).load_sets()
    assert [s.meta["set"] for s in sets] == [1, 2]
    assert sets[1].meta["participant"] == "B"
    assert sets[1].meta["label"] == "squat"
    assert sets[1].meta["category"] == "medium"  # trailing digits stripped
    assert sets[0].acc.unit == "g" and sets[0].gyr.unit == "deg/s"
    assert len(sets[0].acc) == 100 and len(sets[0].gyr) == 200


def test_unpaired_stem_warns_and_is_dropped(tmp_path, caplog):
    _make(
        tmp_path, ["A-bench-heavy", "B-squat-medium", "C-row-heavy"], only_acc=("B-squat-medium",)
    )
    loader = DataLoader(tmp_path)
    with caplog.at_level(logging.WARNING, logger="workout_ml.data.preprocessor"):
        df = loader.load_data()
    assert any("unpaired" in r.message and "B-squat-medium" in r.message for r in caplog.records)
    assert len(loader.unpaired_stems) == 1
    assert set(df["label"]) == {"bench", "row"}
    assert sorted(df["set"].unique()) == [1, 2]


def test_duplicate_reexport_files_are_ignored(tmp_path, caplog):
    _make(tmp_path, ["A-bench-heavy", "B-squat-medium"], duplicates=("A-bench-heavy",))
    loader = DataLoader(tmp_path)
    with caplog.at_level(logging.WARNING, logger="workout_ml.data.preprocessor"):
        df = loader.load_data()
    assert len(loader.duplicate_files) == 2
    assert len(loader.unpaired_stems) == 0
    assert df["set"].nunique() == 2
    # the kept file is the first in sorted order (1.4.4), not the +24h copy
    first = df[df["label"] == "bench"]
    assert first.index.min() == pd.Timestamp(T0_MS, unit="ms").floor("200ms")
    assert first.index.max() < pd.Timestamp(T0_MS, unit="ms") + pd.Timedelta(hours=1)
