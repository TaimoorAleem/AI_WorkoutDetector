![exercises](https://github.com/user-attachments/assets/dc7a594b-be6c-40ac-ad19-efa4189bc3a1)

# AI Workout Detector 🏋️‍♂️🕵️‍♂️

## Overview
The AI Workout Detector is a comprehensive application designed to detect and classify various barbell exercises using Machine Learning and monitor the repetitions of the exercise being performed. The application leverages a real-world dataset ([ Download Link ](https://secure-res.craft.do/v2/VDcx9pyWxusPMveFX3m6KG6HXbjF2gSLkdV3zTrPX8WWrkjoh6aJinsjsSg9tEdgeMZcjDWdtZd28EhN2o2xY1Ui9TfDF5BLtGfUvYhVMqbVgdBdG7UWggpP3rR3DnS5CP9iupmM9rQQPpc9EREkeFXTSsmWXLbb98D3kdakxcembuRAC65ewTeSez8H1yd1GqFYoL76ZhHHGYrL1a4QgNa3G1pHhMLMViLV1PjeuDVxboZBTgp4S8SUsyZZDTixk5jNFwM8BZxff3Mwd8JtxQYkKkGsj8mVm75oGZaFbSGXAkLTsP/MetaMotion.zip)) collected during actual gym workouts, involving five participants performing various barbell exercises with varying weights. The data was retrieved using the [MetaMotion Sensor](https://mbientlab.com/metamotions/) device and includes Accelerometer and Gyroscope recordings from multiple sets of multiple exercises at different frequencies for each.

Each file in the dataset represents sensor data for a specific set and is named to reflect the details of the exercise performed.

For example: `A-bench-heavy_MetaWear_2019-01-14T14.22.49.165_C42732BE255C_Accelerometer_12.500Hz_1.4.4.csv`

## Progress So Far

### ✅ Step 1 - Data Ingestion and Processing:

#### Data Extraction:
Implemented a function to extract features from filenames
- participant: Extracted as the first part of the filename (e.g., "A").
- label: Extracted as the second part of the filename (e.g., "bench").
- category: Extracted as the first part of the third segment, removing any trailing digits (e.g., "heavy").

#### Data Aggregation:
Concatenated individual data files into comprehensive DataFrames for Accelerometer and Gyroscope data.
Added metadata columns (participant, label, category, set) to each DataFrame.

#### Data Cleaning:
Removed unnecessary columns (epoch (ms), time (01:00), elapsed (s)) from both Accelerometer and Gyroscope DataFrames.
Converted epoch timestamps to datetime indices for accurate time-based operations.

#### Resampling and Aggregation:
Resampled the data at a frequency of 200 milliseconds to ensure consistent time intervals.
Applied aggregation functions to compute mean values for sensor readings and preserved the latest values for categorical data.

#### Final Data Preparation:
Merged Accelerometer and Gyroscope DataFrames.
Renamed columns for clarity and consistency.
Converted the set column to integer type for consistency.

#### Export:
Saved the processed dataset to a pickle file (01_data_processed.pkl) for future use.

## Next Steps
### Step 2: Data Visualization
### Step 3: Detecting Outliers
### Step 4: Predictive Modelling
### Step 5: Counting Repetitions

## References

Book: [Hoogendoorn, M. and Funk, B., Machine Learning for the Quantified Self - On the Art of Learning from Sensory Data, Springer, 2018.](https://link.springer.com/book/10.1007/978-3-319-66308-1)

Code snippets retrieved from: https://github.com/mhoogen/ML4QS
