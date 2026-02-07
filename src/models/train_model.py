import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
import matplotlib.pyplot as plt
from LearningAlgorithms import ClassificationAlgorithms
import seaborn as sns
import itertools
from sklearn.metrics import accuracy_score, confusion_matrix
from LearningAlgorithms import ClassificationAlgorithms


# Plot settings
plt.style.use("fivethirtyeight")
plt.rcParams["figure.figsize"] = (20, 5)
plt.rcParams["figure.dpi"] = 100
plt.rcParams["lines.linewidth"] = 2

df = pd.read_pickle("../../data/interim/03_feature_engineered_dataset.pkl")

# Create a training and test set

df_train = df.drop(["participant", "category", "set"], axis=1)

X = df_train.drop("label", axis=1)
Y = df_train["label"]

X_train, X_test, Y_train, Y_test = train_test_split(X, Y, test_size=0.2, random_state=42, stratify=Y)

fig, ax = plt.subplots(figsize=(10, 5))
df_train["label"].value_counts().plot(
    kind="bar", title="Class distribution in the dataset", ax=ax, color=["lightblue"], label="Total"
)
Y_train.value_counts().plot(kind="bar", ax=ax, color=["dodgerblue"], label="Train")
Y_test.value_counts().plot(kind="bar", ax=ax, color=["royalblue"], label="Test")
plt.legend()
plt.show()

# Split feature subsets

basic_features = ["acc_x", "acc_y", "acc_z", "gyro_x", "gyro_y", "gyro_z"]
square_features = ["acc_r", "gyro_r"]
pca_features = ["pca_1", "pca_2", "pca_3"]
time_features = [f for f in df_train.columns if "_temp_" in f]
frequency_features = [f for f in df_train.columns if ("_freq_" in f) or ("_pse" in f)]
cluster_features = ["cluster"]

print("Basic features:", len(basic_features))
print("Square features:", len(square_features))
print("PCA features:", len(pca_features))
print("Time features:", len(time_features))
print("Frequency features:", len(frequency_features))
print("Cluster features:", len(cluster_features))

feature_set_1 = list(set(basic_features))
feature_set_2 = list(set(basic_features + square_features + pca_features))
feature_set_3 = list(set(basic_features + square_features + pca_features + time_features))
feature_set_4 = list(set(basic_features + square_features + pca_features + frequency_features + cluster_features))

# Perform forward feature selection using simple decision tree

learner = ClassificationAlgorithms()

max_features = 10
selected_features, ordered_features, ordered_scores = learner.forward_selection(
    max_features, X_train, Y_train
)

selected_features = ['acc_y_freq_0.0_Hz_ws_10',
 'duration',
 'acc_x_freq_0.0_Hz_ws_10',
 'acc_z_temp_std_ws_5',
 'acc_z_freq_0.0_Hz_ws_10',
 'acc_x_freq_2.0_Hz_ws_10',
 'gyr_x_max_freq',
 'gyr_y_freq_2.5_Hz_ws_10',
 'acc_z_freq_2.5_Hz_ws_10',
 'acc_y_freq_2.5_Hz_ws_10'
 ]

plt.figure(figsize=(10, 5))
plt.plot(np.arange(1, max_features + 1, 1), ordered_scores)
plt.xlabel("Number of features selected")
plt.ylabel("Accuracy Score")
plt.xticks(np.arange(1, max_features + 1, 1))
plt.title("Forward Feature Selection")
plt.show()