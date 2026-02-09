"""
Data preprocessing module for cleaning and preparing sensor data
"""
import pandas as pd
import numpy as np
from pathlib import Path
from glob import glob
import re
import logging

logger = logging.getLogger(__name__)


class DataLoader:
    """Load and merge accelerometer and gyroscope data from CSV files"""
    
    def __init__(self, data_path):
        """
        Args:
            data_path: Path to directory containing CSV files
        """
        self.data_path = Path(data_path)
    
    def load_data(self):
        """
        Load and process all CSV files
        
        Returns:
            tuple: (accelerometer_df, gyroscope_df)
        """
        files = glob(str(self.data_path / "*.csv"))
        logger.info(f"Found {len(files)} files to process")
        
        acc_df = pd.DataFrame()
        gyr_df = pd.DataFrame()
        acc_set = 1
        gyr_set = 1
        
        for f in files:
            df = self._load_single_file(f)
            
            if "Accelerometer" in f:
                df["set"] = acc_set
                acc_set += 1
                acc_df = pd.concat([acc_df, df], ignore_index=True)
            elif "Gyroscope" in f:
                df["set"] = gyr_set
                gyr_set += 1
                gyr_df = pd.concat([gyr_df, df], ignore_index=True)
        
        # Process timestamps
        acc_df = self._process_timestamps(acc_df)
        gyr_df = self._process_timestamps(gyr_df)
        
        logger.info(f"Loaded {len(acc_df)} accelerometer records and {len(gyr_df)} gyroscope records")
        
        return acc_df, gyr_df
    
    def _load_single_file(self, filepath):
        """Load a single CSV file and extract metadata"""
        filename = Path(filepath).name
        
        # Extract metadata from filename
        parts = filename.split('-')
        participant = parts[0]
        label = parts[1]
        category = re.sub(r'\d+$', '', parts[2].split('_')[0])
        
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
        """Merge accelerometer and gyroscope data"""
        merged = pd.concat([acc_df.iloc[:, :3], gyr_df], axis=1)
        merged.columns = [
            "acc_x", "acc_y", "acc_z", 
            "gyr_x", "gyr_y", "gyr_z",
            "participant", "label", "category", "set"
        ]
        return merged
    
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
            'acc_x': "mean",
            'acc_y': "mean",
            'acc_z': "mean",
            'gyr_x': "mean",
            'gyr_y': "mean",
            'gyr_z': "mean",
            'participant': "last",
            'label': "last",
            'category': "last",
            'set': "last"
        }
        
        # Filter to only include columns that exist in the dataframe
        sampling_dict = {k: v for k, v in sampling_dict.items() if k in df.columns}
        
        # Split by day and resample each group to avoid edge effects
        days = [g for n, g in df.groupby(pd.Grouper(freq='D'))]
        data_resampled = pd.concat([
            day.resample(sampling_rule).apply(sampling_dict).dropna() 
            for day in days
        ])
        
        # Convert set column to integer
        if 'set' in data_resampled.columns:
            data_resampled["set"] = data_resampled["set"].astype("int")
        
        logger.info(f"Resampled data to {sampling_rule}. New size: {len(data_resampled)}")
        
        return data_resampled


class OutlierRemover:
    """Remove outliers from sensor data"""
    
    @staticmethod
    def mark_outliers_iqr(dataset, col):
        """
        Mark outliers using Interquartile Range (IQR) method
        
        Args:
            dataset: DataFrame
            col: Column name to check for outliers
            
        Returns:
            DataFrame with outlier indicators
        """
        dataset = dataset.copy()
        
        Q1 = dataset[col].quantile(0.25)
        Q3 = dataset[col].quantile(0.75)
        IQR = Q3 - Q1
        
        lower_bound = Q1 - 1.5 * IQR
        upper_bound = Q3 + 1.5 * IQR
        
        dataset[f"{col}_outlier"] = (dataset[col] < lower_bound) | (dataset[col] > upper_bound)
        
        return dataset
    
    @staticmethod
    def mark_outliers_chauvenet(dataset, col, C=2):
        """Mark outliers using Chauvenet's criterion
        
        Finds outliers in the specified column and adds a binary column with
        the same name extended with '_outlier'.
        
        Args:
            dataset: DataFrame
            col: Column name to check for outliers
            C: Degree of certainty for identification (typically 1-10, default: 2)
            
        Returns:
            DataFrame with outlier indicators
        """
        import math
        import scipy.special
        
        dataset = dataset.copy()
        
        # Compute the mean and standard deviation
        mean = dataset[col].mean()
        std = dataset[col].std()
        N = len(dataset.index)
        criterion = 1.0 / (C * N)
        
        # Consider the deviation for the data points
        deviation = abs(dataset[col] - mean) / std
        
        # Express the upper and lower bounds
        low = -deviation / math.sqrt(C)
        high = deviation / math.sqrt(C)
        prob = []
        mask = []
        
        # Pass all rows in the dataset
        for i in range(0, len(dataset.index)):
            # Determine the probability of observing the point
            prob.append(
                1.0 - 0.5 * (scipy.special.erf(high.iloc[i]) - scipy.special.erf(low.iloc[i]))
            )
            # Mark as outlier when probability is below criterion
            mask.append(prob[i] < criterion)
        
        dataset[col + "_outlier"] = mask
        return dataset
    
    @staticmethod
    def mark_outliers_lof(dataset, columns, n=20):
        """Mark outliers using Local Outlier Factor (LOF)
        
        Args:
            dataset: DataFrame
            columns: List of column names to use for detection
            n: Number of neighbors (default: 20)
            
        Returns:
            tuple: (dataset with outlier column, outlier predictions, LOF scores)
        """
        from sklearn.neighbors import LocalOutlierFactor
        
        dataset = dataset.copy()
        
        lof = LocalOutlierFactor(n_neighbors=n)
        data = dataset[columns]
        outliers = lof.fit_predict(data)
        X_scores = lof.negative_outlier_factor_
        
        # -1 for outliers, 1 for inliers
        dataset["outlier_lof"] = outliers == -1
        
        return dataset, outliers, X_scores
    
    @staticmethod
    def remove_outliers(dataset, columns, method="iqr", **kwargs):
        """Remove outliers from multiple columns
        
        Args:
            dataset: DataFrame
            columns: List of column names
            method: Detection method ('iqr', 'chauvenet', or 'lof')
            **kwargs: Additional parameters:
                - C: for Chauvenet method (default 2)
                - n: for LOF method (default 20)
                - per_label: Whether to apply per label (default False)
            
        Returns:
            DataFrame with outliers removed
        """
        dataset = dataset.copy()
        initial_size = len(dataset)
        per_label = kwargs.get("per_label", False)
        
        if method == "iqr":
            if per_label and "label" in dataset.columns:
                # Apply IQR per label
                outliers_removed_df = dataset.copy()
                for col in columns:
                    for label in dataset["label"].unique():
                        # Mark outliers for this label
                        label_data = OutlierRemover.mark_outliers_iqr(
                            dataset[dataset["label"] == label], col
                        )
                        # Remove outliers
                        outliers_removed_df = outliers_removed_df[
                            ~((outliers_removed_df["label"] == label) & 
                              outliers_removed_df.index.isin(label_data[label_data[col + "_outlier"]].index))
                        ]
                dataset = outliers_removed_df
            else:
                # Apply IQR globally
                for col in columns:
                    dataset = OutlierRemover.mark_outliers_iqr(dataset, col)
                    dataset = dataset[~dataset[col + "_outlier"]]
                    dataset = dataset.drop(columns=[col + "_outlier"])
        
        elif method == "chauvenet":
            C = kwargs.get("C", 2)
            if per_label and "label" in dataset.columns:
                # Apply Chauvenet per label (as in original file)
                outliers_removed_df = dataset.copy()
                for col in columns:
                    for label in dataset["label"].unique():
                        # Mark outliers for this label
                        label_data = OutlierRemover.mark_outliers_chauvenet(
                            dataset[dataset["label"] == label], col, C
                        )
                        
                        # Count outliers
                        n_outliers = label_data[col + "_outlier"].sum()
                        
                        # Replace with NaN instead of removing
                        label_data.loc[label_data[col + "_outlier"], col] = np.nan
                        
                        # Update in main dataframe
                        outliers_removed_df.loc[
                            (outliers_removed_df["label"] == label), col
                        ] = label_data[col]
                        
                        logger.info(f"Marked {n_outliers} outliers as NaN from {col} for label {label}")
                
                dataset = outliers_removed_df
            else:
                # Apply Chauvenet globally
                for col in columns:
                    dataset = OutlierRemover.mark_outliers_chauvenet(dataset, col, C)
                    dataset = dataset[~dataset[col + "_outlier"]]
                    dataset = dataset.drop(columns=[col + "_outlier"])
        
        elif method == "lof":
            n_neighbors = kwargs.get("n", 20)
            # LOF is multivariate, so apply to all columns at once
            dataset, outliers, X_scores = OutlierRemover.mark_outliers_lof(
                dataset, columns, n=n_neighbors
            )
            dataset = dataset[~dataset["outlier_lof"]]
            dataset = dataset.drop(columns=["outlier_lof"])
        
        else:
            raise ValueError(f"Unknown method: {method}. Use 'iqr', 'chauvenet', or 'lof'")
        
        removed_count = initial_size - len(dataset)
        removal_pct = (removed_count / initial_size) * 100 if initial_size > 0 else 0
        
        logger.info(f"Removed {removed_count} outliers ({removal_pct:.2f}%) using {method} method. Dataset size: {len(dataset)}")
        
        return dataset