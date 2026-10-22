"""
Feature engineering pipeline combining temporal and frequency features.

Every step runs per set: nothing here mixes samples from different sets, participants or
labels, and nothing is fitted on other sets (PCA is fitted in-fold during modelling).
"""

import logging

import numpy as np
import pandas as pd

from workout_ml.features.DataTransformation import LowPassFilter
from workout_ml.features.FrequencyAbstraction import FourierTransformation
from workout_ml.features.TemporalAbstraction import NumericalAbstraction

logger = logging.getLogger(__name__)

METADATA_COLUMNS = ["participant", "label", "category", "set"]


class FeatureEngineer:
    """End-to-end, per-set feature engineering pipeline"""

    def __init__(self, config):
        """
        Args:
            config: Configuration dictionary with feature parameters
        """
        self.config = config
        self.low_pass = LowPassFilter()
        self.temporal = NumericalAbstraction()
        self.frequency = FourierTransformation()

    @property
    def windows(self):
        return list(self.config.get("rolling_windows", [5, 10]))

    def engineer_features(self, df):
        """
        Main feature engineering pipeline

        Args:
            df: DataFrame with sensor data (a time index per set) and a "set" column

        Returns:
            DataFrame with engineered features and metadata columns. Each set loses its
            first ``max(window)`` rows, which lack a full in-set window.
        """
        logger.info("Starting per-set feature engineering")
        cols = self.config["predictor_columns"]
        drop_n = max(self.windows + [self.config["window_size"]])

        parts = []
        dropped = 0
        for _, set_df in df.groupby("set", sort=True):
            featured = self._engineer_set(set_df.sort_index(kind="stable"), cols)
            dropped += min(drop_n, len(featured))
            parts.append(featured.iloc[drop_n:])
        out = pd.concat(parts)

        logger.info(
            f"Dropped {dropped} rows ({100 * dropped / max(len(df), 1):.2f}%) without a full "
            f"in-set window (first {drop_n} per set)"
        )
        if out.isna().any().any():
            raise ValueError("Unexpected NaN in engineered features after window trimming")
        logger.info(f"Feature engineering complete. Shape: {out.shape}")
        return out

    def _engineer_set(self, set_df, cols):
        """All feature steps for one set (rows in time order)."""
        data = set_df[cols + [c for c in METADATA_COLUMNS if c in set_df.columns]].copy()
        for col in cols:
            data[col] = data[col].interpolate(limit_direction="both")

        # Low-pass (zero-phase filtfilt is fine: applied to a complete set, D-013)
        for col in cols:
            data = self.low_pass.low_pass_filter(
                data,
                col,
                sampling_frequency=self.config["sampling_frequency"],
                cutoff_frequency=self.config["lowpass_cutoff"],
                order=5,
            )
            data[col] = data[f"{col}_lowpass"]
            data = data.drop(columns=[f"{col}_lowpass"])

        # Derived magnitudes (row-wise, no window)
        derived = pd.DataFrame(
            {
                "acc_r": np.sqrt(data["acc_x"] ** 2 + data["acc_y"] ** 2 + data["acc_z"] ** 2),
                "gyr_r": np.sqrt(data["gyr_x"] ** 2 + data["gyr_y"] ** 2 + data["gyr_z"] ** 2),
            },
            index=data.index,
        )

        # Temporal and frequency features: all new columns concatenated at once
        sensors = data[cols]
        temporal = sensors
        for window_size in self.windows:
            for agg in ["mean", "std", "min", "max"]:
                temporal = self.temporal.abstract_numerical(temporal, cols, window_size, agg)
        temporal = temporal.drop(columns=cols)
        frequency = self.frequency.abstract_frequency(
            sensors,
            cols,
            window_size=self.config["window_size"],
            sampling_rate=self.config["sampling_frequency"],
        ).drop(columns=cols)

        meta = data[[c for c in METADATA_COLUMNS if c in data.columns]]
        return pd.concat([sensors, derived, temporal, frequency, meta], axis=1)
