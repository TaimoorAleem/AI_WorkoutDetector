"""Evaluate the repetition counter against dataset-derived expected counts."""

import logging

import pandas as pd

from workout_ml import config
from workout_ml.reps.counter import add_magnitudes, count_set

logger = logging.getLogger(__name__)

RESULT_COLUMNS = ["set", "label", "category", "expected", "predicted", "error"]


def evaluate_counter(df: pd.DataFrame, cfg: dict = config.REP_CONFIG) -> pd.DataFrame:
    """One row per set (rest excluded): expected vs predicted rep count."""
    df = add_magnitudes(df[df["label"] != "rest"])
    rows = []
    for s, set_df in df.groupby("set", sort=True):
        label = set_df["label"].iloc[0]
        category = set_df["category"].iloc[0]
        expected = cfg["expected_reps"][category]
        predicted = count_set(set_df, label, cfg).count
        rows.append((s, label, category, expected, predicted, predicted - expected))
    return pd.DataFrame(rows, columns=RESULT_COLUMNS)


def summarize(results: pd.DataFrame) -> pd.DataFrame:
    """MAE, exact-match rate and within-+-1 rate overall and per exercise."""

    def _stats(g: pd.DataFrame) -> dict:
        abs_err = g["error"].abs()
        return {
            "n_sets": len(g),
            "mae": abs_err.mean(),
            "exact": (abs_err == 0).mean(),
            "within_1": (abs_err <= 1).mean(),
        }

    rows = {"overall": _stats(results)}
    for label, g in results.groupby("label"):
        rows[label] = _stats(g)
    return pd.DataFrame.from_dict(rows, orient="index")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(message)s")
    df = pd.read_pickle(config.INTERIM_DATA_PATH / "01_data_processed.pkl")
    summary = summarize(evaluate_counter(df))
    logger.info(
        "Rep counter summary (expected reps assumed from load category):\n%s",
        summary.round(3).to_string(),
    )


if __name__ == "__main__":
    main()
