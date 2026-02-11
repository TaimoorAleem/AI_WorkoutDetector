"""
Feature engineering pipeline combining temporal and frequency features
"""
import pandas as pd
import numpy as np
import logging
from .TemporalAbstraction import NumericalAbstraction
from .FrequencyAbstraction import FourierTransformation
from .DataTransformation import LowPassFilter, PrincipalComponentAnalysis

logger = logging.getLogger(__name__)


class FeatureEngineer:
    """End-to-end feature engineering pipeline"""
    
    def __init__(self, config):
        """
        Args:
            config: Configuration dictionary with feature parameters
        """
        self.config = config
        self.low_pass = LowPassFilter()
        self.temporal = NumericalAbstraction()
        self.frequency = FourierTransformation()
        self.pca = PrincipalComponentAnalysis()
    
    def engineer_features(self, df):
        """
        Main feature engineering pipeline
        
        Args:
            df: DataFrame with raw sensor data
            
        Returns:
            DataFrame with engineered features
        """
        logger.info("Starting feature engineering pipeline")
        
        df = df.copy()
        cols = self.config["predictor_columns"]
        
        # Step 1: Interpolate missing values in predictor columns
        logger.info("Interpolating missing values in predictor columns")
        for col in cols:
            df[col] = df[col].interpolate()
        
        # Step 2: Apply low-pass filter
        logger.info("Applying low-pass filter")
        df = self._apply_lowpass(df, cols)
        
        # Step 3: Temporal features
        logger.info("Extracting temporal features")
        df = self._extract_temporal_features(df, cols)
        
        # Step 4: Frequency features
        logger.info("Extracting frequency features")
        df = self._extract_frequency_features(df, cols)
        
        # Step 4.5: Drop rows with NaN in predictor columns only (needed for PCA)
        # Don't drop based on derived features which may have legitimate NaN values
        rows_before = len(df)
        df = df.dropna(subset=cols)
        rows_after = len(df)
        if rows_before != rows_after:
            logger.info(f"Dropped {rows_before - rows_after} rows with NaN in predictor columns ({((rows_before - rows_after)/rows_before)*100:.2f}%)")
        
        # Step 5: PCA
        logger.info("Applying PCA")
        df = self.pca.apply_pca(df, cols, self.config["pca_components"])
        
        # Step 6: Calculate derived features
        logger.info("Calculating derived features")
        df = self._calculate_derived_features(df)
        
        logger.info(f"Feature engineering complete. Shape: {df.shape}")
        return df
    
    def _apply_lowpass(self, df, cols):
        """Apply low-pass filter to all sensor columns"""
        for col in cols:
            df = self.low_pass.low_pass_filter(
                df, col,
                sampling_frequency=self.config["sampling_frequency"],
                cutoff_frequency=self.config["lowpass_cutoff"],
                order=5
            )
            df[col] = df[f"{col}_lowpass"]
            df = df.drop(columns=[f"{col}_lowpass"])
        
        return df
    
    def _extract_temporal_features(self, df, cols):
        """Extract temporal statistics"""
        for col in cols:
            for window_size in [5, 10]:
                for agg in ["mean", "std", "min", "max"]:
                    df = self.temporal.abstract_numerical(df, [col], window_size, agg)
        
        return df
    
    def _extract_frequency_features(self, df, cols):
        """Extract frequency domain features"""
        df = self.frequency.abstract_frequency(
            df, cols,
            window_size=self.config["window_size"],
            sampling_rate=self.config["sampling_frequency"]
        )
        return df
    
    def _calculate_derived_features(self, df):
        """Calculate magnitude and duration features"""
        # Resultant acceleration and gyroscope
        df["acc_r"] = np.sqrt(df["acc_x"]**2 + df["acc_y"]**2 + df["acc_z"]**2)
        df["gyr_r"] = np.sqrt(df["gyr_x"]**2 + df["gyr_y"]**2 + df["gyr_z"]**2)
        
        # Duration per set
        for s in df["set"].unique():
            start = df[df["set"] == s].index[0]
            stop = df[df["set"] == s].index[-1]
            duration = (stop - start).total_seconds()
            df.loc[df["set"] == s, "duration"] = duration
        
        return df