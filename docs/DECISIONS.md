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

**D-016 · 2026-10-22 · Ignore the 23 duplicate `*_1.4.41.csv` raw files**
They are value-identical re-exports of `*_1.4.4.csv` sets with timestamps shifted by exactly +24 h (all 23 pairs verified). The loader keys sets by file stem, keeps the `1.4.4` file and logs each ignored copy. The dataset is 82 unique sets. The old pipeline double-counted 11 sets plus one accelerometer-only file, which also leaked under its random split. The raw DVC dataset is left untouched (immutable raw data); deduplication happens in code.

**D-017 · 2026-10-22 · Phase 0 baseline accepted; set-level accuracy is the primary product metric**
LOPO best: NN on basic + derived + in-fold PCA + temporal features, window acc 0.963 ± 0.047, macro-F1 0.957 ± 0.054, set acc 0.963 ± 0.052. The top models are within fold noise of each other. Window macro-F1 stays as the diagnostic metric. Consequences: Phase 1 adds a rotation-sensitivity test (the model relies on absolute axes, and bench/OHP is the fragile pair); Phase 4 rep-counter rework is mandatory; dropping the FFT features is to be evaluated.

**D-018 · 2026-10-22 · Train with rotation augmentation (≤ 30°, K = 8); keep the 0.15 g calibration gate; amend the §7.3 rule**
The rotation-sensitivity study (`reports/phase1-rotation-sensitivity.md`) showed the Phase 0 model tolerates only 2° of misalignment before bench/OHP recall collapses (worst on the z axis). Results:
- magnitude-only features: rotation-proof, but fail the macro-F1 gate at zero rotation (0.848);
- augmentation ≤ 15° (K = 4): 5°;
- augmentation ≤ 30° (K = 4 or K = 8): 10°, at a cost of 0.5 pp macro-F1.

The pre-set rule would have picked magnitude-only features. It is amended: a remedy must itself pass the §6.5 gates at zero rotation, and the choice among passing remedies goes to the one that loses least at zero rotation. K = 8 is chosen over K = 4 for better OHP recall under large rotations.

Consequences:
- the Kabsch residual gate stays 0.15 g (≈ 8.6° < 10°);
- augmentation becomes the default training regime (classical pipeline now, CNN in Phase 4);
- Phase 3 evaluates baseline and augmented models on real watch data.

Rejected: magnitude-only features (fail a gate); tightening calibration to 2° (likely unachievable from human posture).

**D-019 · 2026-10-22 · Record continuous sessions with segments; the phone writes labels to a separate file**
The watch records the whole session continuously; sets, rest and other are time segments inside it. This captures rest and "other" motion as real sensor data (the training set has no negatives), lets set boundaries be corrected later without data loss, and means one transfer per session. The watch's raw files are immutable; the phone's `labels.json` holds labels and boundary overrides. Rejected: one file per set (loses rest/other data, can't fix boundary mistakes) and labels inside the watch's meta (mutates raw data).

**D-020 · 2026-10-22 · Rotation augmentation is the pipeline default; model selection re-runs with it**
`python -m workout_ml.pipeline` now trains every model on the originals plus 8 rotated copies (≤30°), inside each LOPO fold, with training participants only. `--no-augment` reproduces the Phase 0 baseline exactly; `phase0-baseline.md` stays frozen and the live report is `current-baseline.md`. With augmentation the best combination by macro-F1 is temporal/KNN (window acc 0.966, macro-F1 0.959, set acc 0.986), ahead of the NN (0.952). Because D-018's 10° tolerance was measured on the NN only, KNN/RF are being rotation-tested before any is treated as production. Runtime rises from 7 to 20 minutes, which is acceptable for an offline pipeline.

**D-021 · 2026-10-22 · Production candidate: temporal features + KNN, trained with rotation augmentation**
With augmentation, temporal/KNN has the best LOPO numbers (set acc 0.986, macro-F1 0.959) and the widest rotation tolerance measured (15°, vs NN 10°). It is the candidate for Phase 3 watch evaluation, alongside the NN as a comparison. RF/R2w8 scores θ_tol 0° on the rule but is behaviourally robust to about 20°: one set flips at 2° and accuracy then stays flat. That exposes a limit of the §7.3 rule: a 2 pp set-accuracy threshold is finer than one set in a small fold (a fold has 9 to 27 sets). Future tolerance rules should use pooled set counts or require the drop to persist across two consecutive angles. Not changed retroactively.

**D-022 · 2026-10-22 · Watch and phone apps share `applicationId` `dev.proprio`**
The Wearable Data Layer only connects apps with the same package name and signing key. The spec's separate ids (`dev.proprio.wear`, `dev.proprio.mobile`) meant the phone never found the watch, which unit tests with fake transports could not catch. Kotlin namespaces stay separate. The `package` strings inside recordings remain component labels.

**D-023 · 2026-10-22 · Samsung Freecess must be handled before any data collection**
On the Galaxy Watch5 Pro, Samsung's Freecess freezes the recorder process (`FZ … reason: LEV`) about 15–20 s after the screen turns off, and disables its partial wake lock, even with a `health` foreground service. The 300-event sensor FIFO (~6 s) then overflows and samples are lost. In the first real session, 7 gaps (up to 22.6 s) fell inside labeled sets. Adding the app to the battery allow-list (`deviceidle whitelist`) did not help (frozen for 133 s of a 152 s session) and was reverted. Data collection (U1, U2, U3) is blocked until a 5-minute screen-off session has zero gaps over 100 ms. Candidate fixes, tested on the device in this order: the Wear OS Ongoing Activity API, an ambient-mode recording screen, and a Health Services exercise session.

**D-024 · 2026-10-22 · Keep the dimmed recording screen on during sessions (Freecess fix)**
Device tests on the Galaxy Watch5 Pro showed Samsung Freecess freezes any non-TOP process, including a `health` foreground service (procState 4). The Wear OS Ongoing Activity API, a Health Services strength-training exercise (it runs in the Health Services process, not ours), and ambient mode (untestable with Always-on display off) all failed: each run froze about 5–12 s after screen-off. Holding `FLAG_KEEP_SCREEN_ON` with brightness 0.01 while recording keeps the activity TOP. Results: 0 freezes and 0 gaps over 180 s on the charger, and 289.7 s at 49.85 Hz with a maximum interval of 20.2 ms on the wrist. Cost: screen-on battery drain during sessions (not yet measured). The default strategy is `all` (including `screen_on`). Ambient mode with Always-on display remains a possible battery-friendlier alternative, to be tested later. Rejected: battery allow-list (no effect), and periodic broadcast keep-alives (unnecessary hack once `screen_on` works).

**D-025 · 2026-10-22 · Readable recording screen now; battery optimization deferred to pre-launch**
At brightness 0.01 with dark-gray text the recording screen was barely readable on the owner's watch. The brightness override is removed; the recording screen stays on (D-024) with a black background and readable light text. Battery tuning (brightness, ambient mode with Always-on display, measuring drain per session) is deferred until before launch, at the owner's request. Data collection needs correct, gap-free recordings, not long battery life.
