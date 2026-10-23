# CLAUDE.md

This repo is evolving the AI Workout Detector proof of concept (barbell exercise classification and rep counting from a 2019 MetaMotion wrist IMU dataset) into **Proprio**. Proprio is a product with a Galaxy Watch (Wear OS) recorder, an Android phone app, Java + Python microservices on IBM Cloud, and a Granite-based LLM coach. The owner is building it for an IBM AI CoE entry-level SWE application: every technology must have a genuine job, and weak fits are called out honestly.

## Where things live
- **`docs/PROJECT.md`**: the **source of truth** for scope, features, architecture, sensor spec, gates and phases. Don't change scope without the owner's approval.
- **`docs/PROGRESS.md`**: current phase, next step, checklists, pending owner tasks, session log.
- **`docs/DECISIONS.md`**: append-only decision log, with the reasoning behind each decision.
- **`docs/INTERVIEW_NOTES.md`**: the owner's interview talking points (STAR stories, decisions, numbers, likely questions). Keep it current. **Private: gitignored and purged from history (D-026). Never commit or push it, never `git add -f` it, and copy it aside before branch switches or merges.**
- **`docs/BUILD_JOURNAL.md`**: the full chronological story of building Proprio, organized as parts of a future blog series (what was done, why, what was rejected, results, lessons). Keep it current.

## Session ritual
- **Start:** read `docs/PROGRESS.md` and continue from "Next step". Check `docs/PROJECT.md` before any scope-affecting work.
- **After each task / at session end:**
  - Tick checklist items and update "Now".
  - Add a session-log line.
  - Log any new decision in `DECISIONS.md`.
  - Update `INTERVIEW_NOTES.md` when there's a new story, decision, number or lesson worth telling (reproducible numbers only; include the why and the alternatives).
  - Update `BUILD_JOURNAL.md` with every meaningful step, not just highlights: add to the current part, or start the next part when a phase begins.
  - Commit the doc updates with the work.

## Git rules
- All work goes on `feature/<name>` (or `docs/<name>`) branched from **`dev`**, and merges back into `dev`.
- **Never merge, commit or push to `main` unless the owner explicitly says so.**
- Commit only when asked or when finishing an agreed task. **No Claude co-author trailers and no "Generated with Claude Code" lines.** The owner is the sole author.

## Execution model
- Opus orchestrates: analysis, architecture, task breakdown, review and integration. **The domain-gap analysis and the evaluation criteria stay with the orchestrator.**
- Code and mechanical work go to Sonnet subagents (`model: "sonnet"`). Each gets a self-contained task: goal, files, constraints, acceptance criteria. Always review their output before moving on.
- Use Explore subagents for wide read-only sweeps.

## Fixed constraints (see DECISIONS.md)
- Galaxy Watch, **left wrist only** (the dataset is assumed left-wrist too). No web client.
- **CSF-1** (`PROJECT.md` §6.3) is the only path into models. Raw sensor data is immutable and always kept. Conversion happens server-side in shared Python.
- Leakage-safe evaluation only: LOPO for MetaMotion, grouped by session for watch data. Never a random split over windows.
- Model changes must pass the gates in `PROJECT.md` §6.5.
- No pickles, datasets or generated plots in git. Data goes in DVC; runs, metrics, plots and models go in MLflow.

## Key sensor facts (verified from raw files; don't re-derive)
- MetaMotion: accelerometer in **g**, gravity included; gyroscope in **deg/s**.
- Rates: acc 12.5 Hz and gyro 25 Hz, **zero jitter**. The pipeline resamples to 5 Hz (200 ms mean).
- +y points distally along the forearm. x and z are unknown.
- Android: accelerometer in m/s², gyroscope in rad/s, irregular timing.
- Full table and per-exercise gravity vectors: `PROJECT.md` §6.1–6.2.

## Current layout and commands
- `ml/`: the installable `workout_ml` package (`ml/pyproject.toml`, locks in `ml/requirements.lock` and `ml/requirements-dev.lock`).
  - `workout_ml/config.py`: paths and pipeline config (`ensure_dirs()` creates output dirs).
  - `workout_ml/sensors/`: CSF-1 layer (`types.py` RawStream/SensorSet, `transforms.py`, `resample.py`, `profiles.py` + `profiles/*.yaml`, `metamotion.py` reader, `convert.py` `to_csf`). Public API: `to_csf`, `from_canonical`, `load_profile`, `read_metamotion`. Spec: `docs/sensor_spec.md`.
  - `workout_ml/sensors/android.py`: Raw Format v1 reader (`read_session`, `Session.segments`, `recording_stats`; CLI `python -m workout_ml.sensors.android <zip>`). Validates against `protocol/schema/*.json` (`ml/setup.py` copies them into the wheel).
  - `workout_ml/data/preprocessor.py`: `DataLoader` (file discovery, set IDs from file stems, duplicates; `load_sets()` -> SensorSets, `load_data()` -> reader -> `to_csf` per set -> one DataFrame), `OutlierRemover.clean_outliers` (per-set mark NaN + interpolate).
  - `workout_ml/features/`: `FeatureEngineer` (per-set, no `duration`, no PCA), filters, temporal and FFT features.
  - `workout_ml/models/lopo.py`: in-fold `PCAFeatures`, feature sets, model pipelines, LOPO evaluation and set-level majority vote. `models/experiment.py`: runs every feature set x model and tracks it in MLflow.
  - `workout_ml/tracking.py`, `workout_ml/reporting.py`: MLflow setup/provenance and the generated report (`docs/reports/current-baseline.md`; `phase0-baseline.md` is frozen).
  - `workout_ml/pipeline.py`: end-to-end pipeline (`main()`); flags `--tune`, `--forward-selection`, `--no-augment`, `--models`. Trains with rotation augmentation by default (D-018, `EVAL_CONFIG["augmentation"]`, shared code in `workout_ml/augment.py`); `--no-augment` reproduces the Phase 0 baseline. MLflow store defaults to `sqlite:///<repo>/mlflow.db` (override with `MLFLOW_TRACKING_URI`); view with `mlflow ui --backend-store-uri sqlite:///mlflow.db`.
  - `workout_ml/reps/`: rep counting (`counter.py`: `count_set`, `count_peaks`; `evaluate.py`: per-set evaluation and summary).
  - `workout_ml/watch/`: Phase 3 watch harness (spec `docs/specs/phase3-domain-shift.md`). `ingest.py` (`load_watch_sessions`, `calibration_check`), `calibrate.py` (weighted-Kabsch `fit_rotation`, `write_fitted_profile`), `evaluate.py` (M1-M4 x fitted/prior verdict, gates, report, MLflow experiment `proprio-domain-shift`), `metrics.py`, `diagnostics.py`; datasets in `data/watch_datasets.yaml`. CLI: `python -m workout_ml.watch.evaluate --fit watch-fit-v1 --eval watch-val-v1` or `--in-sample watch-fit-v1`; `dvc repro` runs the `calibration_check`, `fit_profile` and `evaluate` stages (`dvc.yaml`, `params.yaml`).
  - `tests/`: pytest suite.
- `apps/android/`: Gradle project (`:protocol` pure Kotlin/JVM, `:wear`, `:mobile`; version catalog `gradle/libs.versions.toml`). Needs `JAVA_HOME` = Android Studio's JBR 21 and `apps/android/local.properties` (`sdk.dir`, gitignored). Run from `apps/android`: `./gradlew :protocol:test :wear:assembleDebug :mobile:assembleDebug`; phone tests `:mobile:testDebugUnitTest` (JVM + Robolectric Compose), smoke test `:mobile:connectedDebugAndroidTest`; regenerate goldens with `./gradlew :protocol:generateGolden` (never part of `test`).
- `protocol/`: `schema/` (JSON Schemas for `meta.json`/`labels.json`) and `golden/` (committed cross-language conformance sessions + `expected.json`).
- Raw data lives in `data/raw/MetaMotion/`, tracked by DVC (`data/raw/MetaMotion.dvc`, local remote `E:/dvc-store/proprio`). Run `dvc pull` to fetch.
- Environment: conda env `proprio` (Python 3.11). Setup: `conda activate proprio`, `pip install -r ml/requirements-dev.lock`, `pip install -e ml --no-deps`.
- Commands: `python -m workout_ml.pipeline`, `python -m workout_ml.reps.evaluate`, `pytest ml/tests`, `ruff check ml`.
- Deep-learning extra (`dl` in `ml/pyproject.toml`: torch, onnx, onnxruntime; kept out of the base/dev locks so CI stays light): `pip install -r ml/requirements-dl.lock --extra-index-url https://download.pytorch.org/whl/cpu` (CPU torch wheels), then `pip install -e ml --no-deps`. Regenerate with `python -m uv pip compile ml/pyproject.toml --universal --python-version 3.11 --extra dev --extra dl --torch-backend cpu -c ml/requirements-dev.lock -o ml/requirements-dl.lock` (the `-c` keeps every pin of the dev lock). Tests needing torch use `pytest.importorskip("torch")`. CNN (`workout_ml/models/cnn.py`): `python -m workout_ml.models.cnn [--no-augment | --both] [--epochs N] [--quick]`; MLflow experiment `proprio-cnn`; report `docs/reports/phase4-cnn-baseline.md` (written by `--both`).
- Lock regeneration (cross-platform, via uv): `uv pip compile ml/pyproject.toml --universal --python-version 3.11 -o ml/requirements.lock`; add `--extra dev` for `ml/requirements-dev.lock`. CI (`.github/workflows/ml-ci.yml`) runs ruff + pytest on push/PR to `dev` and `main`.
- Android CI (`.github/workflows/android-ci.yml`, push/PR to `dev`/`main` on `apps/android/**`, `protocol/**`): runs `./gradlew --no-daemon :protocol:test :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug :wear:lintDebug :mobile:lintDebug` from `apps/android` on ubuntu (JDK 21, SDK platform 36). `:protocol:test` includes `GoldenTest`, which fails if `protocol/golden` is stale. Uploads test/lint reports and debug APKs. `ml-ci` also triggers on `protocol/**`.
- `apps/android/wear/`: Wear OS recorder (`SensorRecorder`, `RecordingController`, `RecordingService`, calibration, transfer outbox, anti-freeze `Strategy` + debug-only adb receiver `DebugCommandReceiver` in `src/debug`, D-023); see `apps/android/wear/README.md`. Build/test from `apps/android` with `JAVA_HOME` set to Android Studio's JBR: `./gradlew --no-daemon :wear:testDebugUnitTest :wear:assembleDebug :wear:assembleDebugAndroidTest :wear:lintDebug`. Mobile+wear gate: `./gradlew --no-daemon :protocol:test :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug :wear:assembleRelease`.
- `services/core-api/`: Java 21 / Spring Boot 3.5 / Gradle Kotlin DSL (own wrapper), contract-first from `contracts/openapi/core-api.yaml`. Needs `JAVA_HOME` = Android Studio's JBR 21; Testcontainers tests need Docker. From `services/core-api`: `./gradlew unitTest` (no Docker), `./gradlew test`, `./gradlew build` (includes Spotless); image: `docker build -f services/core-api/Dockerfile -t proprio/core-api .` from the repo root. See `services/core-api/README.md`.
- Platform: Windows 11. Both the Bash tool (Git Bash) and PowerShell are available.
