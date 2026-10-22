"""Repetition counting: Butterworth low-pass filter, then local maxima."""

import logging
from dataclasses import dataclass

import numpy as np
import pandas as pd
from scipy.signal import argrelextrema

from workout_ml import config
from workout_ml.features.DataTransformation import LowPassFilter

logger = logging.getLogger(__name__)


@dataclass
class RepResult:
    count: int
    peak_indices: np.ndarray
    column: str
    cutoff: float


def add_magnitudes(df: pd.DataFrame) -> pd.DataFrame:
    """Return a copy of df with Euclidean norms acc_r and gyr_r added."""
    out = df.copy()
    out["acc_r"] = np.sqrt(out["acc_x"] ** 2 + out["acc_y"] ** 2 + out["acc_z"] ** 2)
    out["gyr_r"] = np.sqrt(out["gyr_x"] ** 2 + out["gyr_y"] ** 2 + out["gyr_z"] ** 2)
    return out


def count_peaks(
    signal: np.ndarray, sampling_frequency: float, cutoff: float, order: int
) -> np.ndarray:
    """Low-pass filter a 1-D signal (zero phase) and return indices of its local maxima."""
    tmp = pd.DataFrame({"x": np.asarray(signal, dtype=float)})
    filtered = LowPassFilter().low_pass_filter(
        tmp,
        col="x",
        sampling_frequency=sampling_frequency,
        cutoff_frequency=cutoff,
        order=order,
    )
    return argrelextrema(filtered["x_lowpass"].values, np.greater)[0]


def count_set(set_df: pd.DataFrame, exercise: str, cfg: dict = config.REP_CONFIG) -> RepResult:
    """Count repetitions in a single set of the given exercise."""
    if exercise == "rest":
        raise ValueError("Cannot count repetitions for 'rest'")
    params = cfg["exercises"].get(exercise)
    if params is None:
        logger.warning("Unknown exercise %r; using default rep-counting config", exercise)
        params = cfg["default"]
    column, cutoff = params["column"], params["cutoff"]
    if column not in set_df.columns:
        set_df = add_magnitudes(set_df)
    peaks = count_peaks(set_df[column].values, cfg["sampling_frequency"], cutoff, cfg["order"])
    return RepResult(count=len(peaks), peak_indices=peaks, column=column, cutoff=cutoff)
