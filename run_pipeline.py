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


if __name__ == "__main__":
    main()
