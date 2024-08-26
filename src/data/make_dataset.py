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

f = files[0]
participant, label, category = extract_features(f)