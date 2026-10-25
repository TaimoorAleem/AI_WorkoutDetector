"""Domain diagnostics (not gates): per-feature PSI and a domain classifier AUC."""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.metrics import roc_auc_score
from sklearn.model_selection import GroupKFold
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

from workout_ml.watch.metrics import psi

AUC_INVESTIGATE = 0.8


def feature_psi(X_mm: pd.DataFrame, X_watch: pd.DataFrame, columns, top: int = 10) -> pd.DataFrame:
    """PSI of every feature, MetaMotion (reference) vs watch, sorted descending."""
    rows = [(c, psi(X_mm[c].to_numpy(), X_watch[c].to_numpy())) for c in columns]
    df = pd.DataFrame(rows, columns=["feature", "psi"]).sort_values("psi", ascending=False)
    return df.reset_index(drop=True).head(top) if top else df.reset_index(drop=True)


def domain_classifier(
    X_mm: pd.DataFrame,
    groups_mm,
    X_watch: pd.DataFrame,
    groups_watch,
    columns,
    n_splits: int = 5,
    watch_grouping: str = "session",
) -> dict:
    """Logistic regression (standardised features) separating MetaMotion (0) from watch (1).

    Grouped CV: MetaMotion by participant, watch by session. With a single watch session the
    watch side is grouped by set instead (flagged in ``watch_grouping``), which is optimistic.
    Returns the pooled out-of-fold AUC and the top contributing features of the full fit.
    """
    columns = list(columns)
    gw = np.asarray(groups_watch).astype(str)
    if len(np.unique(gw)) < 2:
        raise ValueError("domain_classifier needs >= 2 watch groups; pass set ids as groups")
    X = pd.concat([X_mm[columns], X_watch[columns]], ignore_index=True).to_numpy(float)
    y = np.r_[np.zeros(len(X_mm)), np.ones(len(X_watch))]
    groups = np.r_[["mm:" + str(g) for g in groups_mm], ["w:" + g for g in gw]]
    n_groups = len(np.unique(groups))
    cv = GroupKFold(n_splits=min(n_splits, n_groups))
    oof_y, oof_s = [], []
    for tr, te in cv.split(X, y, groups):
        if len(np.unique(y[tr])) < 2:  # need both domains to train; AUC is pooled over folds
            continue
        clf = make_pipeline(
            StandardScaler(), LogisticRegression(max_iter=1000, class_weight="balanced")
        )
        clf.fit(X[tr], y[tr])
        oof_y.append(y[te])
        oof_s.append(clf.decision_function(X[te]))
    auc = float("nan")
    if oof_y and len(np.unique(np.concatenate(oof_y))) == 2:
        auc = float(roc_auc_score(np.concatenate(oof_y), np.concatenate(oof_s)))
    full = make_pipeline(
        StandardScaler(), LogisticRegression(max_iter=1000, class_weight="balanced")
    ).fit(X, y)
    coef = full[-1].coef_[0]
    order = np.argsort(-np.abs(coef))[:8]
    return {
        "auc": auc,
        "n_folds_used": len(oof_y),
        "investigate": bool(auc > AUC_INVESTIGATE),
        "top_features": [(columns[i], float(coef[i])) for i in order],
        "watch_grouping": watch_grouping,
    }
