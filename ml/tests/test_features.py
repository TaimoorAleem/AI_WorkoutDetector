import numpy as np
import pandas as pd

from workout_ml import config
from workout_ml.features.engineer_features import FeatureEngineer
from workout_ml.features.FrequencyAbstraction import FourierTransformation

COLS = config.FEATURE_CONFIG["predictor_columns"]
N = 60  # per set; must exceed the filtfilt pad length


def two_set_frame(v1=0.0, v2=10.0, n=N):
    idx = pd.date_range("2019-01-14 14:00:00", periods=2 * n, freq="200ms")
    df = pd.DataFrame(
        {c: np.r_[np.full(n, v1), np.full(n, v2)] for c in COLS},
        index=idx,
    )
    df["participant"] = ["A"] * n + ["B"] * n
    df["label"] = ["bench"] * n + ["squat"] * n
    df["category"] = ["heavy"] * n + ["medium"] * n
    df["set"] = [1] * n + [2] * n
    return df


def test_no_contribution_from_the_previous_set():
    out = FeatureEngineer(config.FEATURE_CONFIG).engineer_features(two_set_frame())
    s2 = out[out["set"] == 2]
    s1 = out[out["set"] == 1]
    # constant 10 in set 2: every feature must be that of a constant 10 signal
    for col in COLS:
        np.testing.assert_allclose(s2[col], 10.0, atol=1e-6)
        for w in (5, 10):
            np.testing.assert_allclose(s2[f"{col}_temp_mean_ws_{w}"], 10.0, atol=1e-6)
            np.testing.assert_allclose(s2[f"{col}_temp_std_ws_{w}"], 0.0, atol=1e-6)
            np.testing.assert_allclose(s2[f"{col}_temp_min_ws_{w}"], 10.0, atol=1e-6)
        # DC bin of an 11-sample window of constant 10
        np.testing.assert_allclose(s2[f"{col}_freq_0.0_Hz_ws_10"], 110.0, atol=1e-4)
        np.testing.assert_allclose(s1[col], 0.0, atol=1e-6)
    np.testing.assert_allclose(s2["acc_r"], 10 * np.sqrt(3), atol=1e-6)


def test_rows_without_a_full_in_set_window_are_dropped_per_set():
    df = two_set_frame()
    out = FeatureEngineer(config.FEATURE_CONFIG).engineer_features(df)
    for s in (1, 2):
        kept = out[out["set"] == s].index
        original = df[df["set"] == s].index
        assert len(kept) == N - 10
        assert kept.min() == original[10]  # first 10 rows of every set are gone
    assert not out.isna().any().any()


def test_output_columns():
    out = FeatureEngineer(config.FEATURE_CONFIG).engineer_features(two_set_frame())
    assert "duration" not in out.columns
    assert not any(c.startswith("pca_") for c in out.columns)
    for c in ["participant", "label", "category", "set", "acc_r", "gyr_r"]:
        assert c in out.columns
    for c in ["acc_x_temp_mean_ws_5", "acc_x_freq_0.5_Hz_ws_10", "acc_x_pse", "acc_x_max_freq"]:
        assert c in out.columns


def test_rows_of_a_set_do_not_depend_on_other_sets():
    base = two_set_frame()
    other = two_set_frame(v1=-7.0)
    a = FeatureEngineer(config.FEATURE_CONFIG).engineer_features(base)
    b = FeatureEngineer(config.FEATURE_CONFIG).engineer_features(other)
    pd.testing.assert_frame_equal(a[a["set"] == 2], b[b["set"] == 2])


def reference_fft_loop(x, window_size, sampling_rate):
    """The original row-by-row implementation, for one column."""
    freqs = np.round(np.fft.rfftfreq(window_size) * sampling_rate, 3)
    n = len(x)
    amp = {f: np.full(n, np.nan) for f in freqs}
    mx, wt, pse = (np.full(n, np.nan) for _ in range(3))
    for i in range(window_size, n):
        real = np.fft.rfft(x[i - window_size : i + 1]).real
        for j, f in enumerate(freqs):
            amp[f][i] = real[j]
        mx[i] = freqs[np.argmax(real)]
        s = np.sum(real)
        wt[i] = float(np.sum(freqs * real)) / s if s != 0 else 0.0
        psd = np.square(real) / float(len(real))
        ps = np.sum(psd)
        if ps != 0:
            pdf = psd / ps
            pdf = pdf[pdf > 0]
            pse[i] = -np.sum(np.log(pdf) * pdf)
        else:
            pse[i] = 0.0
    return freqs, amp, mx, wt, pse


def test_vectorised_fft_matches_the_original_loop():
    rng = np.random.default_rng(1)
    x = rng.normal(size=80)
    df = pd.DataFrame({"s": x})
    out = FourierTransformation().abstract_frequency(df, ["s"], 10, 5.0)
    freqs, amp, mx, wt, pse = reference_fft_loop(x, 10, 5.0)
    for f in freqs:
        np.testing.assert_allclose(out[f"s_freq_{f}_Hz_ws_10"], amp[f], equal_nan=True)
    np.testing.assert_allclose(out["s_max_freq"], mx, equal_nan=True)
    np.testing.assert_allclose(out["s_freq_weighted"], wt, equal_nan=True)
    np.testing.assert_allclose(out["s_pse"], pse, equal_nan=True)
