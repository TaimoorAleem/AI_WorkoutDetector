"""
Configuration file for AI Workout Detector
"""

from pathlib import Path

# Project paths
# ml/workout_ml/config.py -> parents[2] is the repo root
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DATA_DIR = PROJECT_ROOT / "data"
MODELS_DIR = PROJECT_ROOT / "results" / "models"
PLOTS_DIR = PROJECT_ROOT / "results" / "plots"
METRICS_DIR = PROJECT_ROOT / "results" / "metrics"

# Data configuration
RAW_DATA_PATH = DATA_DIR / "raw" / "MetaMotion"
PROCESSED_DATA_PATH = DATA_DIR / "processed"
INTERIM_DATA_PATH = DATA_DIR / "interim"


def ensure_dirs():
    """Create output directories if they don't exist."""
    for d in (MODELS_DIR, PLOTS_DIR, METRICS_DIR, INTERIM_DATA_PATH):
        d.mkdir(parents=True, exist_ok=True)


# Outlier handling: mark as NaN per set, then interpolate within the set (no rows dropped).
# Labels are never used (a per-label scope would be supervised and impossible at inference).
OUTLIER_CONFIG = {
    "method": "chauvenet",  # Options: 'iqr', 'chauvenet', 'lof'
    "scope": "set",  # statistics are computed within one set
    "C": 2,  # For Chauvenet method (certainty parameter, typically 1-10)
    "n": 20,  # For LOF method (number of neighbors)
}

# Feature engineering configuration
FEATURE_CONFIG = {
    "predictor_columns": ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"],
    "window_size": 10,  # FFT window
    "rolling_windows": [5, 10],  # rolling mean/std/min/max windows
    "sampling_frequency": 1000 / 200,  # 5 Hz
    "lowpass_cutoff": 1.3,
    "pca_components": 3,  # fitted in-fold during modelling, never on the full dataset
}

# Repetition counting configuration (low-pass filter + local maxima)
# NOTE: "expected_reps" is an ASSUMPTION derived from the dataset's load category
# (heavy sets = 5 reps, medium sets = 10 reps), not annotated ground truth.
REP_CONFIG = {
    "sampling_frequency": 5.0,
    "order": 10,
    "default": {"column": "acc_r", "cutoff": 0.4},
    "exercises": {
        "bench": {"column": "acc_r", "cutoff": 0.4},
        "squat": {"column": "acc_r", "cutoff": 0.35},
        "row": {"column": "gyr_x", "cutoff": 0.65},
        "ohp": {"column": "acc_r", "cutoff": 0.35},
        "dead": {"column": "acc_r", "cutoff": 0.4},
    },
    "expected_reps": {"heavy": 5, "medium": 10},
}

# Evaluation configuration: leave-one-participant-out (LOPO), inner CV grouped by participant
EVAL_CONFIG = {
    "cv": "LOPO",
    "group_column": "participant",
    "random_state": 42,
    "inner_cv_splits": 4,  # GroupKFold, only used by --tune / --forward-selection
    "selection_metric": "lopo_window_macro_f1_mean",  # tiebreak: lopo_set_acc_mean
}

# MLflow tracking
MLFLOW_EXPERIMENT = "proprio-classifier"
MLFLOW_DEFAULT_DB = PROJECT_ROOT / "mlflow.db"
MLFLOW_ARTIFACT_ROOT = PROJECT_ROOT / "mlartifacts"
DVC_DATA_FILE = DATA_DIR / "raw" / "MetaMotion.dvc"
REPORT_PATH = PROJECT_ROOT / "docs" / "reports" / "phase0-baseline.md"

# Plotting configuration
PLOT_CONFIG = {"style": "fivethirtyeight", "figure_size": (20, 5), "dpi": 100, "line_width": 2}
