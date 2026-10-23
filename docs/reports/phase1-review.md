# Phase 1 review: CSF-1 layer, fake-watch round trip, rotation decision

> Written by hand. It interprets [`phase1-fake-watch-roundtrip.md`](phase1-fake-watch-roundtrip.md) and [`phase1-rotation-sensitivity.md`](phase1-rotation-sensitivity.md), which are generated.
> Spec: [`../sensor_spec.md`](../sensor_spec.md). Decision: D-018.

## Outcome
All Phase 1 acceptance criteria are met.

| Criterion | Result |
|---|---|
| §6.3 tests pass | 126 tests on `dev` (`be7adc8`) |
| Rewired pipeline reproduces Phase 0 | Bit-identical loader output; unchanged leaderboard |
| Fake-watch round trip within 1 pp | Set accuracy Δ 0.00 pp; macro-F1 Δ −0.27 pp; bench↔OHP confusion +4.5% (limit ±20%) |
| Stress variant (3× noise, 6 ms jitter, 1 s gap per set) | Same band; informational, not gated |
| Accuracy-vs-rotation curve | Produced for 5 variants; drives D-018 |

## 1. The conversion layer is sound
- The round trip goes MetaMotion → simulated Galaxy Watch (50 Hz, m/s², rad/s, jitter, unsynchronized streams, random orientation) → CSF-1.
- **The model can't tell the difference.** Units, rotation, regridding, anti-alias filtering, rate emulation and binning together cost less than 0.3 points of macro-F1.
- **This is the evidence that Phase 3's real-watch result will measure *domain* shift, not *conversion* bugs.** §6.6 step 1 ("fix the conversion first") now has a tested baseline to compare against.
- **Signal-level error on real sets:** mean 0.015 g and 1.75 deg/s; worst set 0.081 g and 9.8 deg/s.
  - The worst cases come from fast motion combined with the 0–20 ms unsynchronized stream offsets and the low-pass above about 5 Hz.
  - They don't move any model metric, so I accept them.
  - If Phase 3 shows a gap concentrated in fast exercises, this is the first place to look.

## 2. Rotation sensitivity and the decision (D-018)
Tolerance θ_tol is the largest misalignment, on the worst axis, at which set accuracy drops by ≤ 2 points and bench and OHP recall each drop by ≤ 5 points.

| Variant | Accuracy at θ = 0 (set acc / macro-F1) | θ_tol |
|---|---|---|
| Baseline (Phase 0 model) | 0.963 / 0.957 | **2°** |
| R1, magnitude-only features | 0.921 / **0.848** | 30° |
| R2, augmentation ≤ 15°, K = 4 | 0.963 / 0.941 | 5° |
| R2w, augmentation ≤ 30°, K = 4 | 0.963 / 0.952 | 10° |
| **R2w8, augmentation ≤ 30°, K = 8** | **0.963 / 0.952** | **10°** |

**Reading.**

**The baseline is fragile.**
- At 5° on the z axis, OHP recall falls from 0.98 to 0.80. At 10°, bench recall falls to 0.44.
- The two classes trade places depending on the sign of the rotation, exactly the bench/OHP mechanism predicted in the Phase 0 review.
- The z axis is the worst one. By the right-hand rule, rotating about z mixes the x and y components, which is precisely the plane where bench and OHP differ.

**The pre-set rule had a gap.**
- §7.3 said: if θ_tol < 5°, adopt R2, "or R1 if R2 can't hold θ_tol ≥ 10°".
- R2 (≤ 15°) held only 5°, so read literally the rule selects R1.
- But R1's macro-F1 at θ = 0 (0.848) **fails the 0.85 gate** of PROJECT.md §6.5 before any rotation is applied. The rule didn't consider a remedy that fails a gate on its own.
- Rather than follow a rule into a failing model, I tested the obvious next lever, **augmentation range**, under the same protocol and seeds. Then I chose by the rule's own tiebreak: the variant that loses least at θ = 0 among those holding θ_tol ≥ 10°.

**Decision: adopt R2w8** (originals plus 8 copies rotated by random angles up to 30° about random axes).
- It holds 10° with set accuracy unchanged, and macro-F1 drops only 0.5 points (0.957 → 0.952).
- It beats R2w on OHP recall under rotation:
  - minimum 0.929 vs 0.906 at 15°;
  - 0.887 vs 0.847 at 20°.
- Its cost is training time: 9× the training rows.

**Calibration gate.**
- θ_tol = 10° means the §7.3 rule keeps the Kabsch residual gate at **0.15 g** (about 8.6°, inside the 10° tolerance).
- That matters, because human posture varies between sets. Calibrating to the baseline's 2° (0.035 g) would probably never have been achievable from real lifting data.

**Scope of the decision.**
- R2w8 becomes the training regime for every model going forward:
  - the classical pipeline now;
  - the PyTorch 1D-CNN in Phase 4, which augments on the fly.
- Phase 3 evaluates **both** the baseline and the R2w8 model on real watch data. This tests whether the synthetic sensitivity result transfers to reality.

## 3. Caveats
- **Rotations are synthetic and global** (the same R for a whole set). A real watch can also shift during a set, or sit at a slightly different spot on the wrist. Phase 3 measures both.
- **Single seeds** (augmentation seed and round-trip seed), with 5 participants. The differences between R2w and R2w8 at θ ≤ 10° are within fold noise. The choice rests on the large-angle OHP robustness and on R2w8 not losing anything at θ = 0.
- **Clean provenance flags:** some MLflow runs in this phase carry `git_dirty=True`, because they were generated before their code was committed or because the report's own PNG was rewritten during the run. The code SHAs are recorded and the numbers are reproducible from them. Future analysis scripts should commit code before running and record git state before writing artifacts.

## 4. Changes triggered
1. **D-018:** rotation augmentation (≤ 30°, K = 8) is the default training regime; the Kabsch residual gate stays at 0.15 g; the §7.3 rule is amended (a remedy must itself pass the §6.5 gates at θ = 0).
2. **Pipeline (small Sonnet task, Phase 2 window):** add rotation augmentation to `workout_ml.models` as a first-class option of the training pipeline (default on, logged to MLflow), so the production baseline is the R2w8 model. Not done in Phase 1, to keep the merge reviewable.
3. **Phase 3:** the domain-shift report compares the baseline and R2w8 on watch data, with bench/OHP as a separate line item.
