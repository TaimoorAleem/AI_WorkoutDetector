# Phase 0 baseline: orchestrator review

> Written by hand. It interprets [`phase0-baseline.md`](phase0-baseline.md), which is generated and must not be edited.
> Run: MLflow parent `f5231a5df0974e1e94a80b04f2cfe6b6` on `dev` at merge `c7b7566`, data md5 `45732a83275a4045dd1b7b5a8c229a14.dir`. 292 s, deterministic: the subagent's earlier run produced identical metrics.

## Headline
The honest baseline is **0.963 ± 0.047 window accuracy, 0.957 ± 0.054 macro-F1 and 0.963 ± 0.052 set accuracy** under leave-one-participant-out (LOPO) evaluation.

The best combination was the neural network (NN) on the basic + derived + in-fold PCA + temporal features. The old pipeline reported 0.9994, but that number was inflated by five leaks:

1. **L1:** a random split over overlapping windows.
2. **L2:** the whole-set `duration` feature, which forward selection picked second.
3. **L3:** windows that ran across set boundaries.
4. **L4:** PCA and outlier statistics fitted on the whole dataset.
5. **Duplicate files:** 23 raw files are byte-identical re-exports of other sets (D-016). With a random split, the copy of a set could land in test while the original sat in training.

The spec's leak check (flag anything ≥ 0.99) did not trip. Errors concentrate where the physics predicts, which is further evidence the remaining number is real.

## What the numbers say

**1. Exercise recognition works on the source data, and set level is the right product metric.**
- Window and set accuracy are both about 0.96 for the best model.
- KNN on all features reaches 0.986 set accuracy, because majority voting smooths out window-level errors.
- The product reports exercises per set (D-013), so **set accuracy is the primary product metric**, with window macro-F1 as the diagnostic.
- On the source data, the classifier clears the §6.5 thresholds for set accuracy (≥ 0.90) and macro-F1 (≥ 0.85).
- These are source-domain numbers. The gates themselves apply to watch data in Phase 3.

**2. The one real failure mode is bench vs OHP.**
- 116 bench windows were predicted as OHP and 38 OHP windows as bench. Almost every other cell of the pooled confusion matrix is zero.
- Bench recall is 0.715 for participant B and 0.574 for participant C. The per-participant "class recall ≥ 0.75" gate fails for C.
- **Why:** both are pressing movements with the forearm near vertical, so their mean gravity vectors are nearly the same:
  - bench (−0.11, 0.94, −0.17) g
  - OHP (−0.24, 0.92, −0.12) g
- They differ mainly by about 0.13 g on x, plus range of motion and tempo.
- **Implication for the watch:** an axis-alignment error of only a few degrees in the fitted rotation R (§6.3) is about the size of this difference. **Bench/OHP is the canary for domain shift.** The Phase 3 report must show this pair's confusion separately, and the Kabsch residual gate (< 0.15 g) may be too loose for it. Revisit it after the Phase 1 rotation-sensitivity test (below).

**3. The model leans on absolute orientation.**
- Temporal features add about 7 pp of macro-F1 over raw sensor values (0.861 → 0.957 for the best models).
- The temporal statistics are computed on the raw axes (e.g. `acc_y_temp_min_ws_10`, the top pick in the old forward selection).
- So the classifier is **orientation-dependent by construction**. That's fine on a single fixed device, but it's the main risk on a different watch.
- This is why §6.6 step 2 (orientation-robust features) exists. We should measure that sensitivity before collecting any watch data.

**4. Frequency features add nothing.**
- "all" (107 features) scores 0.953 for the NN, versus 0.957 for the temporal set (59 features).
- The FFT features also dominate feature-engineering runtime.
- Candidate for removal in Phase 4, unless the PyTorch model makes the hand-made features moot.

**5. Fold variance is large; don't over-read the ranking.**
- There are 5 participants. B and D have only 9 sets each and lack several classes.
- Across folds, std is ±0.05 for the top models and up to ±0.21 for decision trees and naive Bayes.
- The top four rows are within noise of each other. Picking the NN is a tiebreak, not a finding.
- Any model claim in this project needs CIs. Phase 3 will use Wilson intervals at the set level.

**6. The rep counter is the weakest component.**

| | MAE | exact | ±1 |
|---|---|---|---|
| Overall (78 sets) | 1.05 | 40% | 81% |
| OHP | 1.90 | 20% | 65% |

- It fails every §6.5 rep gate on its own data:
  - MAE ≤ 1.0
  - exact ≥ 70%
  - ±1 ≥ 90%
  - per-exercise MAE ≤ 1.5
- The figures are optimistic twice over: the cutoffs were tuned on this data, and the expected counts are assumed from load category, not counted.
- It got worse after cleaning (MAE 0.89 → 1.05). That's mostly from removing the duplicate sets, which happened to be easy ones, plus the outlier interpolation.
- **Implications:**
  - Phase 2's recording app must capture a user-confirmed rep count (it does: the rep picker).
  - U2 video gives true counts.
  - The Phase 4 rep-counter rework is required, not optional.

## Changes to the plan this review triggers
1. **Phase 1: add a rotation-sensitivity test (orchestrator design, Sonnet implementation).**
   - Using the fake-watch generator, rotate the canonical signals by known small angles (2°, 5°, 10°, 20° about each axis) and re-score the best model.
   - The result is a curve of set accuracy and bench/OHP confusion against rotation error.
   - That curve sets the real tolerance for the R fit, and tells us now whether orientation-robust features are needed before any watch data exists.
2. **Phase 3 domain-shift report:** report bench/OHP confusion as its own line item next to the gates.
3. **Phase 4:** evaluate dropping the FFT features. Rep-counter rework is mandatory.
4. **Model serialization:** MLflow 3.17's default skops format rejects the custom `PCAFeatures` transformer, so the model is logged with cloudpickle. Loading it requires `workout_ml` installed. That's acceptable for now: inference-svc installs the package anyway (CSF-1 lives there). The Phase 4 ONNX export removes the dependency for the CNN.

## Data findings
- **23 duplicate raw files** (`*_1.4.41.csv`) are exact re-exports of their `*_1.4.4.csv` twins, timestamps shifted by exactly +24 h. All 23 pairs were verified value-identical by the orchestrator.
  - The loader ignores them and logs each one.
  - The dataset is **82 unique sets** (A 27, B 9, C 14, D 9, E 23). Before, it looked like 94 acc and 93 gyro files.
  - The old pipeline double-counted 11 sets plus one accelerometer-only file.
- **Corrected sensor facts** (excluding duplicates):
  - median |a| = 1.001 g
  - gyro p99 = 79 deg/s
  - median set length 18.2 s
  - Per-exercise gravity vectors are unchanged to 2 decimals. `PROJECT.md` §6.1 is updated.
