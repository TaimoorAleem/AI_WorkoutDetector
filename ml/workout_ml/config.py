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


# Model configuration
# Note: LearningAlgorithms performs its own grid search internally
# These are the default parameters if gridsearch=False
MODEL_DEFAULTS = {
    "use_gridsearch": True,  # Enable grid search in LearningAlgorithms
}

# Outlier detection configuration
OUTLIER_CONFIG = {
    "method": "chauvenet",  # Options: 'iqr', 'chauvenet', 'lof'
    "per_label": False,  # Apply detection per label
    # Method-specific parameters
    "C": 2,  # For Chauvenet method (certainty parameter, typically 1-10)
    "n": 20,  # For LOF method (number of neighbors)
}

# Feature engineering configuration
FEATURE_CONFIG = {
    "predictor_columns": ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"],
    "window_size": 10,
    "sampling_frequency": 1000 / 200,  # 5 Hz
    "lowpass_cutoff": 1.3,
    "pca_components": 3,
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

# Training configuration
TRAIN_CONFIG = {"test_size": 0.2, "validation_size": 0.15, "random_state": 42, "stratify": True}

# Plotting configuration
PLOT_CONFIG = {"style": "fivethirtyeight", "figure_size": (20, 5), "dpi": 100, "line_width": 2}
