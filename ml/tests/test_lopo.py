import numpy as np
import pandas as pd
import pytest

from workout_ml.models import lopo

RAW = lopo.RAW_COLUMNS


def test_lopo_has_one_fold_per_participant_and_never_mixes_them():
    participants = np.repeat(list("ABCDE"), [12, 7, 9, 5, 11])
    folds = lopo.lopo_splits(participants)
    assert len(folds) == 5
    held_out = set()
    for train, test in folds:
        assert set(participants[train]).isdisjoint(participants[test])
        assert len(set(participants[test])) == 1
        assert len(train) + len(test) == len(participants)
        held_out.add(participants[test][0])
    assert held_out == set("ABCDE")


def raw_frame(n, seed):
    rng = np.random.default_rng(seed)
    return pd.DataFrame(rng.normal(size=(n, 6)) * [1, 2, 3, 40, 50, 60], columns=RAW)


def test_pca_is_fitted_on_training_rows_only():
    train, test_a, test_b = raw_frame(100, 0), raw_frame(40, 1), raw_frame(40, 2) + 1000
    # Two datasets that differ only in their (held-out) test rows
    full_a = pd.concat([train, test_a], ignore_index=True)
    full_b = pd.concat([train, test_b], ignore_index=True)
    idx = np.arange(len(train))
    fit_a = lopo.PCAFeatures(RAW, 3).fit(full_a.iloc[idx])
    fit_b = lopo.PCAFeatures(RAW, 3).fit(full_b.iloc[idx])
    np.testing.assert_array_equal(fit_a.pca_.components_, fit_b.pca_.components_)
    np.testing.assert_array_equal(fit_a.mean_, fit_b.mean_)
    np.testing.assert_array_equal(fit_a.range_, fit_b.range_)
    pd.testing.assert_frame_equal(fit_a.transform(train), fit_b.transform(train))
    # ... whereas fitting on every row (the old behaviour) does depend on the test rows
    leaky = lopo.PCAFeatures(RAW, 3).fit(full_b)
    assert not np.allclose(leaky.mean_, fit_a.mean_)


def test_pca_transform_appends_components_and_keeps_inputs():
    df = raw_frame(50, 3)
    out = lopo.PCAFeatures(RAW, 3).fit(df).transform(df)
    assert list(out.columns) == RAW + ["pca_1", "pca_2", "pca_3"]
    pd.testing.assert_frame_equal(out[RAW], df)


def test_majority_vote_and_tie_break():
    assert lopo.majority_vote(["squat", "bench", "squat"]) == "squat"
    # ties break to the first class in sorted order
    assert lopo.majority_vote(["squat", "bench"]) == "bench"
    assert lopo.majority_vote(["row", "ohp", "dead", "dead", "row", "ohp"]) == "dead"
    assert lopo.majority_vote(["only"]) == "only"


def test_set_level_accuracy_hand_example():
    sets = [1, 1, 1, 2, 2, 2, 3, 3]
    y_true = ["bench"] * 3 + ["squat"] * 3 + ["row"] * 2
    y_pred = [
        "bench",
        "bench",
        "ohp",  # set 1: vote bench -> correct
        "ohp",
        "ohp",
        "squat",  # set 2: vote ohp -> wrong
        "ohp",
        "row",
    ]  # set 3: tie -> "ohp" (sorted first) -> wrong
    assert lopo.set_level_accuracy(sets, y_true, y_pred) == pytest.approx(1 / 3)


def synthetic_windows(seed=0, informative=True, classes=("bench", "squat", "row")):
    """5 participants x 2 sets per class x 25 windows; B lacks the 'row' class."""
    rng = np.random.default_rng(seed)
    rows, set_id = [], 0
    for p in "ABCDE":
        for k, c in enumerate(classes):
            if p == "B" and c == "row":
                continue
            for _ in range(2):
                set_id += 1
                centre = np.zeros(6)
                if informative:
                    centre[k] = 5.0
                x = rng.normal(size=(25, 6)) + centre
                for r in x:
                    rows.append([*r, p, c, set_id])
    df = pd.DataFrame(rows, columns=[*RAW, "participant", "label", "set"])
    df["acc_r"] = np.sqrt(df.acc_x**2 + df.acc_y**2 + df.acc_z**2)
    df["gyr_r"] = np.sqrt(df.gyr_x**2 + df.gyr_y**2 + df.gyr_z**2)
    return df


@pytest.mark.parametrize("model", ["RF", "DT", "KNN", "NB", "NN"])
def test_evaluate_lopo_on_separable_data(model):
    df = synthetic_windows()
    fs = lopo.build_feature_sets(RAW + ["acc_r", "gyr_r"])[1]  # includes in-fold PCA
    res = lopo.evaluate_lopo(
        df[RAW + ["acc_r", "gyr_r"]], df["label"], df["participant"], df["set"], fs, model
    )
    assert len(res.folds) == 5
    assert res.folds["window_acc"].mean() > 0.9
    assert res.summary["lopo_set_acc_mean"] > 0.9
    # participant B has no 'row' windows: recall is NaN, not 0, and F1 uses present classes
    fold_b = res.folds[res.folds["test_participant"] == "B"].iloc[0]
    assert np.isnan(fold_b["recall_row"])
    assert fold_b["window_macro_f1"] > 0.8
    assert len(res.y_true) == len(df)


def test_uninformative_features_score_near_chance():
    """Leak canary: with no signal, LOPO accuracy must not be high."""
    df = synthetic_windows(informative=False)
    fs = lopo.build_feature_sets(RAW + ["acc_r", "gyr_r"])[0]
    res = lopo.evaluate_lopo(df[RAW], df["label"], df["participant"], df["set"], fs, "RF")
    assert res.folds["window_acc"].mean() < 0.6


def test_tuning_runs_with_grouped_inner_cv():
    df = synthetic_windows()
    fs = lopo.build_feature_sets(RAW + ["acc_r", "gyr_r"])[0]
    res = lopo.evaluate_lopo(
        df[RAW], df["label"], df["participant"], df["set"], fs, "DT", tune=True
    )
    assert "best_params" in res.folds.columns
    assert res.folds["window_acc"].mean() > 0.9


def test_forward_selection_runs_inside_each_training_fold():
    df = synthetic_windows()
    seen = []

    def selector(X_train, y_train, groups_train):
        seen.append(set(groups_train))
        return lopo.forward_selection(X_train, y_train, groups_train, RAW, max_features=2)

    fs = lopo.FeatureSet("forward-selected", tuple(RAW), False, n_features_override=2)
    res = lopo.evaluate_lopo(
        df[RAW], df["label"], df["participant"], df["set"], fs, "DT", selector=selector
    )
    assert len(seen) == 5
    for held_out, groups in zip("ABCDE", seen):
        assert held_out not in groups
    assert res.folds["selected_features"].notna().all()


def test_feature_sets_follow_name_patterns():
    cols = RAW + ["acc_r", "gyr_r", "acc_x_temp_mean_ws_5", "acc_x_freq_0.5_Hz_ws_10", "acc_x_pse"]
    cols += ["acc_x_max_freq", "acc_x_freq_weighted"]
    sets = {s.name: s for s in lopo.build_feature_sets(cols)}
    assert list(sets) == ["basic", "basic+derived+pca", "basic+derived+pca+temporal", "all"]
    assert sets["basic"].columns == tuple(RAW)
    assert not sets["basic"].use_pca and sets["all"].use_pca
    assert "acc_x_temp_mean_ws_5" in sets["basic+derived+pca+temporal"].columns
    assert "acc_x_pse" not in sets["basic+derived+pca+temporal"].columns
    assert {"acc_x_freq_0.5_Hz_ws_10", "acc_x_pse", "acc_x_freq_weighted"} <= set(
        sets["all"].columns
    )
    assert "acc_x_max_freq" not in sets["all"].columns
