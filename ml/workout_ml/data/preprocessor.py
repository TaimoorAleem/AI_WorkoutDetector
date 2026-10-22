"""
Data preprocessing module for cleaning and preparing sensor data
"""
# TODO: return visualization at each step

import logging
import re
from pathlib import Path

import numpy as np
import pandas as pd

logger = logging.getLogger(__name__)


SENSOR_FILE_RE = re.compile(r"^(?P<stem>.+?)_(?P<sensor>Accelerometer|Gyroscope)_")


class DataLoader:
    """Load and merge accelerometer and gyroscope data from CSV files"""

    def __init__(self, data_path):
        """
        Args:
            data_path: Path to directory containing CSV files
        """
        self.data_path = Path(data_path)
        #: Stems that lacked one of the two sensors (filled by load_data)
        self.unpaired_stems = []
        #: Duplicate re-export files that were ignored (filled by load_data)
        self.duplicate_files = []

    def _pair_files(self):
        """Group files by stem (the part before _Accelerometer/_Gyroscope).

        Returns:
            dict: stem -> {"Accelerometer": path, "Gyroscope": path}, sorted by stem
        """
        pairs = {}
        for f in sorted(self.data_path.glob("*.csv"), key=lambda p: p.name):
            m = SENSOR_FILE_RE.match(f.name)
            if m is None:
                logger.warning(f"Ignoring file with unrecognised name: {f.name}")
                continue
            slot = pairs.setdefault(m.group("stem"), {})
            if m.group("sensor") in slot:
                # The raw export contains byte-identical re-exports (firmware suffix
                # "1.4.41", timestamps shifted by +24 h). They are the same recording, so
                # keep the first file (sorted order) and ignore the copy.
                logger.warning(
                    f"Ignoring duplicate {m.group('sensor')} file for set {m.group('stem')}: "
                    f"{f.name} (keeping {slot[m.group('sensor')].name})"
                )
                self.duplicate_files.append(f.name)
                continue
            slot[m.group("sensor")] = f
        return dict(sorted(pairs.items()))

    def load_data(self):
        """
        Load and process all CSV files.

        Set IDs are derived from the sorted, unique file stem (1-based), so they do
        not depend on glob order. Stems missing one of the two sensors are logged as
        WARNING and dropped (see ``self.unpaired_stems``).

        Returns:
            tuple: (accelerometer_df, gyroscope_df)
        """
        self.duplicate_files = []
        pairs = self._pair_files()
        self.unpaired_stems = [s for s, d in pairs.items() if len(d) < 2]
        for stem in self.unpaired_stems:
            have = ", ".join(pairs[stem])
            logger.warning(f"Dropping unpaired set {stem}: only has {have}")
        paired = [s for s, d in pairs.items() if len(d) == 2]
        logger.info(
            f"Found {len(paired)} paired sets ({len(self.unpaired_stems)} unpaired dropped)"
        )

        acc_frames, gyr_frames = [], []
        for set_id, stem in enumerate(paired, start=1):
            acc = self._load_single_file(pairs[stem]["Accelerometer"])
            gyr = self._load_single_file(pairs[stem]["Gyroscope"])
            acc["set"] = set_id
            gyr["set"] = set_id
            acc_frames.append(acc)
            gyr_frames.append(gyr)

        acc_df = self._process_timestamps(pd.concat(acc_frames, ignore_index=True))
        gyr_df = self._process_timestamps(pd.concat(gyr_frames, ignore_index=True))

        logger.info(
            f"Loaded {len(acc_df)} accelerometer records and {len(gyr_df)} gyroscope records"
        )

        return acc_df, gyr_df

    def _load_single_file(self, filepath):
        """Load a single CSV file and extract metadata"""
        filename = Path(filepath).name

        # Extract metadata from filename
        parts = filename.split("-")
        participant = parts[0]
        label = parts[1]
        category = re.sub(r"\d+$", "", parts[2].split("_")[0])

        df = pd.read_csv(filepath)
        df["participant"] = participant
        df["label"] = label
        df["category"] = category

        return df

    def _process_timestamps(self, df):
        """Convert epoch to datetime index"""
        if "epoch (ms)" in df.columns:
            df.index = pd.to_datetime(df["epoch (ms)"], unit="ms")
            df = df.drop(columns=["epoch (ms)"])

        # Remove unnecessary columns
        df = df.drop(columns=[col for col in ["time (01:00)", "elapsed (s)"] if col in df.columns])

        return df

    def merge_sensors(self, acc_df, gyr_df):
        """Merge accelerometer and gyroscope data, set by set"""
        names = [
            "acc_x",
            "acc_y",
            "acc_z",
            "gyr_x",
            "gyr_y",
            "gyr_z",
            "participant",
            "label",
            "category",
            "set",
        ]
        acc_by_set = {k: g for k, g in acc_df.groupby("set")}
        merged = []
        for set_id, gyr in gyr_df.groupby("set"):
            m = pd.concat([acc_by_set[set_id].iloc[:, :3], gyr], axis=1, sort=True)
            m.columns = names
            merged.append(m)
        return pd.concat(merged).sort_index(kind="stable")

    def resample_data(self, df, sampling_rule="200ms"):
        """
        Resample data to specified frequency

        Args:
            df: DataFrame with datetime index
            sampling_rule: Resampling frequency (e.g., '200ms')

        Returns:
            Resampled DataFrame
        """
        # Define aggregation rules for different column types
        sampling_dict = {
            "acc_x": "mean",
            "acc_y": "mean",
            "acc_z": "mean",
            "gyr_x": "mean",
            "gyr_y": "mean",
            "gyr_z": "mean",
            "participant": "last",
            "label": "last",
            "category": "last",
            "set": "last",
        }

        # Filter to only include columns that exist in the dataframe
        sampling_dict = {k: v for k, v in sampling_dict.items() if k in df.columns}

        # Split by day and resample each group to avoid edge effects
        days = [g for n, g in df.groupby(pd.Grouper(freq="D"))]
        data_resampled = pd.concat(
            [day.resample(sampling_rule).apply(sampling_dict).dropna() for day in days]
        )

        # Convert set column to integer
        if "set" in data_resampled.columns:
            data_resampled["set"] = data_resampled["set"].astype("int")

        logger.info(f"Resampled data to {sampling_rule}. New size: {len(data_resampled)}")

        return data_resampled


class OutlierRemover:
    """Mark outliers as NaN and interpolate them, set by set.

    No rows are dropped, so the uniform 200 ms sampling the filters assume is kept.
    All statistics are computed within one set (scope="set"), which is computable at
    inference time from the current set alone.

    There is deliberately no per-label scope: it would need the label, which is not
    known at inference, so it is supervised preprocessing and leaks the target.
    """

    @staticmethod
    def mark_outliers_iqr(dataset, col):
        """Return a boolean Series (True = outlier) using the IQR rule (1.5 * IQR)."""
        q1 = dataset[col].quantile(0.25)
        q3 = dataset[col].quantile(0.75)
        iqr = q3 - q1
        return (dataset[col] < q1 - 1.5 * iqr) | (dataset[col] > q3 + 1.5 * iqr)

    @staticmethod
    def mark_outliers_chauvenet(dataset, col, C=2):
        """Return a boolean Series (True = outlier) using Chauvenet's criterion.

        Args:
            dataset: DataFrame (one set)
            col: Column name to check for outliers
            C: Degree of certainty for identification (typically 1-10, default: 2)
        """
        import scipy.special

        x = dataset[col]
        std = x.std()
        if not np.isfinite(std) or std == 0:
            return pd.Series(False, index=dataset.index)
        criterion = 1.0 / (C * len(x))
        deviation = (x - x.mean()).abs() / std
        # P(|Z| >= deviation / sqrt(C)); outlier when below the criterion
        prob = scipy.special.erfc(deviation / np.sqrt(C))
        return pd.Series(prob < criterion, index=dataset.index)

    @staticmethod
    def mark_outliers_lof(dataset, columns, n=20):
        """Return a boolean Series (True = outlier) using Local Outlier Factor."""
        from sklearn.neighbors import LocalOutlierFactor

        if len(dataset) <= n:
            return pd.Series(False, index=dataset.index)
        lof = LocalOutlierFactor(n_neighbors=n)
        return pd.Series(lof.fit_predict(dataset[columns]) == -1, index=dataset.index)

    @staticmethod
    def _clean_one_set(group, columns, method, C, n):
        group = group.copy()
        if method == "lof":
            mask = OutlierRemover.mark_outliers_lof(group, columns, n=n)
            group.loc[mask, columns] = np.nan
            n_marked = int(mask.sum()) * len(columns)
        else:
            n_marked = 0
            for col in columns:
                if method == "iqr":
                    mask = OutlierRemover.mark_outliers_iqr(group, col)
                else:
                    mask = OutlierRemover.mark_outliers_chauvenet(group, col, C)
                n_marked += int(mask.sum())
                group.loc[mask, col] = np.nan
        group[columns] = group[columns].interpolate(method="linear", limit_direction="both")
        return group[columns].to_numpy(), n_marked

    @staticmethod
    def clean_outliers(dataset, columns, method="chauvenet", scope="set", **kwargs):
        """Mark outliers as NaN per set and linearly interpolate them within the set.

        Args:
            dataset: DataFrame with a "set" column, in time order within each set
            columns: sensor columns to clean
            method: 'iqr', 'chauvenet' or 'lof'
            scope: only "set" is supported
            **kwargs: C (Chauvenet, default 2), n (LOF neighbours, default 20)

        Returns:
            DataFrame with the same rows (and order) as the input
        """
        if method not in ("iqr", "chauvenet", "lof"):
            raise ValueError(f"Unknown method: {method}. Use 'iqr', 'chauvenet', or 'lof'")
        if scope != "set":
            raise ValueError(f"Unsupported scope {scope!r}: only 'set' is allowed (no labels)")
        columns = list(columns)
        C = kwargs.get("C", 2)
        n = kwargs.get("n", 20)

        out = dataset.copy()
        col_pos = [out.columns.get_loc(c) for c in columns]
        set_ids = dataset["set"].to_numpy()
        total_marked = 0
        for s in pd.unique(set_ids):
            pos = np.flatnonzero(set_ids == s)
            values, marked = OutlierRemover._clean_one_set(dataset.iloc[pos], columns, method, C, n)
            out.iloc[pos, col_pos] = values
            total_marked += marked

        n_cells = len(dataset) * len(columns)
        logger.info(
            f"Marked and interpolated {total_marked} outlier values "
            f"({100 * total_marked / max(n_cells, 1):.2f}% of cells) using {method} per {scope}. "
            f"Rows kept: {len(out)}"
        )
        return out
