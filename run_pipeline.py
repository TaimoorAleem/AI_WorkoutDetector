"""
Pipeline script demonstrating end-to-end ML workflow
"""
import sys
from pathlib import Path
import pandas as pd
import logging

# Add src to path
project_root = Path(__file__).parent
sys.path.insert(0, str(project_root / "src"))

from src.data.preprocessor import DataLoader, OutlierRemover
from src.features.engineer_features import FeatureEngineer
from src.models.model_trainer import ModelTrainer
from src import config

# Setup logging
logging.basicConfig(
    level=logging.INFO,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)


def main():
    """Run the complete ML pipeline"""
    
    # Step 1: Load and preprocess data
    logger.info("=" * 50)
    logger.info("STEP 1: Loading and preprocessing data")
    logger.info("=" * 50)
    
    loader = DataLoader(config.RAW_DATA_PATH)
    acc_df, gyr_df = loader.load_data()
    
    # Merge sensor data
    merged_df = loader.merge_sensors(acc_df, gyr_df)
    
    # Resample to 200ms intervals
    resampled_df = loader.resample_data(merged_df, sampling_rule="200ms")
    
    # Remove outliers using method from config
    outlier_method = config.OUTLIER_CONFIG["method"]
    logger.info(f"Using {outlier_method} method for outlier detection")
    
    outlier_remover = OutlierRemover()
    sensor_columns = ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]
    
    # Prepare outlier kwargs based on config
    outlier_kwargs = {}
    if config.OUTLIER_CONFIG.get("per_label"):
        outlier_kwargs["per_label"] = True
    if outlier_method == "chauvenet":
        outlier_kwargs["C"] = config.OUTLIER_CONFIG.get("C", 2)
    elif outlier_method == "lof":
        outlier_kwargs["n"] = config.OUTLIER_CONFIG.get("n", 20)
    
    outliers_removed_df = outlier_remover.remove_outliers(
        resampled_df, 
        sensor_columns, 
        method=outlier_method,
        **outlier_kwargs
    )
    
    # Save interim data
    interim_path = config.INTERIM_DATA_PATH / "01_data_processed.pkl"
    config.INTERIM_DATA_PATH.mkdir(parents=True, exist_ok=True)
    outliers_removed_df.to_pickle(interim_path)
    logger.info(f"Saved preprocessed data to {interim_path}")
    
    # Step 2: Feature Engineering
    logger.info("=" * 50)
    logger.info("STEP 2: Engineering features")
    logger.info("=" * 50)
    
    engineer = FeatureEngineer(config.FEATURE_CONFIG)
    featured_df = engineer.engineer_features(outliers_removed_df)
    
    # Save featured data
    featured_path = config.INTERIM_DATA_PATH / "02_feature_engineered.pkl"
    featured_df.to_pickle(featured_path)
    logger.info(f"Saved feature-engineered data to {featured_path}")
    
    # Step 3: Model Training
    logger.info("=" * 50)
    logger.info("STEP 3: Training models")
    logger.info("=" * 50)
    
    trainer = ModelTrainer(config=config.TRAIN_CONFIG, output_dir=config.MODELS_DIR)
    
    # Prepare data
    X_train, X_test, y_train, y_test = trainer.prepare_data(featured_df)
    
    # Feature selection
    logger.info("\nPerforming forward feature selection...")
    selected_features, ordered_features, ordered_scores = trainer.select_features(
        X_train, y_train, max_features=10
    )
    
    # Plot feature selection results
    trainer.plot_feature_importance(
        ordered_scores, 
        ordered_features,
        save_path=config.PLOTS_DIR / "feature_selection.png"
    )
    
    # Define feature sets for comparison
    basic_features = ["acc_x", "acc_y", "acc_z", "gyr_x", "gyr_y", "gyr_z"]
    square_features = ["acc_r", "gyr_r"] if "acc_r" in X_train.columns else []
    pca_features = ["pca_1", "pca_2", "pca_3"] if "pca_1" in X_train.columns else []
    time_features = [f for f in X_train.columns if "_temp_" in f]
    frequency_features = [f for f in X_train.columns if ("_freq_" in f) or ("_pse" in f)]
    
    feature_set_1 = list(set(basic_features))
    feature_set_2 = list(set(basic_features + square_features + pca_features))
    feature_set_3 = list(set(basic_features + square_features + pca_features + time_features))
    feature_set_4 = list(set(feature_set_3 + frequency_features))
    
    possible_feature_sets = [
        feature_set_1,
        feature_set_2, 
        feature_set_3,
        feature_set_4,
        selected_features
    ]
    
    feature_names = [
        "Basic Features",
        "Basic + Derived + PCA",
        "Basic + Derived + PCA + Temporal",
        "All Features",
        "Selected Features"
    ]
    
    # Compare models (use iterations=1 for speed, increase for better results)
    logger.info("\nComparing models across feature sets...")
    score_df = trainer.compare_models(
        X_train, y_train, X_test, y_test,
        possible_feature_sets, feature_names,
        iterations=1
    )
    
    # Display results
    logger.info("\nModel Comparison Results:")
    logger.info(score_df.sort_values(by=["accuracy"], ascending=False).to_string())
    
    # Plot comparison
    trainer.plot_model_comparison(
        score_df,
        save_path=config.PLOTS_DIR / "model_comparison.png"
    )
    
    # Identify and train the best performing model from comparison
    best_row = score_df.sort_values(by=["accuracy"], ascending=False).iloc[0]
    best_model = best_row["model"]
    best_feature_set_name = best_row["feature_set"]
    best_accuracy = best_row["accuracy"]
    
    # Find the corresponding feature set
    best_feature_idx = feature_names.index(best_feature_set_name)
    best_features = possible_feature_sets[best_feature_idx]
    
    logger.info(f"\nTraining best model: {best_model} with {best_feature_set_name} (accuracy: {best_accuracy:.4f})...")
    train_pred, test_pred, train_prob, test_prob = trainer.train_single_model(
        X_train[best_features],
        y_train,
        X_test[best_features],
        model_type=best_model,
        gridsearch=True
    )
    
    # Evaluate
    metrics = trainer.evaluate(y_test, test_pred)
    logger.info(f"\nFinal Model Performance:")
    logger.info(f"Accuracy: {metrics['accuracy']:.4f}")
    logger.info(f"Precision: {metrics['precision']:.4f}")
    logger.info(f"Recall: {metrics['recall']:.4f}")
    logger.info(f"F1-Score: {metrics['f1']:.4f}")
    
    # Save metrics
    trainer.save_metrics(metrics, "final_model_metrics.csv")
    
    # Plot confusion matrix
    trainer.plot_confusion_matrix(
        y_test, test_pred,
        title="Final Model Confusion Matrix",
        save_path=config.PLOTS_DIR / "confusion_matrix.png"
    )
    
    logger.info("=" * 50)
    logger.info("Pipeline completed successfully!")
    logger.info("=" * 50)


if __name__ == "__main__":
    main()
