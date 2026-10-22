"""LOPO experiment over feature sets x models, tracked in MLflow.

One parent run per invocation, one child run per (feature_set, model). The best child
(by mean LOPO window macro-F1, tiebreak mean set accuracy) is refit on all data and its
model is logged to that child with ``mlflow.sklearn.log_model`` (not registered).
"""

from __future__ import annotations

import logging
import tempfile
from dataclasses import dataclass, field
from pathlib import Path

import mlflow
import mlflow.sklearn
import numpy as np
import pandas as pd
from mlflow.models import infer_signature
from sklearn.metrics import confusion_matrix

from workout_ml import config, reporting, tracking
from workout_ml.features.engineer_features import METADATA_COLUMNS
from workout_ml.models import lopo

logger = logging.getLogger(__name__)

DEFAULT_MODELS = ["RF", "DT", "KNN", "NB", "NN"]
MAX_SELECTED_FEATURES = 10


@dataclass
class ExperimentOutcome:
    parent_run_id: str
    best_run_id: str
    leaderboard: pd.DataFrame
    best: lopo.LopoResult
    pooled_cm: np.ndarray
    results: list = field(default_factory=list)
    report_markdown: str | None = None


def _rank(leaderboard):
    return leaderboard.sort_values(
        by=["lopo_window_macro_f1_mean", "lopo_set_acc_mean"], ascending=False, kind="stable"
    ).reset_index(drop=True)


def _log_child(result, feature_set, model_name, tune, forward, run_name, aug_params, aug_tag):
    """Params, metrics and artifacts for one (feature_set, model) child run."""
    mlflow.set_tag("augmentation", aug_tag)
    mlflow.log_params(
        {
            **aug_params,
            "model": model_name,
            "feature_set": feature_set.name,
            "n_features": feature_set.n_features,
            "tune": tune,
            "forward_selection": forward,
            **{f"hp.{k}": str(v) for k, v in lopo.model_hyperparameters(model_name).items()},
        }
    )
    folds = result.folds
    for i, row in folds.iterrows():
        step = int(row["fold"])
        metrics = {
            "fold_window_acc": row["window_acc"],
            "fold_window_macro_f1": row["window_macro_f1"],
            "fold_set_acc": row["set_acc"],
        }
        for c in result.classes:
            value = row[f"recall_{c}"]
            if not np.isnan(value):
                metrics[f"fold_recall_{c}"] = value
        mlflow.log_metrics({k: float(v) for k, v in metrics.items()}, step=step)
    mlflow.log_metrics(result.summary)
    cm = confusion_matrix(result.y_true, result.y_pred, labels=result.classes)
    with tempfile.TemporaryDirectory() as tmp:
        csv_path = Path(tmp) / "fold_metrics.csv"
        folds.to_csv(csv_path, index=False)
        mlflow.log_artifact(str(csv_path))
        png_path = Path(tmp) / "confusion_matrix.png"
        reporting.plot_confusion(
            cm, result.classes, png_path, f"{run_name}\npooled LOPO confusion (window level)"
        )
        mlflow.log_artifact(str(png_path))


def _refit_final(feature_set, model_name, X, y, participants, *, tune, selector, inner_splits):
    """Refit a (feature_set, model) on all data. Returns (estimator, input DataFrame)."""
    X_fs = X[list(feature_set.columns)]
    grid = lopo.MODEL_SPECS[model_name]["grid"]
    if selector is not None:
        selected = selector(X_fs, y, participants)
        pipeline = lopo.build_pipeline(lopo.selected_feature_set(selected), model_name)
        inner, _ = lopo.fit_estimator(
            pipeline,
            X_fs[selected],
            y,
            participants,
            tune=tune,
            grid=grid,
            inner_splits=inner_splits,
        )
        return lopo.ColumnSubsetClassifier(inner, selected).fit(X_fs, y), X_fs
    estimator, _ = lopo.fit_estimator(
        lopo.build_pipeline(feature_set, model_name),
        X_fs,
        y,
        participants,
        tune=tune,
        grid=grid,
        inner_splits=inner_splits,
    )
    return estimator, X_fs


def run_experiment(
    featured_df,
    *,
    run_params=None,
    rep_summary=None,
    models=None,
    tune=False,
    forward_selection=False,
    tracking_uri=None,
    artifact_root=None,
    report_path=None,
    n_sets=None,
    notes=None,
    augmented_df=None,
    augmentation=None,
):
    """Run LOPO for every feature set x model and track everything in MLflow.

    ``augmented_df``: featurised augmented copies (``augment.engineer_augmented``), used as
    extra training rows in every fold (those of the held-out participant excluded) and in the
    final refit. ``augmentation``: the augmentation config (logged); defaults to
    ``EVAL_CONFIG["augmentation"]`` and is reported as off when ``augmented_df`` is None.
    """
    models = list(models or DEFAULT_MODELS)
    eval_cfg = config.EVAL_CONFIG
    inner_splits = eval_cfg["inner_cv_splits"]

    meta = [c for c in METADATA_COLUMNS if c in featured_df.columns]
    X = featured_df.drop(columns=meta).reset_index(drop=True)
    y = featured_df["label"].to_numpy()
    participants = featured_df[eval_cfg["group_column"]].to_numpy()
    sets = featured_df["set"].to_numpy()
    n_sets = int(n_sets if n_sets is not None else len(np.unique(sets)))
    n_windows = len(featured_df)

    aug_cfg = dict(augmentation or eval_cfg.get("augmentation") or {})
    aug_on = augmented_df is not None and len(augmented_df) > 0
    aug_cfg["enabled"] = bool(aug_on)
    augment = None
    if aug_on:
        aug_meta = [c for c in [*METADATA_COLUMNS, "aug_of", "aug_copy"] if c in augmented_df]
        augment = lopo.AugmentedTrain(
            X=augmented_df.drop(columns=aug_meta).reset_index(drop=True),
            y=augmented_df["label"].to_numpy(),
            participants=augmented_df[eval_cfg["group_column"]].to_numpy(),
        )
    aug_params = {
        "aug_enabled": aug_on,
        "aug_copies": aug_cfg.get("copies") if aug_on else 0,
        "aug_max_deg": aug_cfg.get("max_deg") if aug_on else 0,
        "aug_seed": aug_cfg.get("seed") if aug_on else "none",
        "aug_n_rows": len(augmented_df) if aug_on else 0,
    }
    aug_tag = "on" if aug_on else "off"

    feature_sets = lopo.build_feature_sets(X.columns, config.FEATURE_CONFIG["pca_components"])
    selector = None
    selection_caches = {}
    if forward_selection:
        pool = tuple(feature_sets[-1].columns)
        feature_sets.append(
            lopo.FeatureSet(
                "forward-selected", pool, False, n_features_override=MAX_SELECTED_FEATURES
            )
        )

        def selector(X_train, y_train, groups_train):
            return lopo.forward_selection(
                X_train,
                y_train,
                groups_train,
                list(pool),
                max_features=MAX_SELECTED_FEATURES,
                n_splits=inner_splits,
            )

    uri = tracking.setup_tracking(tracking_uri, artifact_root)
    sha, dirty = tracking.git_info()
    md5 = tracking.data_md5()
    logger.info(f"MLflow tracking URI: {uri}")

    results, child_ids = [], {}
    with mlflow.start_run(
        run_name="current-baseline" if aug_on else "phase0-baseline",
        tags={
            "git.sha": sha,
            "git.dirty": dirty,
            "data.raw.md5": md5,
            "phase": "1" if aug_on else "0",
            "augmentation": aug_tag,
        },
    ) as parent:
        parent_id = parent.info.run_id
        for name, cfg in (run_params or {}).items():
            tracking.log_flat_params(name, cfg)
        mlflow.log_params(
            {
                "cv": eval_cfg["cv"],
                "n_sets": n_sets,
                "n_windows": n_windows,
                "tune": tune,
                "forward_selection": forward_selection,
                "models": ",".join(models),
                **aug_params,
            }
        )

        for fs in feature_sets:
            is_selected = fs.name == "forward-selected"
            cache = selection_caches.setdefault(fs.name, {})
            for model_name in models:
                run_name = f"{fs.name} | {model_name}"
                logger.info(f"Evaluating {run_name}")
                with mlflow.start_run(run_name=run_name, nested=True) as child:
                    result = lopo.evaluate_lopo(
                        X,
                        y,
                        participants,
                        sets,
                        fs,
                        model_name,
                        tune=tune,
                        inner_splits=inner_splits,
                        selector=selector if is_selected else None,
                        selection_cache=cache if is_selected else None,
                        augment=augment,
                    )
                    _log_child(
                        result, fs, model_name, tune, is_selected, run_name, aug_params, aug_tag
                    )
                    child_ids[(fs.name, model_name)] = child.info.run_id
                results.append((fs, result))

        rows = [
            {
                "feature_set": r.feature_set,
                "model": r.model,
                "n_features": r.n_features,
                "run_id": child_ids[(r.feature_set, r.model)],
                **r.summary,
            }
            for _, r in results
        ]
        leaderboard = _rank(pd.DataFrame(rows))
        top = leaderboard.iloc[0]
        best_fs, best_result = next(
            (fs, r)
            for fs, r in results
            if r.feature_set == top["feature_set"] and r.model == top["model"]
        )
        best_run_id = top["run_id"]
        logger.info(
            f"Best: {best_fs.name} / {best_result.model} "
            f"(macro-F1 {top['lopo_window_macro_f1_mean']:.3f}, "
            f"window acc {top['lopo_window_acc_mean']:.3f})"
        )
        if top["lopo_window_acc_mean"] >= 0.99:
            logger.warning("Best LOPO window accuracy >= 0.99: assume a leak and investigate")

        # Refit the best combination on all data and log it in its child run
        # (originals plus all augmented copies when augmentation is on)
        X_all, y_all, p_all = X, y, participants
        if augment is not None:
            X_all = pd.concat([X, augment.X[list(X.columns)]], ignore_index=True)
            y_all = np.concatenate([y, augment.y])
            p_all = np.concatenate([participants, augment.participants])
        final, X_fs = _refit_final(
            best_fs,
            best_result.model,
            X_all,
            y_all,
            p_all,
            tune=tune,
            selector=selector if best_fs.name == "forward-selected" else None,
            inner_splits=inner_splits,
        )
        with mlflow.start_run(run_id=best_run_id, nested=True):
            mlflow.set_tag("best_model", "true")
            signature = infer_signature(X_fs.head(50), final.predict(X_fs.head(50)))
            mlflow.sklearn.log_model(
                final,
                name="model",
                signature=signature,
                input_example=X_fs.head(3),
                # skops would reject our custom PCAFeatures / MLP internals as untrusted
                serialization_format="cloudpickle",
            )

        pooled = confusion_matrix(
            best_result.y_true, best_result.y_pred, labels=best_result.classes
        )

        # Parent metrics: rep counter and headline LOPO numbers
        if rep_summary is not None:
            for exercise, row in rep_summary.iterrows():
                suffix = "" if exercise == "overall" else f"_{exercise}"
                mlflow.log_metrics(
                    {
                        f"rep_mae{suffix}": float(row["mae"]),
                        f"rep_exact{suffix}": float(row["exact"]),
                        f"rep_within_1{suffix}": float(row["within_1"]),
                    }
                )
        mlflow.log_metrics(
            {
                "best_lopo_window_acc_mean": float(top["lopo_window_acc_mean"]),
                "best_lopo_window_macro_f1_mean": float(top["lopo_window_macro_f1_mean"]),
                "best_lopo_set_acc_mean": float(top["lopo_set_acc_mean"]),
            }
        )
        mlflow.set_tag("best_run_id", best_run_id)

        report = reporting.render_report(
            leaderboard=leaderboard,
            best=best_result,
            pooled_cm=pooled,
            rep_summary=rep_summary,
            parent_run_id=parent_id,
            best_run_id=best_run_id,
            data_md5=md5,
            n_sets=n_sets,
            n_windows=n_windows,
            notes=notes or [],
            augmentation=aug_cfg,
        )
        with tempfile.TemporaryDirectory() as tmp:
            report_file = Path(tmp) / "baseline-report.md"
            report_file.write_text(report, encoding="utf-8")
            mlflow.log_artifact(str(report_file))
            leaderboard_file = Path(tmp) / "leaderboard.csv"
            leaderboard.to_csv(leaderboard_file, index=False)
            mlflow.log_artifact(str(leaderboard_file))
        if report_path is not None:
            report_path = Path(report_path)
            report_path.parent.mkdir(parents=True, exist_ok=True)
            report_path.write_text(report, encoding="utf-8")
            logger.info(f"Wrote report to {report_path}")

    return ExperimentOutcome(
        parent_run_id=parent_id,
        best_run_id=best_run_id,
        leaderboard=leaderboard,
        best=best_result,
        pooled_cm=pooled,
        results=[r for _, r in results],
        report_markdown=report,
    )
