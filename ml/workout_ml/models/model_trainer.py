"""
Model training and evaluation pipeline
"""
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.metrics import (
    accuracy_score, precision_score, recall_score, f1_score,
    confusion_matrix, classification_report
)
import matplotlib.pyplot as plt
import seaborn as sns
import itertools
import pickle
import logging
from pathlib import Path
from workout_ml.models.LearningAlgorithms import ClassificationAlgorithms

logger = logging.getLogger(__name__)


class ModelTrainer:
    """Train and evaluate classification models"""
    
    def __init__(self, config=None, output_dir="../../models"):
        """
        Args:
            config: Configuration dictionary
            output_dir: Directory to save models and metrics
        """
        self.config = config or {}
        self.output_dir = Path(output_dir)
        self.output_dir.mkdir(parents=True, exist_ok=True)
        self.learner = ClassificationAlgorithms()
        self.selected_features = None
    
    def prepare_data(self, df, participant_split=None):
        """
        Prepare training data
        
        Args:
            df: Feature-engineered DataFrame
            participant_split: If specified, split by participant (e.g., 'A' for test)
            
        Returns:
            tuple: (X_train, X_test, y_train, y_test)
        """
        logger.info("Preparing training data")
        
        # Drop non-feature columns
        if participant_split:
            # Split by participant for generalization testing
            participant_df = df.drop(["set", "category"], axis=1, errors="ignore")
            
            X_train = participant_df[participant_df["participant"] != participant_split].drop("label", axis=1)
            y_train = participant_df[participant_df["participant"] != participant_split]["label"]
            
            X_test = participant_df[participant_df["participant"] == participant_split].drop("label", axis=1)
            y_test = participant_df[participant_df["participant"] == participant_split]["label"]
            
            # Drop participant column
            X_train = X_train.drop("participant", axis=1)
            X_test = X_test.drop("participant", axis=1)
        else:
            # Regular train-test split
            df_train = df.drop(["participant", "category", "set"], axis=1, errors="ignore")
            
            X = df_train.drop("label", axis=1)
            y = df_train["label"]
            
            # Train-test split
            X_train, X_test, y_train, y_test = train_test_split(
                X, y,
                test_size=self.config.get("test_size", 0.2),
                random_state=self.config.get("random_state", 42),
                stratify=y if self.config.get("stratify", True) else None
            )
        
        logger.info(f"Train size: {len(X_train)}, Test size: {len(X_test)}")
        logger.info(f"Classes: {y_train.unique()}")
        
        return X_train, X_test, y_train, y_test
    
    def select_features(self, X_train, y_train, max_features=10):
        """
        Perform forward feature selection
        
        Args:
            X_train: Training features
            y_train: Training labels
            max_features: Maximum number of features to select
            
        Returns:
            tuple: (selected_features, ordered_features, ordered_scores)
        """
        logger.info(f"Performing forward feature selection (max {max_features} features)")
        
        selected_features, ordered_features, ordered_scores = self.learner.forward_selection(
            max_features, X_train, y_train
        )
        
        self.selected_features = selected_features
        logger.info(f"Selected features: {selected_features}")
        
        return selected_features, ordered_features, ordered_scores
    
    def train_single_model(self, X_train, y_train, X_test, model_type="random_forest", gridsearch=True):
        """
        Train a single classification model
        
        Args:
            X_train: Training features
            y_train: Training labels
            X_test: Test features
            model_type: Type of model to train
            gridsearch: Whether to perform grid search
            
        Returns:
            tuple: (train_predictions, test_predictions, train_probabilities, test_probabilities)
        """
        logger.info(f"Training {model_type} model (gridsearch={gridsearch})")
        
        if model_type == "random_forest":
            return self.learner.random_forest(X_train, y_train, X_test, gridsearch=gridsearch)
        elif model_type == "neural_network":
            return self.learner.feedforward_neural_network(X_train, y_train, X_test, gridsearch=gridsearch)
        elif model_type == "knn":
            return self.learner.k_nearest_neighbor(X_train, y_train, X_test, gridsearch=gridsearch)
        elif model_type == "decision_tree":
            return self.learner.decision_tree(X_train, y_train, X_test, gridsearch=gridsearch)
        elif model_type == "naive_bayes":
            return self.learner.naive_bayes(X_train, y_train, X_test)
        elif model_type == "svm":
            return self.learner.support_vector_machine_with_kernel(X_train, y_train, X_test, gridsearch=gridsearch)
        else:
            raise ValueError(f"Unknown model type: {model_type}")
    
    def compare_models(self, X_train, y_train, X_test, y_test, feature_sets, feature_names, iterations=1):
        """
        Compare multiple models across different feature sets
        
        Args:
            X_train: Training features
            y_train: Training labels
            X_test: Test features
            y_test: Test labels
            feature_sets: List of feature sets to compare
            feature_names: Names for each feature set
            iterations: Number of iterations for non-deterministic models
            
        Returns:
            DataFrame with comparison results
        """
        logger.info(f"Comparing models across {len(feature_sets)} feature sets")
        
        score_df = pd.DataFrame()
        
        for i, f in zip(range(len(feature_sets)), feature_names):
            logger.info(f"Testing feature set {i}: {f}")
            selected_train_X = X_train[feature_sets[i]]
            selected_test_X = X_test[feature_sets[i]]
            
            # Non-deterministic classifiers - average over iterations
            performance_test_nn = 0
            performance_test_rf = 0
            
            for it in range(iterations):
                # Neural Network
                _, class_test_y, _, _ = self.learner.feedforward_neural_network(
                    selected_train_X, y_train, selected_test_X, gridsearch=False
                )
                performance_test_nn += accuracy_score(y_test, class_test_y)
                
                # Random Forest
                _, class_test_y, _, _ = self.learner.random_forest(
                    selected_train_X, y_train, selected_test_X, gridsearch=True
                )
                performance_test_rf += accuracy_score(y_test, class_test_y)
            
            performance_test_nn /= iterations
            performance_test_rf /= iterations
            
            # Deterministic classifiers
            _, class_test_y, _, _ = self.learner.k_nearest_neighbor(
                selected_train_X, y_train, selected_test_X, gridsearch=True
            )
            performance_test_knn = accuracy_score(y_test, class_test_y)
            
            _, class_test_y, _, _ = self.learner.decision_tree(
                selected_train_X, y_train, selected_test_X, gridsearch=True
            )
            performance_test_dt = accuracy_score(y_test, class_test_y)
            
            _, class_test_y, _, _ = self.learner.naive_bayes(
                selected_train_X, y_train, selected_test_X
            )
            performance_test_nb = accuracy_score(y_test, class_test_y)
            
            # Save results
            models = ["NN", "RF", "KNN", "DT", "NB"]
            new_scores = pd.DataFrame({
                "model": models,
                "feature_set": f,
                "accuracy": [
                    performance_test_nn,
                    performance_test_rf,
                    performance_test_knn,
                    performance_test_dt,
                    performance_test_nb,
                ],
            })
            score_df = pd.concat([score_df, new_scores])
        
        return score_df
    
    def evaluate(self, y_test, y_pred):
        """
        Evaluate model performance
        
        Args:
            y_test: Test labels
            y_pred: Predicted labels
            
        Returns:
            dict: Performance metrics
        """
        logger.info("Evaluating model")
        
        metrics = {
            "accuracy": accuracy_score(y_test, y_pred),
            "precision": precision_score(y_test, y_pred, average="weighted", zero_division=0),
            "recall": recall_score(y_test, y_pred, average="weighted", zero_division=0),
            "f1": f1_score(y_test, y_pred, average="weighted", zero_division=0),
            "confusion_matrix": confusion_matrix(y_test, y_pred),
            "classification_report": classification_report(y_test, y_pred, zero_division=0)
        }
        
        logger.info(f"Accuracy: {metrics['accuracy']:.4f}")
        logger.info(f"F1 Score: {metrics['f1']:.4f}")
        
        return metrics
    
    def plot_confusion_matrix(self, y_test, y_pred, classes=None, title="Confusion Matrix", save_path=None):
        """
        Plot confusion matrix
        
        Args:
            y_test: True labels
            y_pred: Predicted labels
            classes: Class labels
            title: Plot title
            save_path: Path to save the plot
        """
        if classes is None:
            classes = sorted(y_test.unique())
        
        cm = confusion_matrix(y_test, y_pred, labels=classes)
        
        plt.figure(figsize=(10, 10))
        plt.imshow(cm, interpolation="nearest", cmap=plt.cm.Blues)
        plt.title(title)
        plt.colorbar()
        tick_marks = np.arange(len(classes))
        plt.xticks(tick_marks, classes, rotation=45)
        plt.yticks(tick_marks, classes)
        
        thresh = cm.max() / 2.0
        for i, j in itertools.product(range(cm.shape[0]), range(cm.shape[1])):
            plt.text(
                j, i, format(cm[i, j]),
                horizontalalignment="center",
                color="white" if cm[i, j] > thresh else "black",
            )
        
        plt.ylabel("True label")
        plt.xlabel("Predicted label")
        plt.grid(False)
        plt.tight_layout()
        
        if save_path:
            plt.savefig(save_path)
            logger.info(f"Confusion matrix saved to {save_path}")
        
        plt.show()
    
    def plot_feature_importance(self, scores, features, title="Forward Feature Selection", save_path=None):
        """
        Plot feature selection scores
        
        Args:
            scores: List of scores
            features: List of selected features
            title: Plot title
            save_path: Path to save the plot
        """
        plt.figure(figsize=(12, 6))
        plt.plot(np.arange(1, len(scores) + 1, 1), scores, marker='o')
        plt.xlabel("Number of features selected")
        plt.ylabel("Accuracy Score")
        plt.xticks(np.arange(1, len(scores) + 1, 1))
        plt.title(title)
        plt.grid(True, alpha=0.3)
        plt.tight_layout()
        
        if save_path:
            plt.savefig(save_path)
            logger.info(f"Feature importance plot saved to {save_path}")
        
        plt.show()
    
    def plot_model_comparison(self, score_df, title="Model Performance Comparison", save_path=None):
        """
        Plot model comparison across feature sets
        
        Args:
            score_df: DataFrame with model comparison results
            title: Plot title
            save_path: Path to save the plot
        """
        plt.figure(figsize=(12, 6))
        sns.barplot(x="model", y="accuracy", hue="feature_set", data=score_df)
        plt.title(title)
        plt.ylabel("Accuracy")
        plt.xlabel("Model")
        plt.ylim(0.7, 1)
        plt.legend(loc="lower right")
        plt.tight_layout()
        
        if save_path:
            plt.savefig(save_path)
            logger.info(f"Model comparison plot saved to {save_path}")
        
        plt.show()
    
    def save_model(self, model_predictions, model_name="model.pkl"):
        """
        Save model predictions (since LearningAlgorithms returns predictions, not models)
        Note: To save actual trained models, the LearningAlgorithms class would need to be modified
        
        Args:
            model_predictions: Model predictions tuple
            model_name: Name for the saved model file
        """
        model_path = self.output_dir / model_name
        pickle.dump(model_predictions, open(model_path, "wb"))
        logger.info(f"Model predictions saved to {model_path}")
        
        return model_path
    
    def save_metrics(self, metrics, metrics_name="metrics.csv"):
        """
        Save metrics to CSV
        
        Args:
            metrics: Dictionary of metrics
            metrics_name: Name for the metrics file
        """
        metrics_df = pd.DataFrame({
            "Metric": ["Accuracy", "Precision", "Recall", "F1-Score"],
            "Score": [
                metrics["accuracy"],
                metrics["precision"],
                metrics["recall"],
                metrics["f1"]
            ]
        })
        
        metrics_path = self.output_dir / metrics_name
        metrics_df.to_csv(metrics_path, index=False)
        logger.info(f"Metrics saved to {metrics_path}")
        
        return metrics_df
