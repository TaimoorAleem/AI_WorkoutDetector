"""
End-to-end pipeline: load, per-set outlier handling, per-set features, LOPO evaluation
tracked in MLflow, rep-counter evaluation and the baseline report.

Usage:
    python -m workout_ml.pipeline [--tune] [--forward-selection] [--models RF DT ...]
"""

import argparse
import logging
import time
import warnings

import matplotlib

matplotlib.use("Agg")  # headless: pipeline code never opens a window

from workout_ml import config  # noqa: E402
from workout_ml.data.preprocessor import DataLoader, OutlierRemover  # noqa: E402
from workout_ml.features.engineer_features import FeatureEngineer  # noqa: E402
from workout_ml.models.experiment import DEFAULT_MODELS, run_experiment  # noqa: E402
from workout_ml.reps.evaluate import evaluate_counter, summarize  # noqa: E402

logger = logging.getLogger(__name__)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--tune",
        action="store_true",
        help="GridSearchCV (GroupKFold by participant) inside each training fold",
    )
    parser.add_argument(
        "--forward-selection",
        action="store_true",
        help="add a 'forward-selected' feature set, selected inside each training fold",
    )
    parser.add_argument("--models", nargs="+", default=DEFAULT_MODELS, choices=DEFAULT_MODELS)
    parser.add_argument("--tracking-uri", default=None, help="overrides MLFLOW_TRACKING_URI")
    return parser.parse_args(argv)


def main(argv=None):
    """Run the complete ML pipeline"""
    args = parse_args(argv)
    logging.basicConfig(
        level=logging.INFO, format="%(asctime)s - %(name)s - %(levelname)s - %(message)s"
    )
    warnings.filterwarnings("ignore", category=UserWarning, module="sklearn")
    warnings.filterwarnings("ignore", category=FutureWarning)
    start = time.perf_counter()
    config.ensure_dirs()

    # Step 1: load, merge, resample, per-set outlier handling
    logger.info("STEP 1: Loading and preprocessing data")
    loader = DataLoader(config.RAW_DATA_PATH)
    acc_df, gyr_df = loader.load_data()
    merged_df = loader.merge_sensors(acc_df, gyr_df)
    resampled_df = loader.resample_data(merged_df, sampling_rule="200ms")

    oc = config.OUTLIER_CONFIG
    sensor_columns = config.FEATURE_CONFIG["predictor_columns"]
    cleaned_df = OutlierRemover.clean_outliers(
        resampled_df, sensor_columns, method=oc["method"], scope=oc["scope"], C=oc["C"], n=oc["n"]
    )
    cleaned_df.to_pickle(config.INTERIM_DATA_PATH / "01_data_processed.pkl")

    # Step 2: per-set feature engineering
    logger.info("STEP 2: Engineering features (per set)")
    featured_df = FeatureEngineer(config.FEATURE_CONFIG).engineer_features(cleaned_df)
    featured_df.to_pickle(config.INTERIM_DATA_PATH / "02_feature_engineered.pkl")

    # Step 3: rep counter (on cleaned, pre-feature data)
    logger.info("STEP 3: Evaluating the repetition counter")
    rep_summary = summarize(evaluate_counter(cleaned_df))
    logger.info("Rep counter summary:\n%s", rep_summary.round(3).to_string())

    # Step 4: LOPO evaluation, tracked in MLflow
    logger.info("STEP 4: LOPO evaluation")
    notes = [
        f"Duplicate re-export files ignored by the loader: {len(loader.duplicate_files)}",
        f"Unpaired sets dropped (one sensor missing): {len(loader.unpaired_stems)}"
        + (f" ({', '.join(loader.unpaired_stems)})" if loader.unpaired_stems else ""),
        f"Flags: tune={args.tune}, forward_selection={args.forward_selection}",
    ]
    outcome = run_experiment(
        featured_df,
        run_params={
            "outlier": config.OUTLIER_CONFIG,
            "features": config.FEATURE_CONFIG,
            "eval": config.EVAL_CONFIG,
        },
        rep_summary=rep_summary,
        models=args.models,
        tune=args.tune,
        forward_selection=args.forward_selection,
        tracking_uri=args.tracking_uri,
        report_path=config.REPORT_PATH,
        n_sets=cleaned_df["set"].nunique(),
        notes=notes,
    )

    top = outcome.leaderboard.iloc[0]
    logger.info("=" * 50)
    logger.info(
        f"Best: {top['feature_set']} / {top['model']} "
        f"window acc {top['lopo_window_acc_mean']:.3f}, "
        f"macro-F1 {top['lopo_window_macro_f1_mean']:.3f}, "
        f"set acc {top['lopo_set_acc_mean']:.3f}"
    )
    logger.info(f"MLflow parent run: {outcome.parent_run_id}")
    logger.info(f"Pipeline completed in {time.perf_counter() - start:.0f} s")
    return outcome


if __name__ == "__main__":
    main()
