import numpy as np
import pandas as pd
import pytest

from workout_ml.data.preprocessor import OutlierRemover

COLS = ["acc_x", "acc_y"]


def make_df(set1, set2):
    n1, n2 = len(set1), len(set2)
    idx = pd.date_range("2019-01-14 14:00:00", periods=n1 + n2, freq="200ms")
    df = pd.DataFrame(
        {
            "acc_x": np.concatenate([set1, set2]),
            "acc_y": np.concatenate([set1, set2]),
            "set": [1] * n1 + [2] * n2,
        },
        index=idx,
    )
    return df


def test_spike_is_marked_then_interpolated_without_dropping_rows():
    base = np.linspace(-1, 1, 200)
    spiked = base.copy()
    spiked[100] = 100.0
    df = make_df(spiked, 10 + np.linspace(-0.1, 0.1, 200))
    out = OutlierRemover.clean_outliers(df, COLS, method="chauvenet", scope="set", C=2)
    assert len(out) == len(df)
    assert out.index.equals(df.index)
    assert not out[COLS].isna().any().any()
    # linear interpolation between the neighbours of the spike
    expected = (base[99] + base[101]) / 2
    assert out["acc_x"].iloc[100] == pytest.approx(expected, abs=1e-9)
    # everything else untouched
    mask = np.ones(len(df), bool)
    mask[100] = False
    np.testing.assert_allclose(out["acc_x"].to_numpy()[mask], df["acc_x"].to_numpy()[mask])


def test_statistics_are_per_set():
    set1 = np.linspace(-1, 1, 200)  # value 0 is perfectly normal here
    set2 = 10 + np.linspace(-0.1, 0.1, 200)
    set2[50] = 0.0  # same value, extreme within set 2
    df = make_df(set1, set2)
    out = OutlierRemover.clean_outliers(df, COLS, method="chauvenet", scope="set")
    # set 1 untouched, including its value near 0
    np.testing.assert_allclose(out["acc_x"].iloc[:200], set1)
    # only the extreme value in set 2 was replaced, and by something near the set mean
    changed = np.flatnonzero(~np.isclose(out["acc_x"].iloc[200:].to_numpy(), set2))
    assert list(changed) == [50]
    assert out["acc_x"].iloc[250] == pytest.approx(10, abs=0.2)


def test_edge_outlier_is_filled_from_nearest_value():
    set1 = np.linspace(-1, 1, 200)
    set1[0] = 100.0
    out = OutlierRemover.clean_outliers(make_df(set1, set1), COLS)
    assert out["acc_x"].iloc[0] == pytest.approx(set1[1])


@pytest.mark.parametrize("method", ["iqr", "lof"])
def test_other_methods_keep_the_mark_and_interpolate_contract(method):
    rng = np.random.default_rng(0)
    set1 = rng.normal(0, 1, 120)
    set1[60] = 50.0
    set2 = rng.normal(5, 1, 120)
    df = make_df(set1, set2)
    out = OutlierRemover.clean_outliers(df, COLS, method=method, scope="set", n=10)
    assert len(out) == len(df)
    assert not out[COLS].isna().any().any()
    assert abs(out["acc_x"].iloc[60]) < 10


def test_label_scope_and_unknown_options_are_rejected():
    df = make_df(np.arange(50.0), np.arange(50.0))
    with pytest.raises(ValueError):
        OutlierRemover.clean_outliers(df, COLS, scope="label")
    with pytest.raises(ValueError):
        OutlierRemover.clean_outliers(df, COLS, method="nope")
    assert not hasattr(OutlierRemover, "remove_outliers")


def test_constant_set_is_left_alone():
    df = make_df(np.full(50, 3.0), np.full(50, 3.0))
    out = OutlierRemover.clean_outliers(df, COLS)
    np.testing.assert_allclose(out[COLS].to_numpy(), df[COLS].to_numpy())
