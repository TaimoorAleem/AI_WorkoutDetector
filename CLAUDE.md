# CLAUDE.md

This repo is evolving the AI Workout Detector proof of concept (barbell exercise classification and rep counting from a 2019 MetaMotion wrist IMU dataset) into **Proprio**. Proprio is a product with a Galaxy Watch (Wear OS) recorder, an Android phone app, Java + Python microservices on IBM Cloud, and a Granite-based LLM coach. The owner is building it for an IBM AI CoE entry-level SWE application: every technology must have a genuine job, and weak fits are called out honestly.

## Where things live
- **`docs/PROJECT.md`**: the **source of truth** for scope, features, architecture, sensor spec, gates and phases. Don't change scope without the owner's approval.
- **`docs/PROGRESS.md`**: current phase, next step, checklists, pending owner tasks, session log.
- **`docs/DECISIONS.md`**: append-only decision log, with the reasoning behind each decision.

## Session ritual
- **Start:** read `docs/PROGRESS.md` and continue from "Next step". Check `docs/PROJECT.md` before any scope-affecting work.
- **After each task / at session end:**
  - Tick checklist items and update "Now".
  - Add a session-log line.
  - Log any new decision in `DECISIONS.md`.
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
  - `workout_ml/data/preprocessor.py`: `DataLoader`, `OutlierRemover`.
  - `workout_ml/features/`: `FeatureEngineer`, filters, temporal and FFT features.
  - `workout_ml/models/`: `ModelTrainer`, `LearningAlgorithms`.
  - `workout_ml/pipeline.py`: end-to-end pipeline (`main()`).
  - `workout_ml/reps/`: rep counting (`counter.py`: `count_set`, `count_peaks`; `evaluate.py`: per-set evaluation and summary).
  - `tests/`: pytest suite.
- Raw data lives in `data/raw/MetaMotion/`, tracked by DVC (`data/raw/MetaMotion.dvc`, local remote `E:/dvc-store/proprio`). Run `dvc pull` to fetch.
- Environment: conda env `proprio` (Python 3.11). Setup: `conda activate proprio`, `pip install -r ml/requirements-dev.lock`, `pip install -e ml --no-deps`.
- Commands: `python -m workout_ml.pipeline`, `python -m workout_ml.reps.evaluate`, `pytest ml/tests`, `ruff check ml`.
- Platform: Windows 11. Both the Bash tool (Git Bash) and PowerShell are available.
