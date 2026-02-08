import numpy as np
import pandas as pd
import matplotlib.pyplot as plt
from DataTransformation import LowPassFilter
from scipy.signal import argrelextrema
from sklearn.metrics import mean_absolute_error

from src import data

pd.options.mode.chained_assignment = None


# Plot settings
plt.style.use("fivethirtyeight")
plt.rcParams["figure.figsize"] = (20, 5)
plt.rcParams["figure.dpi"] = 100
plt.rcParams["lines.linewidth"] = 2

df = pd.read_pickle("../../data/interim/01_data_processed.pkl")
df = df[df["label"] != "rest"]

acc_r = np.sqrt(df["acc_x"]**2 + df["acc_y"]**2 + df["acc_z"]**2)
gyr_r = np.sqrt(df["gyr_x"]**2 + df["gyr_y"]**2 + df["gyr_z"]**2)

df["acc_r"] = acc_r
df["gyr_r"] = gyr_r

# Split data

bench_df = df[df["label"] == "bench"]
squat_df = df[df["label"] == "squat"]
row_df = df[df["label"] == "row"]
ohp_df = df[df["label"] == "ohp"]
dead_df = df[df["label"] == "dead"]

# Visualize data to identify patterns

plot_df = bench_df
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["acc_x"].plot()
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["acc_y"].plot()
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["acc_z"].plot()
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["acc_r"].plot()


plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["gyr_x"].plot()
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["gyr_y"].plot()
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["gyr_z"].plot()
plot_df[plot_df["set"] == plot_df["set"].unique()[0]]["gyr_r"].plot()

# Configure LowPassFilter
fs = 1000 / 200
LowPass = LowPassFilter()

# Apply and tweak LowPassFilter
bench_set_1 = bench_df[bench_df["set"] == bench_df["set"].unique()[0]]
squat_set_1 = squat_df[squat_df["set"] == squat_df["set"].unique()[0]]
row_set_1 = row_df[row_df["set"] == row_df["set"].unique()[0]]
ohp_set_1 = ohp_df[ohp_df["set"] == ohp_df["set"].unique()[0]]
dead_set_1 = dead_df[dead_df["set"] == dead_df["set"].unique()[0]]

bench_set_1["acc_r"].plot()

column = "acc_r"
LowPass.low_pass_filter(
    dead_set_1, col = column, sampling_frequency=fs, cutoff_frequency=0.4, order=5
)[column + "_lowpass"].plot()

# cutoff = 0.4 seems appropriate for all exercises except for row (0.6)

# Creating function to count repetitions using the local minima and maxima of the low pass filtered signal. We will use the acc_r signal for this, as it seems to be the most informative.
def count_repetitions(dataset, column="acc_r", cutoff_frequency=0.4, order=10):
    data = LowPass.low_pass_filter(
        dataset, col=column, sampling_frequency=fs, cutoff_frequency=cutoff_frequency, order=order
    )
    indexes = argrelextrema(data[column + "_lowpass"].values, np.greater)
    peaks = data.iloc[indexes]
    
    fig, ax = plt.subplots()
    plt.plot(dataset[f"{column}_lowpass"])
    plt.plot(peaks[f"{column}_lowpass"], "o", color="red")
    ax.set_ylabel(f"{column}_lowpass")
    exercise = dataset["label"].iloc[0].title()
    category = dataset["category"].iloc[0].title()
    plt.title(f"{category} - {exercise}: {len(peaks)} Repetitions")
    plt.show()
    
    return len(peaks)

count_repetitions(bench_set_1, cutoff_frequency=0.4)
count_repetitions(squat_set_1, cutoff_frequency=0.35)
count_repetitions(row_set_1, cutoff_frequency=0.65, column="gyr_x")
count_repetitions(ohp_set_1, cutoff_frequency=0.35)
count_repetitions(dead_set_1, cutoff_frequency=0.4)

# Creating benchmark dataframe with the actual number of repetitions per set, which we will use to evaluate our counting function.

df["reps"] = df["category"].apply(lambda x: 5 if x == "heavy" else 10)
rep_df = df.groupby(["label", "category", "set"])["reps"].max().reset_index()
rep_df["predicted_reps"] = 0

for s in df["set"].unique():
    subset = df[df["set"] == s]
    
    column = "acc_r"
    cutoff = 0.4
    if subset["label"].iloc[0] == "row":
        column = "gyr_x"
        cutoff = 0.65
    elif subset["label"].iloc[0] == "squat":
        cutoff = 0.35
    elif subset["label"].iloc[0] == "ohp":
        cutoff = 0.35
        
    reps = count_repetitions(subset, column=column, cutoff_frequency=cutoff)
    
    rep_df.loc[rep_df["set"] == s, "predicted_reps"] = reps
    
rep_df

error = mean_absolute_error(rep_df["reps"], rep_df["predicted_reps"]).round(2)
rep_df.groupby(["label", "category"])["reps", "predicted_reps"].mean().plot.bar()