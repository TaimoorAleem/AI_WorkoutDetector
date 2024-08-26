import pandas as pd
from glob import glob
import os

files = glob("../../data/raw/MetaMotion/*.csv")
len(files)

def extract_features(file_path):
    normalized_path = os.path.normpath(file_path)  
    filename = os.path.basename(normalized_path)   
    participant = filename.split('-')[0]           
    label = filename.split('-')[1]                 
    category = filename.split('-')[2].rstrip("123")
    return participant, label, category

acc_df = pd.DataFrame()
gyr_df = pd.DataFrame()

acc_set = 1
gyr_set = 1

for f in files:
    participant, label, category = extract_features(f)
    
    df = pd.read_csv(f)
    df["participant"] = participant
    df["label"] = label
    df["category"] = category
    
    if "Accelerometer" in f:
        acc_df = pd.concat([acc_df, df], ignore_index=True)
    
    elif "Gyroscope" in f:
        gyr_df = pd.concat([gyr_df, df], ignore_index=True)