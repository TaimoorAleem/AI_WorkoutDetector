# Progress

> Working state. Update after every task and at the end of every session.
> Scope and specs live in [`PROJECT.md`](PROJECT.md), decisions in [`DECISIONS.md`](DECISIONS.md).

## Now
- **Current phase:** Phase 0: hygiene and honest baseline
- **Branch:** work on `feature/*` off `dev`
- **Next step:** Phase 0 task 0.1: restructure `src/` into the `ml/workout_ml` package (Python 3.11, locked requirements, DVC with a local remote).

## Pending owner tasks
- [ ] Confirm Android package id (default `dev.proprio`)
- [ ] U1: calibration recording at home, left wrist, ~20 min (after Phase 2)
- [ ] U2: two gym sessions × 20 sets, left wrist, with video if possible (after Phase 2)
- [ ] U3: two friends × 10 sets, plus their consent forms (after Phase 2)
- [ ] Install and smoke-test watch and phone builds (Phases 2, 5, 7)
- [ ] Create IBM Cloud account; approve resources and secrets (Phase 7)

## Blockers / open questions
- None.

## Phase checklists

### Phase 0: hygiene and honest baseline
- [x] [O] Review WIP diff, clean legacy files, commit baseline to main (`ca6f57f`), create `dev`
- [x] [O] Project context docs: CLAUDE.md, PROJECT.md, PROGRESS.md, DECISIONS.md
- [ ] [S] 0.1 Restructure into `ml/workout_ml` package; Python 3.11; complete, locked requirements; DVC with local remote
- [ ] [S] 0.2 ‖ Rep counter as a pure function `reps.count(df, exercise, cfg)` with cutoffs in config, plus pytest
- [ ] [S] 0.3 ‖ GitHub Actions: ruff + pytest
- [ ] [O] 0.4 Leakage-fix spec → [S] implement: per-set filtering/rolling/FFT, outliers → NaN + per-set interpolation, PCA/scaler fitted in folds, drop `duration`, LOPO CV, set-level majority vote
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
- 2026-10-21: Session `proprio-planning`. Analyzed the codebase and raw data, wrote the plan, collected decisions D-001–D-014, cleaned up and committed the baseline to main, created `dev`, and added the context docs.
