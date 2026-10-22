# Spec: Phase 0.4 — leakage-free baseline with MLflow tracking

> Owner of this spec: orchestrator. Implementation: Sonnet subagent. Status: approved for implementation.
> Context: [`PROJECT.md`](../PROJECT.md) §3 (known defects), §6.5 (gates), §6.7 (MLflow). Decision log: D-015.

## Why
The current baseline (decision tree, accuracy 0.9994) can't be trusted. Four things inflate it:

| # | Leak | Where | Effect |
|---|---|---|---|
| L1 | Random 80/20 split over overlapping 200 ms rows | `ModelTrainer.prepare_data` | Near-duplicate neighbours of every test row are in training |
| L2 | `duration` is a whole-set feature, picked 2nd by forward selection | `FeatureEngineer._calculate_derived_features` | Uses the set's future; at 5 vs 10 reps it nearly encodes the load category |
| L3 | Low-pass, rolling and FFT windows run across set and participant boundaries | `FeatureEngineer` (no per-set grouping) | Features mix data from different sets and labels; the first rows of each set carry the previous set |
| L4 | PCA, and outlier statistics, fitted on the full dataset | `FeatureEngineer.pca`, `OutlierRemover` | Test participants shape the training transforms |

Also: Chauvenet **drops rows**, which leaves gaps in time that the filters assume aren't there. Hyperparameter search and forward selection use random-KFold CV inside the training data, which leaks between neighbouring windows.

## Design principles
1. **Anything computed at inference time must be computable from the current set alone, without labels.** Per-set preprocessing is fine. Per-label preprocessing isn't: labels aren't known at inference.
2. **Anything fitted (scalers, PCA, feature selection, hyperparameters) is fitted inside the training fold only.** Use sklearn `Pipeline` objects.
3. **Evaluation is grouped by participant (LOPO).** Any inner CV is grouped by participant as well (`GroupKFold`).

## Changes

### C1. Loading: deterministic set IDs
- `DataLoader`: derive the set ID from the sorted, unique file stem (the part before `_Accelerometer`/`_Gyroscope`), not from two independent counters that depend on glob order.
- Pair the acc and gyro files by stem. Log a WARNING for any stem missing one of the two sensors. There is at least one: 94 acc vs 93 gyro files. Drop that set, which is what happens today implicitly.
- Sort files so results don't depend on the OS.

### C2. Outliers: per set, mark and interpolate
- New default `OUTLIER_CONFIG = {"method": "chauvenet", "scope": "set", "C": 2}`.
- Chauvenet runs **per set per column** using that set's mean/std. It **marks outliers as NaN**, then the values are linearly interpolated within the set (`limit_direction="both"`). No rows are dropped.
- Keep the `iqr`/`lof` options working under the same mark-and-interpolate contract (LOF scope = set).
- Remove `per_label`: it's supervised and impossible at inference. Leave a comment saying why.

### C3. Feature engineering: per set, causal-safe, no `duration`
- Every windowed step runs **per set** (`groupby("set", group_keys=False)`): low-pass (filtfilt is fine, since it's applied to a complete set after the set ends, which matches per-set inference, D-013), rolling stats (windows 5 and 10), and FFT (window 10).
- After windowing, drop rows that lack a full window **within their set** (the first `max(window)` rows of each set). Log the count.
- Remove `duration`. Keep `acc_r` and `gyr_r` (computed per row, no leakage).
- Remove PCA from `FeatureEngineer` and move it to C4.
- Build all new columns at once with `pd.concat(axis=1)`. This also fixes the pandas fragmentation warnings.
- The output keeps `participant`, `label`, `category` and `set` as metadata columns.

### C4. Modeling: sklearn pipelines with in-fold fitting
New module `workout_ml/models/lopo.py` (or similar):
- `PCAFeatures` transformer:
  - Fits min-max-style normalization and PCA(3) on the six raw sensor columns using **training rows only**.
  - Appends `pca_1..3`.
  - Used only by feature sets that include PCA.
- Feature sets (by column name pattern, as today):
  1. `basic`
  2. `basic+derived+pca`
  3. `+temporal`
  4. `all` (adds frequency)
- Models: RF, DT, KNN, NB, NN (MLP).
  - Each is wrapped in a `Pipeline` with a `StandardScaler` where the model needs it (KNN, NN).
  - **Fixed, sensible default hyperparameters**; no grid search by default.
  - Optional `--tune` flag: `GridSearchCV` with `GroupKFold` by participant on the training fold, with a small grid.
- Forward selection: **off by default** (it took about 25 minutes and leaked through random CV). Optional `--forward-selection` runs it inside each training fold with grouped inner CV. If it's kept, the `selected` feature set is fold-specific.

### C5. Evaluation: LOPO plus set-level vote
- Use `LeaveOneGroupOut` over `participant` (5 folds, A–E).
- **Per fold:**
  - Window-level accuracy and macro-F1, using `labels=` set to the classes present in that test fold. Some participants lack classes; for example B has no dead, row or rest.
  - Per-class recall.
  - **Set-level accuracy:** majority vote of window predictions per set, compared with the set label.
- **Aggregate across folds:**
  - Mean ± std of each metric.
  - A pooled confusion matrix over all folds' predictions.
- **Model selection:** pick the best (feature_set, model) by **mean LOPO window macro-F1**, with set-level accuracy as the tiebreak. Refit it on all data. That refit is the model logged as the baseline artifact.
- **Rep counter** (from task 0.2, `workout_ml.reps`):
  - Evaluate on all non-rest sets, reporting MAE, exact-match % and ±1 %, overall and per exercise.
  - Note in the report that its cutoffs were hand-tuned on this same data, so its numbers are optimistic.

### C6. MLflow tracking
- **Tracking URI:** `MLFLOW_TRACKING_URI` if set. Otherwise `sqlite:///<repo>/mlflow.db`, with artifacts under `<repo>/mlartifacts`. Add `mlflow.db` to `.gitignore`.
- **Experiment:** `proprio-classifier`.
- **Parent run** per pipeline invocation:
  - Params: outlier config, feature config, eval config, `cv=LOPO`, `n_sets`, `n_windows`.
  - Tags: git SHA, git dirty flag, `data.raw.md5` read from `data/raw/MetaMotion.dvc`, `phase=0`.
  - Metrics: the rep-counter metrics.
  - Artifacts: the baseline report markdown.
- **Child run** per (feature_set, model):
  - Params: model name, hyperparameters, feature set name, n_features.
  - Metrics: per-fold metrics (with `step=fold_index`), plus `lopo_window_acc_mean/std`, `lopo_window_macro_f1_mean/std`, `lopo_set_acc_mean/std`, and per-class recall means.
  - Artifacts: per-fold metrics CSV and the confusion-matrix PNG.
- **Best model:** logged in its child run with `mlflow.sklearn.log_model`, including the signature and an input example. **Don't register it** (the registry comes in Phase 4).
- All plots are closed after saving. Never call `plt.show()` in pipeline code.

### C7. Report
- Write `docs/reports/phase0-baseline.md` (generated tables, committed). It contains:
  - The leaderboard: all feature_set × model rows with mean ± std of the LOPO metrics.
  - Per-fold detail for the best model.
  - The pooled confusion matrix as a table.
  - The rep-counter table.
  - The MLflow run ID.
  - The data md5.
- The orchestrator adds the interpretation afterwards.

### C8. Remove the leaky paths
- `pipeline.py` no longer does a random split, forward selection by default, or train-on-test-split.
- Delete code left unused by these changes (for example `ModelTrainer.prepare_data`'s random split and the old comparison/plot helpers, if nothing else uses them).
- `LearningAlgorithms.py` may stay for now if still referenced; otherwise delete it (it's in git history).

## Tests (pytest, synthetic data, fast; no raw-data dependency)
- **Set isolation:**
  - Two adjacent sets with very different constant signals. The windowed features of set 2's rows contain no contribution from set 1.
  - No row from set 2 is kept unless it has a full in-set window.
- **No `duration`:** it's absent from the feature output.
- **Outliers:**
  - A spike inside a set becomes NaN, then an interpolated value. Row count is unchanged.
  - Stats are per set: a value normal in set 1 but extreme in set 2 is flagged only in set 2.
- **LOPO:** no participant appears in both train and test in any fold, and there are 5 folds on 5 participants.
- **In-fold PCA:** `PCAFeatures` fitted on train rows doesn't change when test rows change (refit check).
- **Majority vote:** correct on a hand-built example, including tie-handling. Ties break deterministically, by the first class in sorted order; document this.
- **MLflow smoke test:** an end-to-end run on a tiny synthetic dataset with `MLFLOW_TRACKING_URI=sqlite:///<tmp>/mlflow.db`. It creates the parent run, at least one child run with `lopo_window_macro_f1_mean`, and a logged model.
- **Loader:** deterministic set IDs from stems, and the unpaired-file warning.

## Acceptance criteria
1. `pytest ml/tests` and `ruff check` / `ruff format --check` pass.
2. `python -m workout_ml.pipeline` completes on the real data in **under 15 minutes** without `--tune` and `--forward-selection`.
3. The MLflow UI (`mlflow ui --backend-store-uri sqlite:///mlflow.db`) shows the parent and child runs with the metrics and artifacts above.
4. `docs/reports/phase0-baseline.md` exists, with the tables above.
5. No leak paths remain: no random split, no `duration`, no cross-set windows, nothing fitted outside a fold.

## Expected outcome (for the orchestrator's review, not a target)
LOPO accuracy should drop noticeably from 0.999. Somewhere in the 0.85–0.97 range for the best model would be plausible on this data. Expect bench vs ohp confusion, since both are pressing movements with similar wrist orientation, and weak recall for participants with few sets.

If LOPO comes out ≥ 0.99, treat that as a sign that something still leaks, and investigate before accepting.
