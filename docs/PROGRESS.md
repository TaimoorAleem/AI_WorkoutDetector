# Progress

> Working state. Update after every task and at the end of every session.
> Scope and specs live in [`PROJECT.md`](PROJECT.md), decisions in [`DECISIONS.md`](DECISIONS.md).

## Now
- **Current phase:** Phase 0: hygiene and honest baseline
- **Branch:** work on `feature/*` off `dev`
- **Next step:** Phase 0 task 0.4b: implement `docs/specs/phase0-leakage-fix.md` (per-set preprocessing/features, no `duration`, in-fold PCA, LOPO, set-level vote, MLflow tracking, baseline report) on `feature/phase0-leakage` off `dev`. Then 0.5 (orchestrator review + report interpretation).

## Pending owner tasks
- [ ] Confirm Android package id (default `dev.proprio`)
- [ ] U1: calibration recording at home, left wrist, ~20 min (after Phase 2)
- [ ] U2: two gym sessions × 20 sets, left wrist, with video if possible (after Phase 2)
- [ ] U3: two friends × 10 sets, plus their consent forms (after Phase 2)
- [ ] Install and smoke-test watch and phone builds (Phases 2, 5, 7)
- [ ] Create IBM Cloud account; approve resources and secrets (Phase 7)

## Blockers / open questions
- Rep counter already fails most §6.5 gates on its own (training) data: MAE 0.89 (pass), but OHP MAE 1.85, exact 51%, ±1 82%. Optimistic numbers (cutoffs tuned on this data; expected reps assumed from category). Rework planned in Phase 4 (§6.6 rep-counter line).
- CI not yet exercised on GitHub (nothing pushed; owner decides when to push).

## Phase checklists

### Phase 0: hygiene and honest baseline
- [x] [O] Review WIP diff, clean legacy files, commit baseline to main (`ca6f57f`), create `dev`
- [x] [O] Project context docs: CLAUDE.md, PROJECT.md, PROGRESS.md, DECISIONS.md
- [x] [S] 0.1 Restructure into `ml/workout_ml` package; Python 3.11; complete, locked requirements (incl. MLflow); DVC with local remote. Fixed pre-existing bugs found by the first end-to-end run: pandas 3 Arrow-string labels, blocking `plt.show()`, NaN rows from window features, short model names in `train_single_model`.
- [x] [S] 0.2 ‖ Rep counter library `workout_ml.reps` (`count_set`, `count_peaks`, `evaluate_counter`), cutoffs in `REP_CONFIG`, golden test vs original script
- [x] [S] 0.3 ‖ GitHub Actions `ml-ci.yml` (ruff + format + pytest on push/PR to dev/main); cross-platform uv locks, verified on Windows and Linux (Docker)
- [x] [O] 0.4a Leakage-fix spec: `docs/specs/phase0-leakage-fix.md`
- [ ] [S] 0.4b Implement the spec: per-set filtering/rolling/FFT, outliers → NaN + per-set interpolation, PCA/scaler fitted in folds, drop `duration`, LOPO CV, set-level majority vote
- [ ] [O] 0.5 Review and write LOPO baseline report

### Phase 1: CSF-1 normalization layer
- [ ] [O] `docs/sensor_spec.md` (the §6.2 table, CSF-1, profile schema)
- [ ] [S] `sensors/` + tests ‖ [S] synthetic fake-watch generator + round-trip test
- [ ] [S] Rewire pipeline onto CSF; regression test vs legacy loader

### Phase 2: Wear OS + phone recorder
- [ ] [O] `:protocol` (Raw Format v1, meta.json schema, Data Layer messages)
- [ ] [S] `:wear` ‖ [S] `:mobile`
- [ ] [S] JUnit writer/parser tests; Python Raw v1 reader with golden files
- [ ] [U] Install and smoke test

### Phase 3: calibration + domain shift
- [ ] [U] U1, U2 (×2), U3
- [ ] [S] Eval harness ‖ [S] DVC watch ingest
- [ ] [O] Verify units/rates; fit R; write Galaxy Watch profile; freeze `watch-val-v1`; domain-shift report

### Phase 4: remediation + PyTorch
- [ ] [O] Choose a branch of the decision tree
- [ ] [S] Robust features ‖ [S] 1D-CNN → ONNX ‖ [S] rep re-tune
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
- 2026-10-22: 0.2 (rep counter library) and 0.3 (CI + cross-platform locks) merged into `dev`; 0.4 spec written.
- 2026-10-22: 0.1 merged into `dev`. Leaky baseline (random 80/20 row split, `duration` feature): best = decision tree on 10 selected features, accuracy 0.9994 / F1 0.9994. This number is NOT trustworthy; 0.4 replaces it with LOPO. Runtime ~35 min, dominated by forward selection (~25 min).
- 2026-10-22: 0.1 reviewed (AST-identical except config/pipeline). Fixed pandas 3 Arrow-string incompatibility in LearningAlgorithms (`.to_numpy()`). Owner requested MLflow: experiment tracking (Phase 0+), model registry with aliases candidate/production for CI gate and inference-svc (Phases 4-5), mlflow.evaluate for RAG golden set (Phase 6), server on Code Engine with Postgres backend + COS artifacts (Phase 7). Docs not yet updated for MLflow. Known: `duration` was selected by forward selection, confirming the leakage defect.
- 2026-10-21: Session `proprio-planning`. Analyzed the codebase and raw data, wrote the plan, collected decisions D-001–D-014, cleaned up and committed the baseline to main, created `dev`, and added the context docs.
