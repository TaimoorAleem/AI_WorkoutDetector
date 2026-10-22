"""Leakage-safe modelling: sklearn pipelines fitted inside the training fold, evaluated by LOPO.

Principles (docs/specs/phase0-leakage-fix.md):
- anything fitted (PCA, scalers, feature selection, hyper-parameters) is fitted on training
  rows of the current fold only, via sklearn ``Pipeline`` objects;
- the outer split is leave-one-participant-out, and any inner CV is grouped by participant.
"""

from __future__ import annotations

import logging
from dataclasses import dataclass, field

import numpy as np
import pandas as pd
from sklearn.base import BaseEstimator, ClassifierMixin, TransformerMixin, clone
from sklearn.decomposition import PCA
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import accuracy_score, confusion_matrix, f1_score
from sklearn.model_selection import GridSearchCV, GroupKFold, LeaveOneGroupOut, cross_val_score
from sklearn.naive_bayes import GaussianNB
from sklearn.neighbors import KNeighborsClassifier
from sklearn.neural_network import MLPClassifier
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import StandardScaler
from sklearn.tree import DecisionTreeClassifier

logger = logging.getLogger(__name__)

RAW_COLUMNS = ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]
DERIVED_COLUMNS = ["acc_r", "gyr_r"]
PCA_PREFIX = "pca_"


# --------------------------------------------------------------------------- PCA (in-fold)
class PCAFeatures(BaseEstimator, TransformerMixin):
    """Normalise the raw sensor columns and append ``pca_1..n``.

    Mean and range (max - min) normalisation and the PCA itself are fitted on the rows
    passed to ``fit`` only (the training rows of a fold). ``transform`` keeps all input
    columns and appends the components.
    """

    def __init__(self, columns=None, n_components=3):
        self.columns = columns
        self.n_components = n_components

    def fit(self, X, y=None):
        cols = list(self.columns if self.columns is not None else RAW_COLUMNS)
        values = X[cols].to_numpy(dtype=float)
        self.mean_ = values.mean(axis=0)
        rng = values.max(axis=0) - values.min(axis=0)
        self.range_ = np.where(rng > 0, rng, 1.0)
        self.pca_ = PCA(n_components=self.n_components).fit((values - self.mean_) / self.range_)
        self.columns_ = cols
        return self

    def transform(self, X):
        values = X[self.columns_].to_numpy(dtype=float)
        comps = self.pca_.transform((values - self.mean_) / self.range_)
        out = X.copy()
        for i in range(self.n_components):
            out[f"{PCA_PREFIX}{i + 1}"] = comps[:, i]
        return out


# --------------------------------------------------------------------------- feature sets
@dataclass(frozen=True)
class FeatureSet:
    """A named set of *input* columns, plus whether PCA components are appended in-fold."""

    name: str
    columns: tuple
    use_pca: bool = False
    n_pca: int = 3
    n_features_override: int | None = None

    @property
    def n_features(self):
        if self.n_features_override is not None:
            return self.n_features_override
        return len(self.columns) + (self.n_pca if self.use_pca else 0)


def build_feature_sets(columns, n_pca=3):
    """The four feature sets of the baseline, matched by column name pattern.

    - basic: the six raw sensor columns
    - basic+derived+pca: + acc_r, gyr_r and in-fold PCA(3)
    - +temporal: + columns containing ``_temp_``
    - all: + columns containing ``_freq_`` or ``_pse`` (as in the original comparison, so
      ``*_max_freq`` is not included)
    """
    columns = list(columns)
    missing = [c for c in RAW_COLUMNS if c not in columns]
    if missing:
        raise ValueError(f"Missing raw sensor columns: {missing}")
    derived = [c for c in DERIVED_COLUMNS if c in columns]
    temporal = [c for c in columns if "_temp_" in c]
    frequency = [c for c in columns if ("_freq_" in c) or ("_pse" in c)]
    sets = [
        FeatureSet("basic", tuple(RAW_COLUMNS), False, n_pca),
        FeatureSet("basic+derived+pca", tuple(RAW_COLUMNS + derived), True, n_pca),
        FeatureSet(
            "basic+derived+pca+temporal", tuple(RAW_COLUMNS + derived + temporal), True, n_pca
        ),
        FeatureSet("all", tuple(RAW_COLUMNS + derived + temporal + frequency), True, n_pca),
    ]
    return sets


# --------------------------------------------------------------------------- models
SEED = 42

MODEL_SPECS = {
    "RF": {
        "cls": RandomForestClassifier,
        "params": {"n_estimators": 200, "min_samples_leaf": 2, "n_jobs": -1, "random_state": SEED},
        "scale": False,
        "grid": {"model__max_depth": [None, 12], "model__min_samples_leaf": [1, 5]},
    },
    "DT": {
        "cls": DecisionTreeClassifier,
        "params": {"min_samples_leaf": 5, "random_state": SEED},
        "scale": False,
        "grid": {"model__min_samples_leaf": [1, 5, 20], "model__criterion": ["gini", "entropy"]},
    },
    "KNN": {
        "cls": KNeighborsClassifier,
        "params": {"n_neighbors": 5},
        "scale": True,
        "grid": {"model__n_neighbors": [3, 5, 11]},
    },
    "NB": {
        "cls": GaussianNB,
        "params": {},
        "scale": False,
        "grid": {"model__var_smoothing": [1e-9, 1e-6, 1e-3]},
    },
    "NN": {
        "cls": MLPClassifier,
        "params": {
            "hidden_layer_sizes": (100,),
            "alpha": 1e-3,
            "max_iter": 300,
            "random_state": SEED,
        },
        "scale": True,
        "grid": {"model__alpha": [1e-4, 1e-2], "model__hidden_layer_sizes": [(50,), (100,)]},
    },
}


def model_hyperparameters(model_name):
    """Fixed default hyper-parameters of a model (what is logged as params)."""
    return dict(MODEL_SPECS[model_name]["params"])


def build_pipeline(feature_set, model_name, raw_columns=RAW_COLUMNS):
    """Unfitted pipeline: [PCAFeatures] -> [StandardScaler] -> model."""
    spec = MODEL_SPECS[model_name]
    steps = []
    if feature_set.use_pca:
        steps.append(
            ("pca", PCAFeatures(columns=list(raw_columns), n_components=feature_set.n_pca))
        )
    if spec["scale"]:
        steps.append(("scaler", StandardScaler()))
    steps.append(("model", spec["cls"](**spec["params"])))
    return Pipeline(steps)


class ColumnSubsetClassifier(BaseEstimator, ClassifierMixin):
    """Fits ``estimator`` on a column subset chosen at fit time (e.g. by forward selection)."""

    def __init__(self, estimator=None, columns=None):
        self.estimator = estimator
        self.columns = columns

    def fit(self, X, y):
        self.estimator_ = clone(self.estimator).fit(X[list(self.columns)], y)
        self.classes_ = self.estimator_.classes_
        return self

    def predict(self, X):
        return self.estimator_.predict(X[list(self.columns)])

    def predict_proba(self, X):
        return self.estimator_.predict_proba(X[list(self.columns)])


# --------------------------------------------------------------------------- forward selection
def forward_selection(X, y, groups, candidates, max_features=10, n_splits=4):
    """Greedy forward selection with a decision tree and participant-grouped inner CV.

    Must be called with training-fold rows only. Returns the selected columns in order.
    """
    n_splits = min(n_splits, len(np.unique(groups)))
    cv = GroupKFold(n_splits=n_splits)
    values = X[list(candidates)].to_numpy(dtype=float)
    selected, remaining = [], list(range(len(candidates)))
    tree = DecisionTreeClassifier(min_samples_leaf=5, random_state=SEED)
    for _ in range(min(max_features, len(candidates))):
        best_score, best_idx = -np.inf, None
        for idx in remaining:
            score = cross_val_score(
                tree, values[:, selected + [idx]], y, groups=groups, cv=cv, scoring="f1_macro"
            ).mean()
            if score > best_score:
                best_score, best_idx = score, idx
        selected.append(best_idx)
        remaining.remove(best_idx)
    return [candidates[i] for i in selected]


# --------------------------------------------------------------------------- evaluation
def majority_vote(predictions):
    """Most frequent prediction. Ties break to the first class in sorted order."""
    values, counts = np.unique(np.asarray(predictions), return_counts=True)  # sorted values
    return values[np.argmax(counts)]  # argmax returns the first maximum


def set_level_accuracy(sets, y_true, y_pred):
    """Accuracy of the majority vote of window predictions per set vs the set label."""
    frame = pd.DataFrame({"set": np.asarray(sets), "true": np.asarray(y_true), "pred": y_pred})
    voted = frame.groupby("set")["pred"].agg(majority_vote)
    truth = frame.groupby("set")["true"].first()
    return float((voted == truth.loc[voted.index]).mean())


def lopo_splits(groups):
    """LOPO splitter yielding (train_idx, test_idx); one fold per distinct participant."""
    groups = np.asarray(groups)
    dummy = np.zeros(len(groups))
    return list(LeaveOneGroupOut().split(dummy, groups=groups))


@dataclass
class LopoResult:
    feature_set: str
    model: str
    n_features: int
    folds: pd.DataFrame
    y_true: np.ndarray
    y_pred: np.ndarray
    classes: list
    summary: dict = field(default_factory=dict)


def _fold_metrics(y_test, y_pred, sets_test, classes):
    present = np.unique(y_test)
    row = {
        "n_windows": len(y_test),
        "n_sets": int(len(np.unique(sets_test))),
        "window_acc": accuracy_score(y_test, y_pred),
        "window_macro_f1": f1_score(
            y_test, y_pred, labels=present, average="macro", zero_division=0
        ),
        "set_acc": set_level_accuracy(sets_test, y_test, y_pred),
    }
    for c in classes:
        mask = y_test == c
        row[f"recall_{c}"] = float((y_pred[mask] == c).mean()) if mask.any() else np.nan
    return row


def summarize_folds(folds, classes):
    """Mean and sample std (ddof=1) across folds; NaN-aware for per-class recall."""
    summary = {}
    names = {"window_acc": "window_acc", "window_macro_f1": "window_macro_f1", "set_acc": "set_acc"}
    for col, name in names.items():
        summary[f"lopo_{name}_mean"] = float(folds[col].mean())
        summary[f"lopo_{name}_std"] = float(folds[col].std(ddof=1))
    for c in classes:
        if folds[f"recall_{c}"].notna().any():
            summary[f"lopo_recall_{c}_mean"] = float(folds[f"recall_{c}"].mean())
    return summary


def fit_estimator(pipeline, X, y, groups, *, tune=False, grid=None, inner_splits=4):
    """Fit one pipeline on training rows. Returns (fitted_estimator, info dict).

    With ``tune`` a small GridSearchCV (macro-F1, GroupKFold by participant) runs on the
    training rows only.
    """
    info = {}
    if tune:
        n_splits = min(inner_splits, len(np.unique(groups)))
        search = GridSearchCV(
            pipeline,
            grid,
            cv=GroupKFold(n_splits=n_splits),
            scoring="f1_macro",
            refit=True,
        )
        search.fit(X, y, groups=groups)
        info["best_params"] = dict(search.best_params_)
        return search.best_estimator_, info
    return pipeline.fit(X, y), info


@dataclass
class AugmentedTrain:
    """Featurised augmented copies, usable as extra *training* rows only (D-018).

    ``participants`` is the source participant of each row; in a LOPO fold the rows of the
    held-out participant are dropped, so no copy of a held-out set is ever trained on.
    """

    X: pd.DataFrame
    y: np.ndarray
    participants: np.ndarray


def training_rows(X, y, participants, train, held_out, augment=None):
    """Training (X, y, groups) of a fold: originals ``train`` plus augmented copies of the
    training participants' sets (copies of ``held_out`` are excluded)."""
    X_tr, y_tr, g_tr = X.iloc[train], y[train], participants[train]
    if augment is None:
        return X_tr, y_tr, g_tr
    keep = np.asarray(augment.participants) != held_out
    X_tr = pd.concat([X_tr, augment.X.loc[keep, list(X.columns)]])
    return (
        X_tr,
        np.concatenate([y_tr, np.asarray(augment.y)[keep]]),
        np.concatenate([g_tr, np.asarray(augment.participants)[keep]]),
    )


def evaluate_lopo(
    X,
    y,
    participants,
    sets,
    feature_set,
    model_name,
    *,
    tune=False,
    inner_splits=4,
    selector=None,
    selection_cache=None,
    augment=None,
):
    """Leave-one-participant-out evaluation of one (feature_set, model) combination.

    Args:
        X: DataFrame containing at least ``feature_set.columns``
        y, participants, sets: aligned label / participant / set arrays
        selector: optional ``f(X_train, y_train, groups_train) -> list[str]`` (forward
            selection). Run on the training fold only; results cached per fold in
            ``selection_cache`` so every model of the feature set shares one selection.
        augment: optional ``AugmentedTrain``; its rows (minus the held-out participant's)
            are added to the training rows of each fold. Test rows are never augmented.
    """
    y = np.asarray(y)
    participants = np.asarray(participants)
    sets = np.asarray(sets)
    classes = sorted(np.unique(y))
    X = X[list(feature_set.columns)]
    grid = MODEL_SPECS[model_name]["grid"]

    rows, all_true, all_pred = [], [], []
    for fold, (train, test) in enumerate(lopo_splits(participants)):
        held_out = participants[test][0]
        assert held_out not in set(participants[train]), "participant in both train and test"
        X_tr, y_tr, g_tr = training_rows(X, y, participants, train, held_out, augment)
        selected = None
        if selector is not None:
            key = held_out
            if selection_cache is not None and key in selection_cache:
                selected = selection_cache[key]
            else:
                selected = selector(X_tr, y_tr, g_tr)
                if selection_cache is not None:
                    selection_cache[key] = selected
        cols = list(selected) if selected is not None else list(X.columns)
        fs = feature_set if selected is None else selected_feature_set(selected)
        estimator, info = fit_estimator(
            build_pipeline(fs, model_name),
            X_tr[cols],
            y_tr,
            g_tr,
            tune=tune,
            grid=grid,
            inner_splits=inner_splits,
        )
        pred = estimator.predict(X.iloc[test][cols])
        row = {"fold": fold, "test_participant": held_out}
        row.update(_fold_metrics(y[test], pred, sets[test], classes))
        if "best_params" in info:
            row["best_params"] = str(info["best_params"])
        if selected is not None:
            row["selected_features"] = ";".join(selected)
        rows.append(row)
        all_true.append(y[test])
        all_pred.append(np.asarray(pred))
        logger.info(
            f"  [{feature_set.name} | {model_name}] fold {fold} (test {held_out}): "
            f"acc={row['window_acc']:.3f} f1={row['window_macro_f1']:.3f} "
            f"set_acc={row['set_acc']:.3f}"
        )

    folds = pd.DataFrame(rows)
    return LopoResult(
        feature_set=feature_set.name,
        model=model_name,
        n_features=feature_set.n_features,
        folds=folds,
        y_true=np.concatenate(all_true),
        y_pred=np.concatenate(all_pred),
        classes=classes,
        summary=summarize_folds(folds, classes),
    )


def selected_feature_set(selected):
    """Feature set for forward-selected columns (no PCA: it is not among the candidates)."""
    return FeatureSet("forward-selected", tuple(selected), use_pca=False)


def pooled_confusion(result_or_pairs, classes):
    """Confusion matrix (rows = true, columns = predicted) over pooled predictions."""
    y_true, y_pred = result_or_pairs
    return confusion_matrix(y_true, y_pred, labels=classes)
