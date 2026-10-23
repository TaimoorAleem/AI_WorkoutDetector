# Spec: Phase 3 — watch evaluation harness, axis-rotation fit, domain-shift report

> Owner: orchestrator (evaluation criteria stay with the orchestrator). Implementation: Sonnet subagent (task H1). Status: approved for implementation.
> Context: [`PROJECT.md`](../PROJECT.md) §6.4–6.7 (protocol, gates, decision tree, pipeline); [`sensor_spec.md`](../sensor_spec.md) (CSF-1, profiles); decisions D-018, D-021, D-023–D-025; [`reports/u1-calibration.md`](../reports/u1-calibration.md).

## 1. Purpose
Turn watch sessions into a **pass/fail verdict** against the PROJECT.md §6.5 gates, using the same code path production will use: raw zip → `read_session` → `to_csf` with a device profile → `clean_outliers` → `FeatureEngineer` → model.

It produces:
1. A **fitted Galaxy Watch device profile**: rotation R from watch frame to MetaMotion frame (Kabsch).
2. A **domain-shift report** (generated markdown plus an MLflow run).
3. **DVC stages**, so `dvc repro` re-derives everything from the raw sessions.

The harness must work **Sunday evening on U2 session 1 alone**, in a clearly flagged in-sample mode. Its first real verdict comes once session 2 exists.

## 2. Data layout and datasets
- **Raw sessions** live in `data/raw/watch/<kind>/proprio-session-<id>.zip`, each zip including `labels.json`. `kind` ∈ {`calibration`, `gym`}. The whole `data/raw/watch` directory is one DVC-tracked output; it already exists, with U1.
- **Datasets** (logical names used in reports and MLflow):

| name | sessions | use |
|---|---|---|
| `watch-calib-v1` | U1 (`9390dce6…`) | calibration gates |
| `watch-fit-v1` | U2 session 1 | fitting R; tuning the rejection threshold τ; never used for the final verdict |
| `watch-val-v1` | U2 session 2 | **frozen held-out validation**: the verdict |
| `watch-u3-v1` | U3 friends' sessions | between-user variance (PROJECT §6.6 step 4) |

- A small checked-in file maps session ids to datasets: `data/watch_datasets.yaml`. It's text, not data. The pipeline reads it, and freezing `watch-val-v1` means adding its id there in a reviewed commit.

## 3. Modules (under `ml/workout_ml/watch/`)

### 3.1 `ingest.py`
- `load_watch_sessions(dataset, profile) -> pd.DataFrame`, in the same shape `DataLoader.load_data()` returns (200 ms CSF bins, the six sensor columns, plus `participant`, `label`, `category`, `set`), and additionally `session_id`, `segment_id`, `kind`, `reps_user`, `source`, `implicit`.
  - **Kinds:** `set` segments take label = exercise. Implicit `rest` gets label `rest`. `other` gets label `other`, a class **unseen in training**. Discarded segments are excluded, which is the reader default.
  - A `set` segment without an exercise, or with `reps_user` missing, is **flagged** (`confirmed=False`). It stays in exercise evaluation if it has an exercise, and is excluded from rep evaluation.
  - `participant` comes from `labels.json` (`participant_id`). For CV, groups are `session_id` (D-007 rule: grouped by session for watch data).
- `calibration_check(session_path) -> dict` formalizes the U1 analysis (`.claude/scratch/u1_analysis.py` is the reference):
  - static |a| on `table_flat_face_up`;
  - gyro bias and noise;
  - the six poses' signed-axis check, with off-axis angle;
  - per-axis gyro-sign identifiability via the single-axis flip test on `rot_*` steps.

  Its output feeds the calibration gate.

### 3.2 `calibrate.py`: fitting R
- **Inputs:** the MetaMotion training data (cleaned CSF, all participants) and the `watch-fit-v1` sessions.
- **Watch-side vectors:** convert with a **units-and-emulation-only** profile (the Galaxy prior profile with R = I) to get watch-frame vectors.
- **For each exercise e present in both:**
  - `m_e` = mean gravity vector in g in the MetaMotion frame, the mean over all MetaMotion windows of that exercise. Recompute it; don't use the rounded PROJECT table.
  - `w_e` = the same on the watch side, in the watch frame.
  - Include `rest` if present on both sides.
  - Weight each exercise by the number of watch sets (rest: number of rest segments).
- **Fit** by weighted Kabsch, constrained to det = +1: R* = argmin Σ wₑ ‖R wₑ − mₑ‖².
- **Report:**
  - the residual: weighted RMS of ‖R* wₑ − mₑ‖ in g, plus per exercise;
  - the angle between R* and the prior R (from `galaxy-watch-prior-v0`);
  - the unconstrained (orthogonal, det free) fit. **Flag** if it prefers det = −1, since that contradicts the left-wrist assumption (D-006) and must be escalated to the owner;
  - **conditioning:** the smallest singular value of the weighted cross-covariance. Warn if the exercise gravity directions are near-collinear, because R is then poorly determined about that axis.
- **Gyro confirmation:** per exercise, compute per-axis gyro variance on the watch side after applying R*, and on the MetaMotion side. Report the Pearson r of the 3×E variance profile. The gate is r ≥ 0.8 (PROJECT §6.4).
- **Output:** a new profile YAML `ml/workout_ml/sensors/profiles/galaxy-watch5pro-fit-v1.yaml`. It's a copy of the prior profile with:
  - the rotation R*;
  - `rotation_status: fitted`;
  - `device.model: SM-R920`;
  - a filled `calibration` block (date, fit session ids, residual_g, gyro_profile_r, angle_from_prior_deg).

  A new id is required, because `transform_id` changes. Writing it is a CLI action (`--write-profile`), never a side effect of tests.

### 3.3 `evaluate.py`: the verdict
- **Models**, each trained on **all MetaMotion data**, since the test domain is the watch and no LOPO is needed here:
  - **M1** production candidate: `basic+derived+pca+temporal` / KNN, **with** rotation augmentation (D-021);
  - **M2**: the same features with NN, with augmentation;
  - **M3**: the same as M1 **without** augmentation, to quantify what augmentation buys on real data;
  - **M4 (optional)**: the CNN from task C1, if its module exists (`workout_ml.models.cnn`); skip cleanly if not.
- **Profiles compared:** `fitted` (the R* from §3.2) and `prior`. Each row of the report is model × profile.
- **Metrics on the evaluation dataset:**
  - **set-level exercise accuracy** (majority vote over windows), with a Wilson 95% CI;
  - window macro-F1 and per-class recall over classes present;
  - **bench↔OHP confusion** as its own line item, at window and set level;
  - **rejection:** the share of `rest` + `other` segments **not logged as a lift**. A segment is rejected if its set-level vote is `rest`, **or** if the winning lift class has a vote share below τ;
  - **rep counter** (`workout_ml.reps`) on confirmed sets: MAE, exact %, ±1 % against `reps_user`, overall and per exercise.
- **τ, the vote-share threshold:**
  - default 0.6;
  - chosen on `watch-fit-v1` only: the largest τ that keeps fit-set set accuracy within 2 pp of τ = 0;
  - then **frozen** for `watch-val-v1`;
  - recorded in the report.
- **Gates (PROJECT §6.5), evaluated exactly as written there:**

| gate | rule |
|---|---|
| Calibration | from `calibration_check` on `watch-calib-v1`, plus the Kabsch residual < 0.15 g from §3.2 |
| Set accuracy | ≥ 0.90, **and** ≤ 5 pp below the MetaMotion LOPO baseline for the same model (M1: 0.986 from `current-baseline.md`; read it from the latest MLflow pipeline run, or pass it explicitly) |
| Window macro-F1 | ≥ 0.85, and every class recall ≥ 0.75 (classes with ≥ 2 sets only; others listed as "insufficient data") |
| Rejection | ≥ 0.80 |
| Reps | MAE ≤ 1.0 overall, ≤ 1.5 per exercise; ≥ 70% exact; ≥ 90% within ±1 |

  - **Provisional:** a pass whose Wilson lower bound is more than 10 pp below the threshold.
  - **Status values:** `PASS`, `PASS (provisional)`, `FAIL`, `N/A (insufficient data)`.
- **Domain diagnostics** (not gates):
  - per-feature PSI, MetaMotion vs watch, on the model's feature set; report the top 10;
  - a domain-classifier AUC: logistic regression on standardized features, grouped CV (MetaMotion grouped by participant, watch by session); report the AUC and the top contributing features;
  - per-exercise mean gravity vectors before and after R*, next to MetaMotion's.
- **Modes:**
  - `--fit watch-fit-v1 --eval watch-val-v1` is the real verdict.
  - `--in-sample watch-fit-v1` fits and evaluates on the same session. Every number is labeled **IN-SAMPLE (optimistic)**, and gate statuses are suffixed "(in-sample)". This is the Sunday-evening first look.

### 3.4 Report and tracking
- Generated `docs/reports/phase3-domain-shift.md` (or `phase3-domain-shift-insample.md` in in-sample mode). Header:
  - git SHA, clean or dirty (record git state **before** writing any artifact);
  - dataset ids;
  - profile ids and `transform_id`s;
  - model training run ids;
  - τ.
- Sections:
  - calibration;
  - the R fit (matrix, residuals, angle from prior, det check, conditioning, gyro r);
  - the gate table per model × profile;
  - bench↔OHP;
  - rejection;
  - reps;
  - domain diagnostics;
  - per-session data-quality stats (from `recording_stats`: rate, gaps, drift).
- MLflow experiment `proprio-domain-shift`: one parent run per invocation, one child per model × profile, metrics and gate statuses as tags, and the report plus fitted profile as artifacts.
- CLI: `python -m workout_ml.watch.evaluate --fit … --eval … [--in-sample …] [--write-profile] [--models M1,M2,M3,M4]`.

### 3.5 DVC
- Add `dvc.yaml` at the repo root with stages:
  - `calibration_check` (deps `data/raw/watch`; out `results/metrics/watch_calibration.json`);
  - `fit_profile` (deps `data/raw/watch`, `data/raw/MetaMotion`, `data/watch_datasets.yaml`; out `results/metrics/watch_fit.json`);
  - `evaluate` (deps the above plus the code; metrics `results/metrics/watch_eval.json`).
- Use `params.yaml` for τ default, the model list and dataset names.
- `dvc repro` must run end to end on the current data. With only U1, the fit and evaluate stages exit cleanly with "no gym sessions yet".

## 4. Tests (fast, synthetic, CI-safe; no real-data dependency)
- **Kabsch recovery:** build synthetic MetaMotion-frame per-exercise gravity vectors (the PROJECT §6.1 table) and a random known R. Map them into a "watch frame" with Rᵀ plus noise of 0.02 g, and fit. It must recover R within 1°, give a residual below 0.03 g, and report det = +1.
  - A near-collinear input must produce the conditioning warning.
  - A mirrored input (det = −1 truth) must trigger the det flag.
- **End to end on synthetic sessions:** use `simulate_android` (with a known R) on synthetic labeled sets, pack them as Raw v1 zips with `labels.json` (reuse the `:protocol` golden format or write zips directly via Python with the same schema), then run ingest → fit → evaluate. The fitted R must match within 2°, and the report must generate. This is the **in-sample mode** test.
- Ingest on `protocol/golden/session_gym_v1.zip`: labels, implicit rest, `other`, discarded handling and confirmed flags.
- Wilson CI values against known cases; PSI on identical vs shifted distributions; rejection logic (vote-share threshold); τ selection; gate status logic including provisional and N/A.
- `calibration_check` on a synthetic calibration session with known units and signs, plus a sign-flipped variant that must fail.

## 5. U2 checklist
`docs/U2_CHECKLIST.md`, written by the orchestrator, is the owner's protocol for the gym sessions.

## 6. Acceptance
1. `pytest`, `ruff check` and `ruff format --check` pass. CI-safe tests only.
2. `python -m workout_ml.watch.evaluate --in-sample <dataset>` runs on a synthetic dataset fixture, and `dvc repro` runs on the real data (U1 only), exiting cleanly.
3. `calibration_check` on the real U1 session reproduces the U1 report numbers: |a| 0.989 g, gyro bias 0.11 deg/s, six poses OK, gyro sign flips detectable.
