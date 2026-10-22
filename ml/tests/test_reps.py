import logging

import numpy as np
import pandas as pd
import pytest
from scipy.signal import argrelextrema

from workout_ml import config
from workout_ml.features.DataTransformation import LowPassFilter
from workout_ml.reps.counter import RepResult, add_magnitudes, count_peaks, count_set
from workout_ml.reps.evaluate import evaluate_counter, summarize

FS = config.REP_CONFIG["sampling_frequency"]
ORDER = config.REP_CONFIG["order"]


def make_signal(cycles, freq=0.4, seed=0, offset=1.0, noise=0.02):
    """Sine-like signal at 5 Hz with a known number of full cycles."""
    n = int(round(cycles / freq * FS))
    t = np.arange(n) / FS
    rng = np.random.default_rng(seed)
    return offset + 0.5 * np.sin(2 * np.pi * freq * t) + rng.normal(0, noise, n)


def make_set_df(signal, label="bench", category="heavy", set_id=1):
    n = len(signal)
    return pd.DataFrame(
        {
            "acc_x": signal,
            "acc_y": np.zeros(n),
            "acc_z": np.zeros(n),
            "gyr_x": signal,
            "gyr_y": np.zeros(n),
            "gyr_z": np.zeros(n),
            "label": label,
            "category": category,
            "set": set_id,
        }
    )


@pytest.mark.parametrize("cycles,freq", [(5, 0.4), (10, 0.4), (5, 0.3), (10, 0.3)])
@pytest.mark.parametrize("exercise", ["bench", "squat", "row", "ohp", "dead"])
def test_count_peaks_known_cycles(cycles, freq, exercise):
    sig = make_signal(cycles, freq)
    cutoff = config.REP_CONFIG["exercises"][exercise]["cutoff"]
    peaks = count_peaks(sig, FS, cutoff, ORDER)
    assert len(peaks) == cycles


def test_count_peaks_does_not_mutate_input():
    sig = make_signal(5)
    copy = sig.copy()
    count_peaks(sig, FS, 0.4, ORDER)
    np.testing.assert_array_equal(sig, copy)


def test_count_set_picks_columns():
    sig = make_signal(5)
    df = make_set_df(sig)
    row = count_set(df, "row")
    bench = count_set(df, "bench")
    assert isinstance(row, RepResult)
    assert row.column == "gyr_x" and row.cutoff == 0.65
    assert bench.column == "acc_r" and bench.cutoff == 0.4
    assert row.count == len(row.peak_indices) == 5
    assert bench.count == 5


def test_count_set_unknown_exercise_falls_back(caplog):
    df = make_set_df(make_signal(5))
    with caplog.at_level(logging.WARNING):
        res = count_set(df, "curl")
    assert res.column == "acc_r" and res.cutoff == 0.4
    assert "curl" in caplog.text


def test_count_set_rest_raises():
    with pytest.raises(ValueError):
        count_set(make_set_df(make_signal(5)), "rest")


def test_count_set_does_not_mutate_input():
    df = make_set_df(make_signal(5))
    cols = list(df.columns)
    count_set(df, "bench")
    assert list(df.columns) == cols


def test_add_magnitudes_correct_and_pure():
    df = pd.DataFrame(
        {
            "acc_x": [3.0, 0.0],
            "acc_y": [4.0, 1.0],
            "acc_z": [0.0, 0.0],
            "gyr_x": [1.0, 2.0],
            "gyr_y": [2.0, 0.0],
            "gyr_z": [2.0, 0.0],
        }
    )
    before = df.copy()
    out = add_magnitudes(df)
    np.testing.assert_allclose(out["acc_r"], [5.0, 1.0])
    np.testing.assert_allclose(out["gyr_r"], [3.0, 2.0])
    pd.testing.assert_frame_equal(df, before)
    assert "acc_r" not in df.columns


def _multi_set_df():
    parts = [
        make_set_df(make_signal(5, seed=1), "bench", "heavy", 1),  # exact
        make_set_df(make_signal(10, seed=2), "bench", "medium", 2),  # exact
        make_set_df(make_signal(6, seed=3), "squat", "heavy", 3),  # +1
        make_set_df(make_signal(8, seed=4), "squat", "medium", 4),  # -2
        make_set_df(make_signal(5, seed=5), "rest", "heavy", 5),  # excluded
    ]
    return pd.concat(parts, ignore_index=True)


def test_evaluate_counter_and_summarize():
    res = evaluate_counter(_multi_set_df())
    assert list(res.columns) == ["set", "label", "category", "expected", "predicted", "error"]
    assert list(res["set"]) == [1, 2, 3, 4]
    assert "rest" not in set(res["label"])
    assert list(res["expected"]) == [5, 10, 5, 10]
    assert list(res["predicted"]) == [5, 10, 6, 8]
    assert list(res["error"]) == [0, 0, 1, -2]

    s = summarize(res)
    assert s.loc["overall", "mae"] == pytest.approx(0.75)
    assert s.loc["overall", "exact"] == pytest.approx(0.5)
    assert s.loc["overall", "within_1"] == pytest.approx(0.75)
    assert s.loc["bench", "mae"] == 0 and s.loc["bench", "exact"] == 1
    assert s.loc["squat", "mae"] == pytest.approx(1.5)
    assert s.loc["squat", "exact"] == 0
    assert s.loc["squat", "within_1"] == pytest.approx(0.5)


def _reference_script_count(values, column_cutoff):
    """The original exploration script's algorithm, inlined."""
    df = pd.DataFrame({"v": values})
    data = LowPassFilter().low_pass_filter(
        df, col="v", sampling_frequency=1000 / 200, cutoff_frequency=column_cutoff, order=10
    )
    indexes = argrelextrema(data["v_lowpass"].values, np.greater)
    return len(data.iloc[indexes]), indexes[0]


@pytest.mark.parametrize("seed", range(5))
@pytest.mark.parametrize("cutoff", [0.35, 0.4, 0.65])
def test_golden_matches_original_script(seed, cutoff):
    rng = np.random.default_rng(seed)
    sig = make_signal(7, freq=0.45, seed=seed) + rng.normal(0, 0.1, int(round(7 / 0.45 * FS)))
    ref_count, ref_idx = _reference_script_count(sig, cutoff)
    peaks = count_peaks(sig, FS, cutoff, ORDER)
    assert len(peaks) == ref_count
    np.testing.assert_array_equal(peaks, ref_idx)
