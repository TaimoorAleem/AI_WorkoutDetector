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