import pandas as pd
from glob import glob
import os
import re

def read_data_from_files(files):
    """
    Reads and processes data from a list of CSV files, extracting features 
    and concatenating them into separate DataFrames for accelerometer and gyroscope data.
    """
    
    # Initialize DataFrames for accelerometer and gyroscope data
    acc_df = pd.DataFrame()
    gyr_df = pd.DataFrame()

    # Initialize counter for accelerometer and gyroscope workout sets
    acc_set = 1
    gyr_set = 1

    for f in files:
        # Normalize the file path and extract filename
        normalized_path = os.path.normpath(f)
        filename = os.path.basename(normalized_path)
        
        # Extract features from filename
        participant = filename.split('-')[0]  # e.g: "A"
        label = filename.split('-')[1]  # e.g: "bench"
        category_with_digits = filename.split('-')[2].split('_')[0]  # e.g: "heavy2"
        category = re.sub(r'\d+$', '', category_with_digits)  # Remove trailing digits using RegEx, e.g: "heavy"
        
        # Read the CSV file into a DataFrame
        df = pd.read_csv(f)
        
        # Add feature columns
        df["participant"] = participant
        df["label"] = label
        df["category"] = category
        
        # Check if the file is for accelerometer or gyroscope and concatenate to the respective DataFrame
        if "Accelerometer" in f:
            df["set"] = acc_set  # Add a set identifier for accelerometer data
            acc_set += 1  # Increment the set counter
            acc_df = pd.concat([acc_df, df], ignore_index=True)  # Concatenate to the accelerometer DataFrame
        
        elif "Gyroscope" in f:
            df["set"] = gyr_set  # Add a set identifier for gyroscope data
            gyr_set += 1  # Increment the set counter
            gyr_df = pd.concat([gyr_df, df], ignore_index=True)  # Concatenate to the gyroscope DataFrame
    
    # Ensure 'epoch (ms)' column exists before setting it as index
    if "epoch (ms)" in acc_df.columns:
        acc_df.index = pd.to_datetime(acc_df["epoch (ms)"], unit="ms")  # Convert epoch to datetime index
        del acc_df["epoch (ms)"]  # Remove the original epoch column
    
    if "epoch (ms)" in gyr_df.columns:
        gyr_df.index = pd.to_datetime(gyr_df["epoch (ms)"], unit="ms")  # Convert epoch to datetime index
        del gyr_df["epoch (ms)"]  # Remove the original epoch column

    # Drop unnecessary columns, if they exist
    for col in ["time (01:00)", "elapsed (s)"]:
        if col in acc_df.columns:
            del acc_df[col]
        if col in gyr_df.columns:
            del gyr_df[col]
    
    return acc_df, gyr_df

# List all CSV files in the specified directory
files = glob("../../data/raw/MetaMotion/*.csv")

# Read and process all files
acc_df, gyr_df = read_data_from_files(files)

# Merge accelerometer and gyroscope DataFrames
final_dataset = pd.concat([acc_df.iloc[:,:3], gyr_df], axis=1)

# Rename columns for clarity
final_dataset.columns = [
    "acc_x",
    "acc_y",
    "acc_z",
    "gyr_x",
    "gyr_y",
    "gyr_z",
    "label",
    "category",
    "participant",
    "set"
]

# Define resampling rules for different columns
sampling =  {
    'acc_x': "mean",
    'acc_y': "mean",
    'acc_z': "mean",
    'gyr_x': "mean",
    'gyr_y': "mean",
    'gyr_z': "mean",
    'label': "last",
    'category': "last",
    'participant': "last",
    'set': "last"
}

# Ensure datetime index is set before resampling
if final_dataset.index.name != 'datetime':
    final_dataset = final_dataset.resample('200ms').apply(sampling)  # Resample data to 200ms intervals

# Split the dataset by day and resample each group
days = [g for n, g in final_dataset.groupby(pd.Grouper(freq='D'))]

# Concatenate resampled data
data_resampled = pd.concat([df.resample('200ms').apply(sampling).dropna() for df in days])

# Convert 'set' column to integer type
data_resampled["set"] = data_resampled["set"].astype("int")

# Export the processed dataset to a pickle file
data_resampled.to_pickle("../../data/interim/01_data_processed.pkl")

# Output info about the resampled data
data_resampled.info()