"""Shared loading helpers for the watch harness (MetaMotion training data, cleaned watch data)."""

from __future__ import annotations

import pandas as pd

from workout_ml import config
from workout_ml.data.preprocessor import DataLoader, OutlierRemover
from workout_ml.features.engineer_features import FeatureEngineer
from workout_ml.watch.ingest import load_watch_sessions

SENSOR_COLUMNS = config.FEATURE_CONFIG["predictor_columns"]


def clean(df: pd.DataFrame) -> pd.DataFrame:
    """Per-set outlier handling with the pipeline's ``OUTLIER_CONFIG`` (no rows dropped)."""
    if df.empty:
        return df
    oc = config.OUTLIER_CONFIG
    out = OutlierRemover.clean_outliers(
        df, SENSOR_COLUMNS, method=oc["method"], scope=oc["scope"], C=oc["C"], n=oc["n"]
    )
    out.attrs.update(df.attrs)
    return out


def load_metamotion_cleaned(raw_path=None) -> pd.DataFrame:
    """All MetaMotion sets as cleaned CSF-1 (the training data of every model here)."""
    loader = DataLoader(raw_path or config.RAW_DATA_PATH)
    return clean(loader.load_data())


def watch_frame(dataset, profile, *, clean_outliers: bool = True, **kw) -> pd.DataFrame:
    """``load_watch_sessions`` then (optionally) the same per-set outlier cleaning as training."""
    df = load_watch_sessions(dataset, profile, **kw)
    return clean(df) if clean_outliers else df


def engineer(df: pd.DataFrame) -> pd.DataFrame:
    """Per-set feature engineering with the pipeline's ``FEATURE_CONFIG``."""
    return FeatureEngineer(config.FEATURE_CONFIG).engineer_features(df)
