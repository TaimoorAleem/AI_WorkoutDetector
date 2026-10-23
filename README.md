![exercises](https://github.com/user-attachments/assets/dc7a594b-be6c-40ac-ad19-efa4189bc3a1)

# Proprio

[![ml-ci](https://github.com/TaimoorAleem/Proprio/actions/workflows/ml-ci.yml/badge.svg?branch=dev)](https://github.com/TaimoorAleem/Proprio/actions/workflows/ml-ci.yml)
[![android-ci](https://github.com/TaimoorAleem/Proprio/actions/workflows/android-ci.yml/badge.svg?branch=dev)](https://github.com/TaimoorAleem/Proprio/actions/workflows/android-ci.yml)

**Proprio recognizes barbell exercises and counts reps from a smartwatch on your wrist.** You lift with a Galaxy Watch on; the watch records motion, the phone logs your sets, a backend classifies each set, and an LLM coach explains your training.

It started as **AI Workout Detector**, a 2019 research proof of concept: one MetaMotion wrist sensor, five people, five lifts. It's being rebuilt into a real product, where every technology has a real job, every number comes from leakage-free evaluation, and every decision is written down.

> **Status (Oct 2026):** Phases 0–2 are done, Phase 3 (real-watch validation) is in progress, and the services are being built. See [Roadmap](#roadmap).

---

## Highlights so far
| | |
|---|---|
| **Honest accuracy** | The original pipeline reported **99.9%**. After fixing five data leaks, the honest number is **96–99% of sets classified correctly on people the model never saw**. The leaks were a random split over overlapping windows, a whole-set "duration" feature, filters running across set boundaries, transforms fitted on test data, and 23 duplicate files. Evaluation is leave-one-participant-out. |
| **Sensor transfer** | A canonical sensor format (CSF-1) converts any device's raw data into the training format. A simulated Galaxy Watch loses **0.0 points** of set accuracy after conversion. |
| **Rotation robustness** | The original model broke with **2°** of sensor misalignment. Training on randomly rotated copies raises that to **15°** (KNN) at no accuracy cost. |
| **Real hardware** | Wear OS recorder and Android phone app, tested on a Galaxy Watch5 Pro and a Galaxy S25 Ultra: **49.85 Hz with zero gaps** after defeating Samsung's Freecess power manager. |
| **Verified with physics** | A guided calibration confirmed the watch's units, axes and gyroscope signs. Static \|a\| = 0.989 g. Integrating the gyroscope reproduces gravity changes within 2–3°. |

## How it works
```
 Galaxy Watch (Wear OS)        Android phone              Backend (Phase 5)
 ┌─────────────────────┐ Data ┌──────────────────┐ REST  ┌──────────────────────────────┐
 │ 50 Hz acc + gyro,   │Layer │ label sets, reps │ +JWT  │ core-api (Java, Spring Boot) │
 │ raw units, sessions ├─────►│ review, export   ├──────►│ PostgreSQL · object storage  │
 │ + segments, calib.  │      │ upload, history  │       │      │ RabbitMQ              │
 └─────────────────────┘      └──────────────────┘       │      ▼                       │
                                                         │ inference-svc (Python)       │
   raw data is never converted on device                 │ raw → CSF-1 → model → reps   │
                                                         │ coach-svc (Granite, RAG)     │
                                                         └──────────────────────────────┘
```
1. The **watch** records a whole session continuously, in native units and timestamps. Sets, rest and "other" are marked segments inside it.
2. The **phone** labels segments (exercise, load, reps), lets you fix mistakes, and uploads the untouched raw file plus a separate labels file.
3. The **backend** converts the raw data with the same Python code used for training (no train/serve skew), classifies every set, counts reps, and merges the predictions with your labels.

## Repository layout
| Path | What's there |
|---|---|
| [`ml/`](ml/) | `workout_ml`: the Python package with data loading, CSF-1 sensor layer, features, models (LOPO evaluation, rotation augmentation), rep counter, watch-session reader, and MLflow tracking |
| [`apps/android/`](apps/android/) | Gradle project: `:protocol` (recording format, pure Kotlin), `:wear` (Wear OS recorder), `:mobile` (phone app) |
| [`protocol/`](protocol/) | Raw Format v1 JSON Schemas and golden sessions shared by Kotlin and Python (cross-language conformance) |
| [`contracts/`](contracts/) | Service contracts: OpenAPI for core-api, JSON Schemas for RabbitMQ events |
| `services/` | Backend services (Phase 5, in progress) |
| [`docs/`](docs/) | Project spec, progress, decision log, specs, reports, build journal |
| `data/` | DVC-tracked raw data (`MetaMotion`, `watch`), never committed directly |

## Getting started

### ML pipeline (Python 3.11)
```bash
conda create -n proprio python=3.11 && conda activate proprio
pip install -r ml/requirements-dev.lock
pip install -e ml --no-deps
dvc pull                                  # raw data (or place the MetaMotion dataset in data/raw/MetaMotion)

python -m workout_ml.pipeline             # LOPO evaluation of all models, tracked in MLflow (~20 min)
python -m workout_ml.pipeline --no-augment   # reproduce the Phase 0 baseline exactly
python -m workout_ml.reps.evaluate        # rep-counter evaluation
python -m workout_ml.sensors.android protocol/golden/session_gym_v1.zip   # inspect a watch session

mlflow ui --backend-store-uri sqlite:///mlflow.db
pytest ml/tests && ruff check ml
```
Locks are cross-platform. Regenerate them with:
```bash
uv pip compile ml/pyproject.toml --universal --python-version 3.11 -o ml/requirements.lock
```
Add `--extra dev` and `-o ml/requirements-dev.lock` for the dev lock.

### Android apps (JDK 21, Android SDK platform 36)
```bash
cd apps/android      # needs local.properties with sdk.dir=...
./gradlew :protocol:test :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug
```
- Both apps use the same `applicationId` (`dev.proprio`), which the Wearable Data Layer requires.
- Install the APKs on a paired Wear OS watch and Android phone.
- See [`apps/android/wear/README.md`](apps/android/wear/README.md) for watch specifics, including the debug-only adb test hook.

### CI
GitHub Actions runs on every push and PR to `dev` and `main`:
- **`ml-ci`:** ruff and pytest on Linux.
- **`android-ci`:** unit tests, lint and both debug APKs.

## Technology map
Each technology has a concrete job. Weak fits are called out instead of bolted on. Full map: [`docs/PROJECT.md` §5](docs/PROJECT.md).

| Area | Used for |
|---|---|
| Python, scikit-learn, **PyTorch** | Feature pipelines and classical models; a 1D-CNN with on-the-fly rotation augmentation, exported to ONNX |
| **MLflow** + **DVC** | MLflow tracks runs and models; DVC versions datasets. Every run records the data hash and git commit |
| Kotlin, Jetpack Compose, Wear OS | Watch recorder and phone app; Wearable Data Layer for transfer |
| **Java / Spring Boot**, Gradle, JUnit | core-api: auth, sessions, labels, history (contract-first OpenAPI) |
| **PostgreSQL** (+pgvector), **RabbitMQ** | Relational data and embeddings; upload → inference events with retries and a dead-letter queue |
| **Docker** on Red Hat UBI | Service images and the local stack |
| **IBM watsonx.ai** (Granite), RAG | The coach: summaries and Q&A grounded in your history and a curated knowledge base (Phase 6) |
| **IBM Cloud**, GitHub Actions | Code Engine, Cloud Object Storage, Databases; CI/CD (Phase 7) |

Deliberately **not** used: Kafka (no replay or streaming need), Cassandra (relational data at small scale), Maven (Android requires Gradle), a web client (out of scope; so HTML/JS is an acknowledged gap).

## Roadmap
| Phase | | Status |
|---|---|---|
| 0 | Honest baseline: packaging, DVC, MLflow, CI, leakage fixes | ✅ |
| 1 | CSF-1 sensor layer, fake-watch test, rotation robustness | ✅ |
| 2 | Wear OS recorder + phone labeling app, real-device fixes | ✅ |
| 3 | Calibration (done) and real gym data: axis fit, pass/fail gates on watch data | 🟡 in progress |
| 4 | PyTorch model, ONNX, model registry, rep-counter rework | 🟡 started |
| 5 | Services: core-api, inference-svc, RabbitMQ, compose, E2E tests | 🟡 started |
| 6 | LLM coach: Granite on watsonx.ai, RAG over pgvector, evaluation | ⏳ |
| 7 | CI/CD to IBM Cloud, security review, battery tuning | ⏳ |
| 8 | Personalization from user corrections | ⏳ |

## Documentation
- [`docs/PROJECT.md`](docs/PROJECT.md): the source of truth for scope, architecture, sensor spec and quality gates.
- [`docs/PROGRESS.md`](docs/PROGRESS.md): current phase and next steps.
- [`docs/DECISIONS.md`](docs/DECISIONS.md): every decision, with the reason and the alternatives rejected.
- [`docs/BUILD_JOURNAL.md`](docs/BUILD_JOURNAL.md): the full story of the build.
- [`docs/reports/`](docs/reports/): baseline, sensitivity, round-trip and calibration reports.
- [`docs/specs/`](docs/specs/): phase specifications.

## Data and references
- **Training data:** five participants performing bench press, squat, deadlift, overhead press and row with a [MetaMotion](https://mbientlab.com/metamotions/) wrist sensor (accelerometer 12.5 Hz, gyroscope 25 Hz). There are 82 unique sets after removing 23 duplicate re-exports.
  - Files are named per set, e.g. `A-bench-heavy_MetaWear_2019-01-14T14.22.49.165_C42732BE255C_Accelerometer_12.500Hz_1.4.4.csv`.
  - [Original dataset download](https://secure-res.craft.do/v2/VDcx9pyWxusPMveFX3m6KG6HXbjF2gSLkdV3zTrPX8WWrkjoh6aJinsjsSg9tEdgeMZcjDWdtZd28EhN2o2xY1Ui9TfDF5BLtGfUvYhVMqbVgdBdG7UWggpP3rR3DnS5CP9iupmM9rQQPpc9EREkeFXTSsmWXLbb98D3kdakxcembuRAC65ewTeSez8H1yd1GqFYoL76ZhHHGYrL1a4QgNa3G1pHhMLMViLV1PjeuDVxboZBTgp4S8SUsyZZDTixk5jNFwM8BZxff3Mwd8JtxQYkKkGsj8mVm75oGZaFbSGXAkLTsP/MetaMotion.zip).
- **Book:** Hoogendoorn, M. and Funk, B., [*Machine Learning for the Quantified Self*](https://link.springer.com/book/10.1007/978-3-319-66308-1), Springer, 2018. Feature-engineering code is adapted from [ML4QS](https://github.com/mhoogen/ML4QS).
