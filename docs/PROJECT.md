# Proprio — Project Specification (source of truth)

> **Status:** v1.1 · last updated 2026-10-22 (added MLflow, D-015)
> This document is the single source of truth for Proprio's scope, features, architecture and technical specs.
> **Rules:** scope changes require the owner's approval and must be logged in [`DECISIONS.md`](DECISIONS.md). Task status lives in [`PROGRESS.md`](PROGRESS.md), not here.

## 1. Purpose
Evolve the AI Workout Detector proof of concept into **Proprio**, a working product that demonstrates genuine use of the technologies in an IBM Entry-Level SW Developer (AI Center of Excellence) job description. Every technology must have a real job in the system. Weak fits are stated as such, not bolted on.

## 2. Product vision and scope
Proprio is a gym logger:
- The user starts a set on a **Samsung Galaxy Watch (Wear OS), worn on the left wrist**, and lifts.
- The system identifies the exercise (bench, squat, deadlift, OHP, row, rest, other) and counts reps.
- The **Android phone app** shows the logged workout, history and progress. One tap corrects a label. A recording-review screen plots a set's signals.
- An **LLM coach** writes post-workout summaries and answers questions, grounded in the user's own history (tool calls) and a curated knowledge base (RAG).
- User corrections are a product feature: they drive per-user personalization and, with opt-in consent, global retraining.

### In scope
| Feature | Status |
|---|---|
| Leakage-free ML baseline (LOPO CV, per-set filtering) | planned |
| CSF-1 sensor normalization layer (MetaMotion ↔ Galaxy Watch) | planned |
| Wear OS recorder with calibration mode | planned |
| Phone app: session/labeling, history, corrections, recording review, coach chat | planned |
| Domain-shift evaluation with pass/fail gates | planned |
| PyTorch 1D-CNN classifier + tuned rep counter, ONNX serving | planned |
| "Other" class / confidence rejection | planned |
| core-api (Java/Spring Boot), inference-svc and coach-svc (Python/FastAPI) | planned |
| PostgreSQL (+pgvector), RabbitMQ, IBM COS | planned |
| LLM coach: Granite on watsonx.ai, RAG + tool calls | planned |
| CI/CD to IBM Cloud Code Engine, model regression gate | planned |
| MLflow experiment tracking, model registry, LLM eval runs | planned |
| Per-user personalization from corrections | planned |
| Stretch: live on-watch rep counter (Kotlin), Helm/K8s, Granite LoRA | stretch |

### Out of scope (decided)
- Web client. As a result, HTML/JS/Ajax is a **known gap** in tech coverage.
- Right-wrist wear. Users always wear the watch on the left wrist.
- Streaming inference in the MVP. Inference runs per set.
- Kafka, Cassandra, Maven, Selenium/Puppeteer (see the tech map for reasons).
- The LLM doing signal classification.

## 3. Baseline codebase (as of 2026-10-21)
- **Pipeline:** `run_pipeline.py` runs these steps:
  - `src/data/preprocessor.py`: `DataLoader` handles load, merge and resampling to 200 ms. `OutlierRemover` supports iqr, chauvenet and lof.
  - `src/features/engineer_features.py`: `FeatureEngineer` interpolates, applies a Butterworth low-pass at 1.3 Hz, then adds rolling stats (windows 5 and 10), FFT features (window 10), PCA(3), magnitudes and `duration`.
  - `src/models/model_trainer.py`: `ModelTrainer` runs forward selection and compares 5 models × 5 feature sets.
- **Rep counting:** the exploratory script `src/features/count_repetitions.py` (per-exercise low-pass + `argrelextrema`).
- **Config:** `src/config.py`.

**Known defects to fix in Phase 0:**
1. **Leakage:**
   - The train/test split is a random 80/20 over overlapping rows.
   - PCA and outlier stats are fitted on all data.
   - `duration` is a whole-set feature.
   - The low-pass, rolling and FFT steps run across set and participant boundaries.
2. Global Chauvenet drops rows, which breaks the uniform-sampling assumption the filters rely on. Outliers should be marked NaN and interpolated per set.
3. The rep counter is a script, its ground truth is assumed (heavy = 5, medium = 10), and it has no tests.
4. No serialized model, no tests, no CI. The requirements are incomplete. The environment is Python 3.8.
5. Features depend on absolute axis values, which makes them sensitive to orientation.

## 4. Architecture
```
 Galaxy Watch (Wear OS, Kotlin)            Android phone (Kotlin, Compose)
 ┌──────────────────────────┐ Data Layer  ┌───────────────────────────────┐
 │ SensorManager acc+gyro   │ChannelClient│ Session/label UI, history,     │
 │ 50 Hz raw, native units  ├────────────►│ review, coach chat, corrections│
 │ calibration mode, buffer │  (raw file) │ uploads raw set (presigned URL)│
 └──────────────────────────┘             └──────┬────────────────────────┘
                                   HTTPS/REST+JWT │            ▲ results (poll/FCM)
                                                  ▼            │
 ┌───────────────────────── core-api (Java 21, Spring Boot, Gradle) ───────────┐
 │ auth (JWT), users, workouts, sets, consent, presigned COS URLs, OpenAPI     │
 └───┬───────────────────────┬────────────────────────────┬──────────────────┘
     │ JDBC/Flyway           │ AMQP publish set.uploaded  │ REST /coach/*
     ▼                       ▼                            ▼
 PostgreSQL 16          RabbitMQ ──► inference-svc (Python, FastAPI worker)
 (+pgvector)              ▲         canonicalize (CSF-1) → classify (ONNX)
 IBM Cloud Databases      │         → count reps → publish set.analyzed
     ▲                    └──────── personalization jobs
     │                              IBM Cloud Object Storage (raw sets, DVC remote)
     └──── coach-svc (Python, FastAPI): RAG over pgvector + history tools
                 └─► watsonx.ai (Granite Instruct + granite-embedding); Ollama Granite locally/CI
 ML lifecycle: MLflow tracking server + model registry (Code Engine; backend DB on the same Postgres
               instance, artifacts in COS). inference-svc loads models:/proprio-classifier@production
 Deploy: UBI-based images → IBM Container Registry → IBM Cloud Code Engine
         Postgres: IBM Cloud Databases · RabbitMQ: IBM Cloud Messages for RabbitMQ
         Local dev: docker-compose (MinIO stands in for COS)
```
**Data flow:**
1. The watch records raw samples (native units, Android frame, `event.timestamp` ns) and sends the file to the phone.
2. The phone gets an upload URL from core-api and uploads the file to COS.
3. core-api publishes a `set.uploaded` event.
4. The inference worker converts the raw file to CSF-1, classifies it, counts reps and publishes `set.analyzed`.
5. core-api stores the result, and the phone displays it.

**Raw data is always kept**, so calibration can be improved later and old data reprocessed.

**Target repo layout:**
- `ml/` (the `workout_ml` package)
- `services/{core-api,inference,coach}/`
- `apps/android/` (`:wear`, `:mobile`, `:protocol`)
- `deploy/`, `docs/`
- `dvc.yaml`, `.github/workflows/`

## 5. Technology map
| Technology | Where | Why it fits / honest notes | Priority |
|---|---|---|---|
| Python server | inference-svc, coach-svc (FastAPI) | The ML stack is Python. The same code trains and serves, so there's no skew | must |
| Java + Gradle + JUnit 5 | core-api; Android | Domain/API service. Gradle is forced by Android, so one build tool covers everything. No Maven | must |
| PostgreSQL + pgvector | users, workouts, sets, consent, embeddings | Relational data plus a vector store in one DB. Cassandra would be a poor fit | must |
| HTTP/REST/JSON | OpenAPI REST between phone and core-api, and between services | The real client/server boundary | must |
| HTML/JavaScript/Ajax | **Not covered**: web client dropped (Swagger UI is generated, not authored) | Known gap | gap |
| PyTorch | 1D-CNN classifier, fine-tuning on watch data, ONNX export | Learned features, and fine-tuning is the natural domain-adaptation tool | must |
| Hugging Face | granite-embedding for local/CI embeddings; Granite weights via the Hub | Moderate fit | should |
| Prompt engineering + RAG | coach-svc | Vector RAG covers the KB and free-text notes. Numeric history goes through tool calls, not vector search | must |
| Git + CI/CD | GitHub Actions: tests, model regression gate, builds, deploy | Central to the project | must |
| IBM watsonx.ai | Granite Instruct + embeddings | LLM backend | should |
| IBM Cloud | COS, Container Registry, Code Engine, Databases for PostgreSQL, Messages for RabbitMQ | Hosting | should |
| Granite deploy / fine-tune | Deploy via watsonx: yes. LoRA: weak fit | The sensor model is the genuine fine-tuning story | should / stretch |
| Docker | Every service; compose for dev and CI | Needed | must |
| Kubernetes | Code Engine (Knative-based managed); Helm on `kind` in CI | A full cluster is overkill for this scale | stretch |
| Microservices | core-api / inference / coach | Different runtimes, scaling and failure isolation justify the split. Don't split further | should |
| RabbitMQ | set.uploaded → inference; set.analyzed; personalization jobs | Decoupling, retries, DLQ. Kafka is overkill | should |
| Linux (RHEL UBI) | `ubi9/openjdk-21-runtime`, `ubi9/python-311`, non-root | Natural fit | should |
| Automated testing | JUnit 5 + Testcontainers; Android JUnit/Robolectric + Espresso/Compose UI; REST Assured E2E; pytest + hypothesis | No web UI, so Selenium/Puppeteer have nothing real to drive | must |
| DVC | Versioned datasets, COS remote | Reproducible retraining. DVC versions **data**; MLflow tracks **runs and models** | must |
| MLflow | Experiment tracking (every training run: params, LOPO + watch-val metrics, confusion matrices, DVC data hash, git SHA); model registry with aliases `candidate` / `production` used by the CI gate and inference-svc; `mlflow.evaluate` + prompt versions for the coach golden set | Strong fit: replaces ad-hoc CSV/PNG outputs and the JSON model card. Tracing coach tool calls is optional | must |
| Security | §9 | | must |

## 6. Sensor calibration and validation spec

### 6.1 Measured training-data facts (from `data/raw/MetaMotion`, 94 acc + 93 gyro files)
- **Device:** a single MetaWear device (MAC `C42732BE255C`), firmware 1.4.4. Bosch BMI160 IMU.
- **Accelerometer:** units g, gravity **included** (median |a| = 1.007 g, p5 0.66, p95 1.31). Max |axis| is 1.82 g. The range setting isn't recorded, so ±2 g is possible.
- **Gyroscope:** units deg/s. p99 |ω| = 92 deg/s, max 393 deg/s.
- **Timing:** acc exactly 80 ms (12.5 Hz), gyro exactly 40 ms (25 Hz). **Zero jitter** (device-set timestamps). A few in-file gaps of 2.0–3.5 s.
- **Axes:** +y points **distally along the forearm**. Mean acc_y is +0.94 g for bench (hand above wrist) and −1.02 g for deadlift and row. x and z are undetermined.
- **Wrist:** not documented by the source. **Assumed left (decision).**
- **Per-exercise mean gravity vector (x, y, z) in g:**
  - bench (−0.11, 0.94, −0.17)
  - dead (0.04, −1.02, −0.02)
  - ohp (−0.24, 0.92, −0.12)
  - rest (0.52, −0.50, 0.30)
  - row (0.05, −1.02, 0.04)
  - squat (0.17, 0.66, 0.66)
- **Sets:** 5 participants with uneven coverage (B and D have 9 sets each). Sets last 8–40 s (median 19 s). Rep ground truth is inferred from category, not annotated.

### 6.2 Domain gap: MetaMotion vs Galaxy Watch
| Property | MetaMotion | Galaxy Watch (verify on device) | Conversion |
|---|---|---|---|
| Accel units | g | m/s² (`TYPE_ACCELEROMETER`) | ÷ 9.80665. Check static \|a\| = 1.00 ± 0.03 g |
| Gravity | included | included in `TYPE_ACCELEROMETER` | use raw accel, not linear acceleration |
| Gyro units | deg/s | rad/s (`TYPE_GYROSCOPE`, bias-compensated) | × 180/π. Rest bias < 1 deg/s |
| Rate/jitter | 12.5 / 25 Hz, zero jitter | requested 50 Hz. Actual rate, batching and jitter unknown. Separate async streams | uniform-grid interpolation, then **emulate** MetaMotion (anti-alias → 12.5/25 Hz → 200 ms bin mean) |
| Range | max 1.82 g observed | ±8/16 g (`Sensor.maximumRange`) | log the fraction of samples above 1.9 g. Clip only if training was ±2 g |
| Axes | +y distal; x/z unknown | Android: +x screen-right, +y screen-top, +z out of screen. Left wrist with buttons toward the hand gives +x distal | prior: MetaMotion y ≈ watch +x. Full R fitted by Kabsch, det = +1 (both frames right-handed) |
| Wrist | assumed left | always left | no mirror. A fit that prefers det = −1 contradicts the assumption, so escalate to the owner |

### 6.3 CSF-1 (Canonical Sensor Frame v1)
- **Columns:** `acc_x, acc_y, acc_z` in g with gravity included; `gyr_x, gyr_y, gyr_z` in deg/s. MetaMotion axis frame. 200 ms grid, bin-mean semantics after source-rate emulation.
- **Metadata:** `csf_version`, `device_profile`, `transform_id`.
- **Lives in** `ml/workout_ml/sensors/` (`metamotion.py`, `android.py`, `transforms.py`, `resample.py`, `profiles/*.yaml`). Training and inference-svc share this code. The watch and phone send **raw data only**.
- **Device profile YAML:** unit scales, rotation R, emulation rates, clip range, calibration date and residual. Versioned in git.
- **Required tests:**
  - Unit round-trips.
  - R orthonormal with det = +1 (the loader rejects det = −1).
  - Rotation preserves norms.
  - acc/gyro rotation consistency.
  - A jittered sine keeps its frequency within 2%.
  - Async stream merge.
  - MetaMotion through CSF matches the legacy `DataLoader` output.
  - **Synthetic fake-watch round-trip:** MetaMotion → inverse transform to Android raw (m/s², rad/s, 50 Hz, ±3 ms jitter, noise) → CSF. Classifier metrics must match within 1 pp.

### 6.4 Recording app and protocol
**Watch:**
- Foreground service (type `health`; on Android 14+ this needs `FOREGROUND_SERVICE_HEALTH` + `HIGH_SAMPLING_RATE_SENSORS`/`ACTIVITY_RECOGNITION`; verify).
- acc and gyro at 20 000 µs, with `event.timestamp` plus a wall-clock anchor.
- Sensor metadata: name, vendor, resolution, maximumRange, minDelay, fifo.
- Haptic cue at start.
- **Calibration mode:** 6 static poses × 10 s, plus 3 slow 90° rotations per axis.

**Phone:**
- Pick exercise, load (kg plus heavy/medium) and RPE. Left-wrist reminder on first run.
- After stop, a rep-count picker.
- The gap between sets is auto-labeled rest. An "other" button.
- Recording-review plots.
- Export as Raw Format v1 (zip of CSVs plus `meta.json`).

**Protocol (owner tasks):**
- **U1, calibration at home (~20 min, left wrist):** the full calibration sequence, plus 2 min flat on a table.
- **U2, two gym sessions, at least 2 days apart, left wrist:** each session is 5 exercises × (2 heavy × 5 reps + 2 medium × 10 reps) = 20 sets, with 60–120 s rest and 5 × 30 s of "other". Count reps aloud. Phone video is recommended. Total: 40 sets, 30+ rest segments, 10 "other" segments.
- **U3, two friends:** 10 sets each (5 exercises × 1 heavy + 1 medium), left wrist, with written consent.

**Fitting R:**
- Kabsch on per-exercise mean gravity vectors (§6.1), with det = +1, initialized from the prior.
- Confirm with gyro: per-exercise per-axis variance profiles must correlate (r ≥ 0.8).
- A residual of 0.15 g or more, or an unconstrained fit that prefers det = −1, means the left-wrist assumption is suspect, so escalate.
- Fit on session 1, evaluate on session 2.

### 6.5 Domain-shift evaluation and gates
- **Reference:** MetaMotion LOPO CV (leakage fixed), at window level and set level.
- **Watch evaluation:** on `watch-val-v1`, session 2 held out:
  - Diagnostics: per-feature PSI and domain-classifier AUC.
  - Window macro-F1 and set accuracy (majority vote), with Wilson 95% CIs.
  - Rep MAE, exact %, and ±1 %.

| Gate | Pass |
|---|---|
| Calibration | static \|a\| 1.00 ± 0.03 g; rest gyro bias < 1 deg/s; Kabsch residual < 0.15 g |
| Set-level exercise accuracy | ≥ 90% **and** ≤ 5 pp below MetaMotion LOPO |
| Window macro-F1 | ≥ 0.85; every class recall ≥ 0.75 |
| Other/rest rejection | ≥ 80% not logged as a lift |
| Rep count | MAE ≤ 1.0 overall, ≤ 1.5 per exercise; ≥ 70% exact; ≥ 90% within ±1 |
| Domain-classifier AUC | diagnostic; above 0.8 triggers feature investigation |

- With about 40 sets, the CIs are roughly ±9 pp. A pass whose CI lower bound is more than 10 pp below the threshold is **provisional**.
- If the LOPO baseline itself falls below a threshold, that gate becomes relative (≤ 5 pp drop).

### 6.6 Remediation decision tree (apply in order)
1. **Calibration gate fails:** fix the conversion or profile. Never retrain to cover a units or axes bug.
2. **High AUC from absolute-axis features:** switch to orientation-robust features (acc_r, gyr_r, gravity-projected vertical, horizontal magnitude) and retrain on MetaMotion only.
3. **Gap persists and ≥ 30 watch sets exist:** mix watch data into training, or fine-tune the CNN on session 1. Adopt it only if held-out watch improves by ≥ 5 pp **and** MetaMotion LOPO drops by ≤ 2 pp.
4. **Between-user variance > 10 pp (U3):** per-user head on ≥ 3 corrected sets per exercise. Activate it only if the per-user held-out check improves.
5. **Still failing, or any class recall < 0.6:** recollect at scale. That's the owner's decision.

**Rep counter:** re-tune the cutoff and signal column per exercise on session 1 first. If MAE is still above 1.5, use a learned or phase-based counter.

### 6.7 Pipeline and CI integration
- **DVC stages:** `ingest_metamotion` → `ingest_watch` → `canonicalize` → `features` → `train` → `evaluate`.
- **Datasets:** `metamotion-v1` (immutable), `watch-calib-v1`, `watch-val-v1` (frozen, never trained on), `watch-train-vN`. Remote: COS.
- **Experiment tracking:** every pipeline run logs to MLflow (experiment `proprio-classifier`): config params, CV scheme, metrics per gate, confusion matrix and feature-selection plots, `dvc.lock` data hashes, git SHA, device profile id.
- **Model artifacts:** registered in the MLflow model registry as `proprio-classifier` (ONNX flavor plus a model card with dataset hashes, profile id, metrics and thresholds). Aliases: `candidate` (latest passing CI) and `production` (promoted by the owner or a release tag). Rollback = move the alias.
- **CI `model-regression` job:** runs on changes to `ml/**` or `profiles/**`. Fails if any gate fails, or the candidate regresses by more than 2 pp vs the model behind the `production` alias. On pass, it registers the run's model with alias `candidate`.
- **Reproducibility:** fixed seeds, a locked environment, and `dvc repro` reproduces the model hash-for-hash.

## 7. Phases and acceptance criteria
Legend: [O] orchestrator (Opus) · [S] Sonnet subagent · [U] owner (physical) · ‖ parallel. Task checklists live in `PROGRESS.md`.

| Phase | Weeks | Goal | Acceptance |
|---|---|---|---|
| 0 Hygiene + honest baseline | 1 | `ml/workout_ml` package, Python 3.11, locked requirements, DVC, rep counter as a library, leakage fixes, LOPO, CI (ruff + pytest), MLflow tracking (local server) | CI green; pipeline logs a LOPO baseline run to MLflow (window F1, set accuracy, rep MAE) |
| 1 CSF-1 layer | 2–3 | `sensors/`, tests, fake-watch generator, pipeline on CSF | §6.3 tests pass; fake-watch within 1 pp |
| 2 Wear OS + phone recorder | 3–5 | `:protocol`, `:wear`, `:mobile`, Python Raw v1 reader | a recorded set opens in Python; rate and jitter in metadata; calibration mode works |
| 3 Calibration + domain shift | 5–6 | U1/U2/U3, eval harness, profile fit, `watch-val-v1` frozen | domain-shift report with all gates evaluated |
| 4 Remediation + PyTorch | 6–8 | decision tree, robust features, 1D-CNN → ONNX, rep re-tune, CI gate, MLflow model registry + aliases | model passes gates (or provisional); `dvc repro` reproducible |
| 5 Services MVP | 8–10 | OpenAPI contract, core-api, inference-svc (loads `@production` from the MLflow registry), compose (+ MLflow server), phone integration, REST Assured E2E | watch → result on phone in < 3 s locally; DLQ/retry demonstrated |
| 6 LLM coach + RAG | 10–11 | coach-svc, pgvector, Granite (watsonx; Ollama fallback), golden-set eval logged with `mlflow.evaluate` and versioned prompts, phone chat | answers cite sources; ≥ 90% golden pass; injection prompts refused |
| 7 CI/CD + IBM Cloud + security | 11–12 | full workflows, Trivy, CodeQL, ICR, Code Engine, IBM Databases/RabbitMQ provisioning, MLflow server (Postgres backend, COS artifacts, auth; reachable only by CI and services) | tag → deployed; phone works against cloud; all gates green |
| 8 Personalization + stretch | 12+ | per-user heads via RabbitMQ jobs; stretch items | per-user check improves before activation |

## 8. Testing and CI/CD
- **Python:** pytest + hypothesis, golden files (Raw v1 ↔ CSF), model-regression gate (against the MLflow `production` alias), RAG golden set (MLflow evaluation runs).
- **Java:** JUnit 5, Spring slice tests, Testcontainers (Postgres, RabbitMQ).
- **Android:** JUnit + Robolectric, Espresso/Compose UI tests, one instrumented smoke test.
- **API E2E:** REST Assured + JUnit against docker-compose.
- **CI:** GitHub Actions, path-filtered (ml, services, android).
- **CD:** tag → build → Trivy scan → push to ICR → deploy to Code Engine → smoke test.

## 9. Security
- **API and transport:**
  - JWT (short-lived access token plus refresh), Argon2/BCrypt password hashing, TLS only.
  - The watch never holds credentials; the phone stores tokens in Android Keystore.
  - Presigned, size-limited, content-type-checked uploads. Server-side schema validation of Raw v1.
- **Authorization:** row-level checks by `user_id` on every query. Coach tools are scoped to the caller.
- **Secrets and supply chain:**
  - Secrets live in GitHub Secrets and IBM Secrets Manager or Code Engine secrets. None in the repo; secret scanning on.
  - Non-root UBI images, Trivy, Dependabot, CodeQL, SBOM.
- **Standards:** OWASP ASVS L1, and OWASP LLM Top 10 (prompt injection via notes, output handling, no medical diagnoses).
- **Health data:** per-set training-consent flag, plus data export and delete endpoints. U3 participants give written consent.

## 10. Risks
- The source's x/z axes are unknown and the left-wrist assumption is unverified. Mitigated by the Kabsch residual and the det check.
- Galaxy Watch sensor batching or throttling. Measured in Phase 2.
- A small validation set means wide CIs. Results may be marked provisional.
- Training rep ground truth is assumed. U2 video provides true counts.
- The training data has no "other" class.
- IBM Cloud Databases and Messages for RabbitMQ have no free tier. Use trial credits during the demo window and tear down afterwards; compose for daily work.
- HTML/JS/Ajax is uncovered.

## 11. Demo and resume outputs
- **P0:** "Found and fixed data leakage in an IMU activity classifier; re-baselined with leave-one-subject-out CV."
- **P1–P3:** "Designed a sensor normalization layer (units, Kabsch-fitted axis rotation, source-rate emulation) to transfer models from a research IMU to Galaxy Watch; quantified domain shift against defined gates."
- **P2:** "Built Wear OS and Android apps in Kotlin/Compose for 50 Hz IMU capture and in-app labeling."
- **P4:** "Trained and fine-tuned a PyTorch 1D-CNN, exported it to ONNX, and added a CI regression gate on a DVC-versioned watch validation set."
- **P5:** "Microservices (Spring Boot + FastAPI) with PostgreSQL and RabbitMQ on Docker and Red Hat UBI."
- **P6:** "RAG coach using IBM Granite on watsonx.ai with pgvector and tool calling, evaluated against a golden set."
- **P7:** "CI/CD on GitHub Actions to IBM Cloud (Code Engine, Databases for PostgreSQL, Messages for RabbitMQ) with security scanning and automated E2E tests."
- **P8:** "Personalization loop from user corrections."
