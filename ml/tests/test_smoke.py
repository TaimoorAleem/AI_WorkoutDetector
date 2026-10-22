import importlib

import numpy as np
import pandas as pd
import pytest

MODULES = [
    "workout_ml",
    "workout_ml.config",
    "workout_ml.data.preprocessor",
    "workout_ml.features.engineer_features",
    "workout_ml.features.DataTransformation",
    "workout_ml.features.TemporalAbstraction",
    "workout_ml.features.FrequencyAbstraction",
    "workout_ml.models.lopo",
    "workout_ml.models.experiment",
    "workout_ml.tracking",
    "workout_ml.reporting",
    "workout_ml.pipeline",
]


@pytest.mark.parametrize("name", MODULES)
def test_module_imports(name):
    importlib.import_module(name)


ACC_COLS = "epoch (ms),time (01:00),elapsed (s),x-axis (g),y-axis (g),z-axis (g)"
GYR_COLS = "epoch (ms),time (01:00),elapsed (s),x-axis (deg/s),y-axis (deg/s),z-axis (deg/s)"
T0_MS = 1547476969165  # 2019-01-14T14:22:49.165


def _write_csv(path, header, step_ms, n, t0=T0_MS):
    rows = [header]
    for i in range(n):
        epoch = t0 + i * step_ms
        ts = pd.Timestamp(epoch, unit="ms").strftime("%Y-%m-%dT%H.%M.%S.%f")[:-3]
        rows.append(f"{epoch},{ts},{i * step_ms / 1000:.3f},0.{i % 10},1.0,-0.{i % 10}")
    path.write_text("\n".join(rows) + "\n")


@pytest.fixture
def raw_dir(tmp_path):
    for k, prefix in enumerate(("A-bench-heavy", "B-squat-medium")):
        t0 = T0_MS + k * 600_000  # real sets never overlap in time
        stem = f"{prefix}_MetaWear_2019-01-14T14.22.49.165_C42732BE255C"
        _write_csv(tmp_path / f"{stem}_Accelerometer_12.500Hz_1.4.4.csv", ACC_COLS, 80, 250, t0)
        _write_csv(tmp_path / f"{stem}_Gyroscope_25.000Hz_1.4.4.csv", GYR_COLS, 40, 500, t0)
    return tmp_path


def test_dataloader_load_merge_resample(raw_dir):
    from workout_ml.data.preprocessor import DataLoader

    loader = DataLoader(raw_dir)
    acc, gyr = loader.load_data()
    merged = loader.merge_sensors(acc, gyr)
    out = loader.resample_data(merged, sampling_rule="200ms")

    for col in [
        "acc_x",
        "acc_y",
        "acc_z",
        "gyr_x",
        "gyr_y",
        "gyr_z",
        "participant",
        "label",
        "category",
        "set",
    ]:
        assert col in out.columns
    assert set(out["label"]) == {"bench", "squat"}
    assert set(out["participant"]) == {"A", "B"}
    assert set(out["category"]) == {"heavy", "medium"}
    assert len(out) > 0
    for _, g in out.groupby("set"):
        spacing = np.diff(g.index.values) / np.timedelta64(1, "s")
        assert np.allclose(spacing, 0.2)


def test_project_root_contains_ml():
    from workout_ml import config

    assert (config.PROJECT_ROOT / "ml").is_dir()
