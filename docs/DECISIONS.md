# Decision Log

Append-only. Newest at the bottom. Format: **ID · date · decision**, followed by the reason and the alternatives rejected.
Scope-affecting decisions must also be reflected in [`PROJECT.md`](PROJECT.md).

---

**D-001 · 2026-10-21 · Target watch: Samsung Galaxy Watch (Wear OS)**
The owner has one. Raw IMU comes from the standard Android `SensorManager`; Samsung's Health Sensor SDK isn't needed.

**D-002 · 2026-10-21 · Backend split: Java (Spring Boot) core-api + Python ML services**
Different runtimes justify a real service boundary, and the split covers both Java and Python. Rejected: all-Python, which means less Java/JUnit coverage.

**D-003 · 2026-10-21 · Cloud: IBM Cloud (free tier + trial credits)**
Code Engine, COS and Container Registry. Postgres runs on IBM Cloud Databases and RabbitMQ on IBM Cloud Messages for RabbitMQ (both paid, so use trial credits during the demo window). docker-compose for daily work.

**D-004 · 2026-10-21 · Timeline: 12+ weeks**
Stretch items (K8s, Granite LoRA, live on-watch counter) are included as optional.

**D-005 · 2026-10-21 · No web client**
The owner chose to skip it. Consequences: HTML/JS/Ajax is an acknowledged gap, Selenium/Puppeteer are dropped, and UI automation uses Espresso/Compose UI tests. The recording-review UI moves to the phone.

**D-006 · 2026-10-21 · Left wrist only**
The MetaMotion dataset is assumed to be recorded on the left wrist (the source doesn't document it), and users always wear the watch on the left wrist. No mirror transform. The Kabsch fit residual and det check verify the assumption, and the decision gets escalated if they contradict it.

**D-007 · 2026-10-21 · Product name: Proprio**
Android package id defaults to `dev.proprio.*`, pending owner override.

**D-008 · 2026-10-21 · Friends join validation collection (U3)**
Two people do 10 sets each, with written consent, to estimate between-user variance.

**D-009 · 2026-10-21 · Baseline cleanup committed to main; all work on `dev`**
Legacy exploratory scripts were removed and generated artifacts untracked (commit `ca6f57f`). All branches come off `dev` and merge back to `dev`. **Nothing goes to `main` until the owner says so.**

**D-010 · 2026-10-21 · Commit authorship: owner only**
No Claude co-author trailers on commits and no Claude attribution in PRs.

**D-011 · 2026-10-21 · Source of truth: `docs/PROJECT.md` in the repo**
Versioned with the code and readable by every future session. Rejected: a Claude Doc on claude.ai, which drifts and needs a connector to read.

**D-012 · 2026-10-21 · Raw sensor data is kept; conversion to CSF-1 happens server-side in shared Python code**
This avoids train/serve skew, and calibration can be fixed after the fact by reprocessing raw data. Rejected: converting on the watch or phone.

**D-013 · 2026-10-21 · Per-set inference for the MVP (not streaming)**
Sets have a median length of 19 s, so a result 1–2 s after the set is acceptable. A live on-watch counter is a stretch goal, checked against Python golden vectors.

**D-014 · 2026-10-21 · RabbitMQ over Kafka; Gradle over Maven; PostgreSQL over Cassandra**
RabbitMQ gives task-queue semantics, retries and a DLQ, with no need for replay or streaming. Android already requires Gradle. The data is relational at small scale, and pgvector gives a vector store in the same DB.

**D-015 · 2026-10-22 · Adopt MLflow for experiment tracking, model registry and LLM evaluation**
Owner request. Every training run is tracked (params, metrics, plots, DVC data hash, git SHA). The registry's `candidate`/`production` aliases drive the CI regression gate and what inference-svc serves, so promotion and rollback don't need a redeploy. The coach's golden-set evaluation runs through `mlflow.evaluate` with versioned prompts. Hosting: local server for development; in the cloud, an MLflow server on Code Engine with its backend DB on the existing IBM Postgres instance and artifacts in COS. Division of labor: DVC versions data, MLflow tracks runs and models. Rejected: a JSON model card in the repo as the registry (no promotion workflow, no run history).
