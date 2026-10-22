# Progress

> Working state. Update after every task and at the end of every session.
> Scope and specs live in [`PROJECT.md`](PROJECT.md), decisions in [`DECISIONS.md`](DECISIONS.md).

## Now
- **Current phase:** Phase 2: Wear OS + phone recorder (Phases 0 and 1 complete)
- **Branch:** work on `feature/*` off `dev`
- **Next step:** Phase 2 tasks P1 (Gradle skeleton + `:protocol` + golden files + Python reader) ‖ A1 (rotation augmentation default), per `docs/specs/phase2-recorder.md` §9. Then P2 (`:wear`) ‖ P3 (`:mobile`), then P4 (Android CI).

## Pending owner tasks
- [ ] Confirm Android package id (default `dev.proprio`; spec uses `dev.proprio.wear` / `dev.proprio.mobile`)
- [ ] Install a Wear OS system image + AVD in Android Studio (SDK Manager → SDK Platforms → Wear OS 5 or 6 image), **or** enable Wi-Fi debugging on the Galaxy Watch (Settings → Developer options). Needed only for the Phase 2 smoke test.
- [ ] U1: calibration recording at home, left wrist, ~20 min (after Phase 2)
- [ ] U2: two gym sessions × 20 sets, left wrist, with video if possible (after Phase 2)
- [ ] U3: two friends × 10 sets, plus their consent forms (after Phase 2)
- [ ] Install and smoke-test watch and phone builds (Phases 2, 5, 7)
- [ ] Create IBM Cloud account; approve resources and secrets (Phase 7)

## Blockers / open questions
- Rep counter fails every §6.5 rep gate on its own (training) data after cleaning: MAE 1.05, exact 40%, ±1 81%, OHP MAE 1.90. Optimistic (cutoffs tuned on this data; expected reps assumed). Phase 4 rework is mandatory.
- Resolved (D-018): the baseline tolerated only 2° of misalignment; rotation augmentation (≤30°, K=8) raises it to 10° at a cost of 0.5 pp macro-F1. Not yet wired into the default training pipeline.

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
- [ ] [S] P1 Gradle skeleton + `:protocol` + schemas + golden zips + Python reader `sensors/android.py`
- [ ] [S] A1 ‖ Rotation augmentation as default training regime (D-018)
- [ ] [S] P2 `:wear` ‖ [S] P3 `:mobile`
- [ ] [S] P4 `android-ci.yml`
- [ ] [U] Smoke test on watch or emulator; record measured rate/jitter

### Phase 3: calibration + domain shift
- [ ] [U] U1, U2 (×2), U3
- [ ] [S] Eval harness ‖ [S] DVC watch ingest
- [ ] [O] Verify units/rates; fit R; write Galaxy Watch profile; freeze `watch-val-v1`; domain-shift report (bench/OHP confusion as its own line item)

### Phase 4: remediation + PyTorch
- [ ] [O] Choose a branch of the decision tree
- [ ] [S] Robust features ‖ [S] 1D-CNN → ONNX ‖ [S] rep-counter rework (mandatory) ‖ evaluate dropping FFT features
- [ ] [S] DVC pipeline + `model-regression` CI job
- [ ] [O] Select production model; model card

### Phase 5: services MVP
- [ ] [O] OpenAPI contract, DB schema, AMQP schemas
- [ ] [S] core-api ‖ [S] inference-svc ‖ [S] compose + UBI Dockerfiles
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
- 2026-10-22: Phase 2 spec written. Android SDK present (platforms 30–36.1, JBR 21, phone AVD); no Wear OS image or cmdline-tools yet. Owner AFK with watch off; device steps deferred.
- 2026-10-22: Phase 1 complete. S2b and S2a merged; R2w/R2w8 follow-up run; D-018 recorded; Phase 1 review written.
- 2026-10-22: S1 merged. Spec corrected (anti-alias attenuation: ≥14 dB at 6 Hz, ≥20 dB at 7 Hz). Resampled row count is 8180 (82 sets, 7360 windows after trimming).
- 2026-10-22: Pushed `dev`; first GitHub CI run green. Owner approved merging `dev` → `main` (fast-forward to `f8b4e77`) and pushing. Phase 1 spec written (`docs/sensor_spec.md`).
- 2026-10-22: Phase 0 complete. 0.4b merged; clean rerun on `dev` reproduced metrics exactly (MLflow parent `f5231a5df0974e1e94a80b04f2cfe6b6`). Orchestrator review written; D-016 (duplicate raw files); PROJECT.md §6.1 corrected; Phase 1 gains a rotation-sensitivity test.
- 2026-10-22: 0.2 (rep counter library) and 0.3 (CI + cross-platform locks) merged into `dev`; 0.4 spec written.
- 2026-10-22: 0.1 merged into `dev`. Leaky baseline (random 80/20 row split, `duration` feature): best = decision tree on 10 selected features, accuracy 0.9994 / F1 0.9994. This number is NOT trustworthy; 0.4 replaces it with LOPO. Runtime ~35 min, dominated by forward selection (~25 min).
- 2026-10-22: 0.1 reviewed (AST-identical except config/pipeline). Fixed pandas 3 Arrow-string incompatibility in LearningAlgorithms (`.to_numpy()`). Owner requested MLflow: experiment tracking (Phase 0+), model registry with aliases candidate/production for CI gate and inference-svc (Phases 4-5), mlflow.evaluate for RAG golden set (Phase 6), server on Code Engine with Postgres backend + COS artifacts (Phase 7). Docs not yet updated for MLflow. Known: `duration` was selected by forward selection, confirming the leakage defect.
- 2026-10-21: Session `proprio-planning`. Analyzed the codebase and raw data, wrote the plan, collected decisions D-001–D-014, cleaned up and committed the baseline to main, created `dev`, and added the context docs.
