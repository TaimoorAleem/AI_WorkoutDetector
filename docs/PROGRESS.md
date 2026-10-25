# Progress

> Working state. Update after every task and at the end of every session.
> Scope and specs live in [`PROJECT.md`](PROJECT.md), decisions in [`DECISIONS.md`](DECISIONS.md).

## Now
- **Current phase:** Phase 3: calibration + domain shift (Phases 0–2 complete)
- **Branch:** work on `feature/*` off `dev`
- **Next step:** V4 (phone upload). Sunday 2026-10-25: owner records U2 session 1 (`docs/U2_CHECKLIST.md`); orchestrator runs `python -m workout_ml.watch.evaluate --in-sample watch-fit-v1` that evening.

## Pending owner tasks
- [ ] Confirm Android package id (default `dev.proprio`; spec uses `dev.proprio.wear` / `dev.proprio.mobile`)
- [ ] Install a Wear OS system image + AVD in Android Studio (SDK Manager → SDK Platforms → Wear OS 5 or 6 image), **or** enable Wi-Fi debugging on the Galaxy Watch (Settings → Developer options). Needed only for the Phase 2 smoke test.
- [x] U1: calibration recording (2026-10-23, 275 s, watch in hand). All checks pass; see `docs/reports/u1-calibration.md`
- [ ] U2: two gym sessions × 20 sets, left wrist, with video if possible. Session 1 planned Sunday 2026-10-25
- [ ] U3: two friends × 10 sets, plus their consent forms (after Phase 2)
- [ ] Install and smoke-test watch and phone builds (Phases 2, 5, 7)
- [ ] Create IBM Cloud account; approve resources and secrets (Phase 7)

## Blockers / open questions
- Resolved (D-024): Samsung Freecess. Keeping the recording screen on (dimmed) keeps the process TOP; verified on the wrist with 0 gaps.
- Resolved: watch-started sets now ask the phone user to confirm the exercise.
- Resolved (P2c): watch tap debounce (1 s), discard prompt for segments < 3 s (note `"discarded"`, skipped by the Python reader), unconfirmed labels marked in Review.
- Resolved (P2c): a phone-started session now opens the watch app remotely (`RemoteActivityHelper`) and starts it from the foreground. Verified on device with the watch app closed.
- Rep counter fails every §6.5 rep gate on its own (training) data after cleaning: MAE 1.05, exact 40%, ±1 81%, OHP MAE 1.90. Optimistic (cutoffs tuned on this data; expected reps assumed). Phase 4 rework is mandatory.
- Resolved (D-018): the baseline tolerated only 2° of misalignment; rotation augmentation (≤30°, K=8) raises it to 10°. Now the default training regime (A1).
- Resolved (D-021): temporal/KNN with augmentation tolerates 15° (NN 10°), and is the production candidate. RF scores 0° only because one set flips at 2°; the 2 pp set-accuracy limit is finer than one set in a small fold.

## Phase checklists

### Phase 0: hygiene and honest baseline
- [x] [O] Review WIP diff, clean legacy files, commit baseline to main (`ca6f57f`), create `dev`
- [x] [O] Project context docs: CLAUDE.md, PROJECT.md, PROGRESS.md, DECISIONS.md
- [x] [S] 0.1 Restructure into `ml/workout_ml` package; Python 3.11; complete, locked requirements (incl. MLflow); DVC with local remote. Fixed pre-existing bugs found by the first end-to-end run: pandas 3 Arrow-string labels, blocking `plt.show()`, NaN rows from window features, short model names in `train_single_model`.
- [x] [S] 0.2 ‖ Rep counter library `workout_ml.reps` (`count_set`, `count_peaks`, `evaluate_counter`), cutoffs in `REP_CONFIG`, golden test vs original script
- [x] [S] 0.3 ‖ GitHub Actions `ml-ci.yml` (ruff + format + pytest on push/PR to dev/main); cross-platform uv locks, verified on Windows and Linux (Docker)
- [x] [O] 0.4a Leakage-fix spec: `docs/specs/phase0-leakage-fix.md`
- [x] [S] 0.4b Implemented the spec (per-set features/outliers, no `duration`, in-fold PCA, LOPO, set vote, MLflow tracking, generated report); 86 tests; pipeline 292 s
- [x] [O] 0.5 Review: `docs/reports/phase0-baseline-review.md`. Honest baseline: NN on temporal features, LOPO window acc 0.963±0.047, macro-F1 0.957±0.054, set acc 0.963±0.052 (was 0.9994 with leaks). Found 23 duplicate raw files (D-016).

### Phase 1: CSF-1 normalization layer
- [x] [O] `docs/sensor_spec.md`: CSF-1, conversion steps, profiles, fake-watch generator, tests, analyses, decision rule
- [x] [S] S1 `sensors/` package (`to_csf`, `from_canonical`, `load_profile`, `read_metamotion`), 2 device profiles, 106 tests; pipeline rewired; real-data output bit-identical to legacy loader, leaderboard unchanged
- [x] [S] S2a ‖ Rotation sensitivity (§7.2): baseline θ_tol 2°, R1 30° (fails macro-F1 gate), R2 5°, R2w 10°, R2w8 10°
- [x] [S] S2b Fake-watch generator `simulate_android` + tests; model-level round trip passes (set acc Δ 0.00 pp, macro-F1 Δ −0.27 pp, bench↔OHP +4.5%)
- [x] [O] §7.3 decision (D-018): adopt rotation augmentation R2w8; residual gate stays 0.15 g; rule amended. Review: `docs/reports/phase1-review.md`
- [ ] [S] Wire rotation augmentation into the default training pipeline (MLflow-logged), re-baseline

### Phase 2: Wear OS + phone recorder
- [x] [O] Spec: `docs/specs/phase2-recorder.md` (continuous sessions + segments, Raw Format v1, schemas, golden files, Data Layer paths)
- [x] [S] P1 Gradle 9.8 / AGP 9.4 / Kotlin 2.4 project; `:protocol` (writer, reader, zip, resolver, stats), JSON Schemas, deterministic golden sessions, Python reader + CLI; 24 Kotlin + 22 Python tests
- [x] [O] Data Layer contract in `:protocol` (`DataLayer.kt`: paths, capabilities, payloads)
- [x] [S] A1 Rotation augmentation default (`workout_ml/augment.py`); `--no-augment` reproduces Phase 0 exactly; augmented NN matches R2w8; new best temporal/KNN 0.966 / 0.959 / 0.986; runtime 20 min (7 min without)
- [x] [S] Rotation sensitivity for KNN and RF: KNN/R2w8 θ_tol 15°, RF/R2w8 0° (single-set artifact), KNN/baseline 5°, RF/baseline 0°
- [x] [S] P2 `:wear` (46 JVM tests; explicit LSM6DSO sensor selection; hash-verified transfer; orphan recovery) ‖ [x] [S] P3 `:mobile` (29 tests; labels bound to watch segment ids in either arrival order; review + export)
- [x] [O] Both apps installed on the owner's Galaxy Watch5 Pro (SM-R920, Wear OS 5 / API 34, LSM6DSO IMU) and Galaxy S25 Ultra (Android 17 / API 37)
- [ ] [S] P4 `android-ci.yml`
- [x] [U] First device smoke test: transfer, hash ack and label binding work end to end. Watch timing: 49.8 Hz, p50 20.08 ms, p99 20.11 ms (≈0.03 ms jitter), acc-gyr offset 0–20 ms, drift < 1 ms; LSM6DSO sensors selected; accelerometer range ±8 g
- [x] [S] P2b Debug adb test hook, anti-freeze strategies (ongoing, ambient, exercise, screen_on), watch-started set exercise prompt. Device matrix: only `screen_on` works (0 gaps, process TOP)
- [x] [U] Wrist test with `screen_on`: 289.7 s, 49.85 Hz, 0 gaps > 100 ms, max 20.2 ms, 0 freezes during session; squat label confirmed correctly
- [x] [S] P2c UX fixes + black recording screen + remote watch launch; device test: remote start works, 0 gaps over 148 s, discard recorded
- [x] [O] Readable recording screen: removed the 0.01 brightness override and dim text (owner: barely readable); battery tuning deferred to pre-launch (D-025)
- [x] [S] P4 `android-ci.yml` (tests, both APKs, lint 0 errors), verified in a Linux Android-SDK container; `gradlew` made executable; not yet run on GitHub
- [ ] [U] Optional, pre-launch: enable Always-on display so `ambient` (more battery-friendly) can be tested

### Phase 3: calibration + domain shift
- [x] [U] U1 calibration: |a| 0.989 g, gyro bias 0.11 deg/s, all 6 poses on the expected axes, gyro signs and units confirmed; data in DVC (`data/raw/watch.dvc`)
- [ ] [U] U2 (×2), U3
- [x] [O] Phase 3 spec `docs/specs/phase3-domain-shift.md`; owner checklist `docs/U2_CHECKLIST.md`
- [x] [S] H1 eval harness `workout_ml.watch` (ingest, calibration_check, weighted Kabsch with det/conditioning checks, τ, gates, diagnostics, report, MLflow, `dvc.yaml`); reproduces U1 numbers; recovers synthetic R within 0.07°
- [ ] [O] Verify units/rates; fit R; write Galaxy Watch profile; freeze `watch-val-v1`; domain-shift report (bench/OHP confusion as its own line item)

### Phase 4: remediation + PyTorch
- [ ] [O] Choose a branch of the decision tree
- [x] [S] C1 PyTorch 1D-CNN: LOPO set acc 0.986 ± 0.032, macro-F1 0.959 (ties KNN); ONNX parity 1.3e-6; `requirements-dl.lock` (CPU torch, optional `dl` extra); `WatchPredictor` API used by the harness as M4
- [ ] [S] Robust features ‖ [S] rep-counter rework (mandatory) ‖ evaluate dropping FFT features
- [ ] [S] DVC pipeline + `model-regression` CI job
- [ ] [O] Select production model; model card

### Phase 5: services MVP
- [x] [O] Spec `docs/specs/phase5-services.md`; contracts: `contracts/openapi/core-api.yaml` (17 operations), `contracts/events/session-{uploaded,analyzed}.schema.json`; DB schema in the spec
- [x] [S] V1 core-api (Spring Boot 3.5, contract-first, Argon2id, RS256 JWT + rotating refresh, outbox, IDOR tests; 61 tests incl. 35 Testcontainers; UBI image healthy)
- [x] [S] V2 inference-svc (FastAPI + pika worker; shared `workout_ml.watch.predict.analyze_session`; TTL delay-queue retries ×3 → DLQ + `failed` event; deterministic UUIDv5 event ids; 36 tests incl. 4 Testcontainers; UBI image healthy)
- [x] [S] V3 `deploy/compose.yaml` (healthy from clean; synthetic `model-init` for CI), REST Assured E2E (10 tests, spec §6 steps 1–10), `services-ci.yml` (not yet run on GitHub)
- [ ] [S] Phone upload/results/history/corrections + UI tests
- [ ] [S] REST Assured E2E
- [ ] [O] Integration review

### Phase 6: LLM coach + RAG
- [ ] [O] KB sources, prompts, tool schema, eval rubric
- [ ] [S] coach-svc; [S] golden set + nightly CI; phone chat UI

### Phase 7: CI/CD + IBM Cloud + security
- [ ] [S] Workflows, scans, ICR, Code Engine, IBM Databases/RabbitMQ provisioning
- [ ] [O] Security review
- [ ] [U] IBM Cloud account and secrets

### Phase 8: personalization + stretch
- [ ] [S] Per-user heads via RabbitMQ jobs
- [ ] Stretch: Kotlin live counter, Helm/kind, Granite LoRA

## Session log
- 2026-10-25: V3 compose + E2E + services CI reviewed and merged (D-028). V4 next.
- 2026-10-23: V2 inference-svc reviewed and merged (D-027). V3 started.
- 2026-10-23: H1, C1 and V1 merged. README rewritten; GitHub repo renamed to Proprio. Owner made the interview notes private: untracked, purged from `dev`/`main` history with git filter-repo in a separate clone, force-pushed with leases (D-026). Merged helper branches and worktrees removed.
- 2026-10-23: Phase 3 spec + U2 checklist written; H1 (harness) and C1 (CNN) started in parallel; Phase 5 spec and contracts written, V1 (core-api) started.
- 2026-10-23: Added `docs/BUILD_JOURNAL.md` (full journey, blog-series structure) at the owner's request; added to the session ritual. U2 session 1 planned for Sunday 2026-10-25.
- 2026-10-23: Pushed `dev`; first `android-ci` and `ml-ci` runs on GitHub both green. U1 recorded and analyzed: Android frame, units and gyro handedness confirmed on the real watch.
- 2026-10-22: Phase 2 complete. P2c and P4 merged; remote launch verified on device; dimming removed at owner's request (D-025).
- 2026-10-22: Freecess solved by keeping the dimmed recording screen on (D-024), verified on the wrist. Health Services exercise, Ongoing Activity and ambient (with AOD off) did not prevent the freeze. U1–U3 unblocked once P2c lands.
- 2026-10-22: First real-device tests. Fixed the Data Layer: both apps now share `applicationId` dev.proprio (D-022). Recording works, but Samsung Freecess freezes the watch app (D-023); allow-listing didn't help and was reverted.
- 2026-10-22: P2, P3 and the KNN/RF sensitivity follow-up merged. Watch and phone paired over adb Wi-Fi; both apps installed; first end-to-end smoke test in progress.
- 2026-10-22: P1, Data Layer contract and A1 merged. All three Sonnet agents hit the usage limit at once and resumed after the 2:10 pm reset (work was saved in worktrees). A1 found that `rotation_sensitivity.py` had been broken on `dev` since S1 removed the old loader methods; its tests didn't exercise that path.
- 2026-10-22: Phase 2 spec written. Android SDK present (platforms 30–36.1, JBR 21, phone AVD); no Wear OS image or cmdline-tools yet. Owner AFK with watch off; device steps deferred.
- 2026-10-22: Phase 1 complete. S2b and S2a merged; R2w/R2w8 follow-up run; D-018 recorded; Phase 1 review written.
- 2026-10-22: S1 merged. Spec corrected (anti-alias attenuation: ≥14 dB at 6 Hz, ≥20 dB at 7 Hz). Resampled row count is 8180 (82 sets, 7360 windows after trimming).
- 2026-10-22: Pushed `dev`; first GitHub CI run green. Owner approved merging `dev` → `main` (fast-forward to `f8b4e77`) and pushing. Phase 1 spec written (`docs/sensor_spec.md`).
- 2026-10-22: Phase 0 complete. 0.4b merged; clean rerun on `dev` reproduced metrics exactly (MLflow parent `f5231a5df0974e1e94a80b04f2cfe6b6`). Orchestrator review written; D-016 (duplicate raw files); PROJECT.md §6.1 corrected; Phase 1 gains a rotation-sensitivity test.
- 2026-10-22: 0.2 (rep counter library) and 0.3 (CI + cross-platform locks) merged into `dev`; 0.4 spec written.
- 2026-10-22: 0.1 merged into `dev`. Leaky baseline (random 80/20 row split, `duration` feature): best = decision tree on 10 selected features, accuracy 0.9994 / F1 0.9994. This number is NOT trustworthy; 0.4 replaces it with LOPO. Runtime ~35 min, dominated by forward selection (~25 min).
- 2026-10-22: 0.1 reviewed (AST-identical except config/pipeline). Fixed pandas 3 Arrow-string incompatibility in LearningAlgorithms (`.to_numpy()`). Owner requested MLflow: experiment tracking (Phase 0+), model registry with aliases candidate/production for CI gate and inference-svc (Phases 4-5), mlflow.evaluate for RAG golden set (Phase 6), server on Code Engine with Postgres backend + COS artifacts (Phase 7). Docs not yet updated for MLflow. Known: `duration` was selected by forward selection, confirming the leakage defect.
- 2026-10-21: Session `proprio-planning`. Analyzed the codebase and raw data, wrote the plan, collected decisions D-001–D-014, cleaned up and committed the baseline to main, created `dev`, and added the context docs.
