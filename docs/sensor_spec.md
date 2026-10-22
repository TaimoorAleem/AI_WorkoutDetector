# Sensor spec: CSF-1 normalization layer (Phase 1)

> Owner: orchestrator. Implementation: Sonnet subagents (tasks S1, S2a, S2b below). Status: approved for implementation.
> Context: [`PROJECT.md`](PROJECT.md) §6.1–6.3 and §6.5. Decisions D-006 (left wrist), D-012 (raw kept; conversion happens server-side in shared Python), D-013 (per-set inference), D-017 (baseline). Review: [`reports/phase0-baseline-review.md`](reports/phase0-baseline-review.md).

## 1. Purpose
Data from any device (MetaMotion today, Galaxy Watch from Phase 2) is converted by **one Python module** into **CSF-1**: the format the models are trained on. Training, the fake-watch tests, and later the inference service all import the same code. Nothing upstream (watch or phone) converts anything.

## 2. Data model

### 2.1 `RawStream`: one sensor, native form
A frozen dataclass in `workout_ml/sensors/types.py`:

| field | type | meaning |
|---|---|---|
| `sensor` | `"acc"` \| `"gyr"` | which sensor |
| `t_ns` | `np.ndarray[int64]` (n,) | sample timestamps, ns since Unix epoch (UTC), strictly increasing |
| `xyz` | `np.ndarray[float64]` (n, 3) | samples in the **source** units and **source** device frame |
| `unit` | `str` | `"g"`, `"m/s^2"`, `"deg/s"` or `"rad/s"` |
| `frame` | `str` | `"metamotion"` or `"android"` |

Validation happens in `__post_init__`:
- shapes match,
- `t_ns` is strictly increasing,
- the arrays are finite,
- `unit` is consistent with `sensor`.

A non-increasing timestamp raises `ValueError`. Readers are responsible for sorting and deduplicating timestamps beforehand.

### 2.2 `SensorSet`: one recorded set
A dataclass with `acc: RawStream`, `gyr: RawStream`, and `meta: dict`. Typical meta keys are `participant`, `label`, `category`, `set` and `source_file`; Phase 2 adds device metadata.

### 2.3 CSF-1: the canonical frame
A `pd.DataFrame` for one set, with the following structure.

**Index**
- A `DatetimeIndex` (naive UTC, matching today's pipeline).
- Bins are **200 ms wide and aligned to multiples of 200 ms since the Unix epoch**. That's equivalent to pandas' default `resample("200ms")` origin (`start_day`), because a day is a multiple of 200 ms.
- Each index value is the bin's left edge.

**Columns**
- `acc_x, acc_y, acc_z`: **g, gravity included**.
- `gyr_x, gyr_y, gyr_z`: **deg/s**.
- All in the **MetaMotion axis frame**, where +y points distally along the forearm (PROJECT.md §6.1).

**Values**
- **Bin semantics:** each bin holds the arithmetic mean of that sensor's (emulated, §3.4) samples whose timestamps fall in `[left, left+200 ms)`.
- **Missing bins:** a bin is kept only if **both** sensors have at least one sample in it. This reproduces today's `resample().mean()` followed by `dropna()`.

**Metadata**
- `df.attrs["csf"] = {"csf_version": 1, "profile_id": ..., "transform_id": ...}`.
- `transform_id` is a short hash of the profile's numeric content (scales, R, emulation, clip). Two CSF frames can only be compared if their `transform_id`s match.

The training pipeline adds the `participant`, `label`, `category` and `set` columns after conversion. CSF-1 itself is label-free.

## 3. Conversion: `to_csf(sensor_set, profile) -> pd.DataFrame`
These steps run **per sensor, in this order**, and are implemented in `workout_ml/sensors/transforms.py` and `resample.py`:

1. **Units.** `xyz *= profile.<sensor>.unit_scale`. The acc scale is 1 / 9.80665 for m/s² → g; the gyro scale is 180/π for rad/s → deg/s. MetaMotion uses 1.0 for both.
2. **Rotation.** `xyz_canonical = xyz @ R.T`, i.e. `canonical = R · device` for each sample.
   - The **same R applies to acc and gyro**. The gyro needs no sign flip under a proper rotation.
   - `R` must be orthonormal (‖RᵀR − I‖∞ < 1e-6) with det = +1. The profile loader raises on det = −1 (D-006: no mirroring).
3. **Clip (optional).** If `profile.clip.acc_g` is set, clip acc components to ±that value. The default is `null` (no clipping). The PROJECT.md §6.2 decision is pending Phase 3 data.
4. **Source-rate emulation** (only if `profile.emulation.enabled`):
   1. **Segment** the stream at gaps > `max_gap_ms` (default 500). Each segment is processed independently and the results are concatenated. Drop segments shorter than the filter's padding requirement, and log a WARNING when you do.
   2. **Regrid** each segment onto a uniform grid at the stream's **nominal rate**. The nominal rate is `1 / median(diff(t))` rounded to 0.1 Hz. The grid starts at the segment's first timestamp. Use linear interpolation.
   3. **Anti-alias** with a zero-phase Butterworth low-pass (`filtfilt`). Order is `anti_alias_order` (default 4). Cutoff is `anti_alias_fraction × target_hz / 2` (default fraction 0.8).
   4. **Decimate**: sample the filtered signal at the target rate (`acc_hz` 12.5, `gyr_hz` 25.0) by linear interpolation on a uniform grid starting at the segment's first timestamp.

   MetaMotion has `enabled: false`. Its streams are already at target rate with device timestamps, so this step is the identity.
5. **Bin** to 200 ms means per sensor, inner-join acc and gyro on the bin index, and order the columns as in §2.3.

**Inverse transforms** (`from_canonical`) are provided for steps 1–2 only. The fake-watch generator (§5) uses them.

## 4. Device profiles
YAML files in `ml/workout_ml/sensors/profiles/`, loaded with `load_profile(id_or_path)` and validated.

```yaml
profile_id: metamotion-r-2019        # never reuse an id for different numbers
csf_version: 1
device: {vendor: MbientLab, model: MetaMotionR, firmware: "1.4.4", platform: metawear}
acc: {source_unit: "g",     unit_scale: 1.0}
gyr: {source_unit: "deg/s", unit_scale: 1.0}
rotation: [[1,0,0],[0,1,0],[0,0,1]]  # canonical = R @ device
rotation_status: identity            # identity | prior | fitted
emulation: {enabled: false}
clip: {acc_g: null}
calibration: null
```

```yaml
profile_id: galaxy-watch-prior-v0
csf_version: 1
device: {vendor: Samsung, model: null, platform: android}   # model filled in Phase 3
acc: {source_unit: "m/s^2", unit_scale: 0.10197162129779283}
gyr: {source_unit: "rad/s", unit_scale: 57.29577951308232}
# PRIOR, unverified: left wrist, buttons toward the hand gives watch +x distal, so it maps to MetaMotion +y.
# MetaMotion x/z are unknown; this is one det=+1 completion. Phase 3 replaces it with a Kabsch fit.
rotation: [[0,1,0],[1,0,0],[0,0,-1]]
rotation_status: prior
emulation: {enabled: true, acc_hz: 12.5, gyr_hz: 25.0, anti_alias_order: 4, anti_alias_fraction: 0.8, max_gap_ms: 500}
clip: {acc_g: null}
calibration: {date: null, session: null, residual_g: null, gyro_profile_r: null}
```

**Rules**
- `transform_id` is derived from the numeric fields only (scales, R, emulation, clip).
- Changing any numeric field requires a new `profile_id`.
- **No test may depend on the prior R being correct.** Fake-watch tests use random rotations (§5).

## 5. Fake-watch generator: `workout_ml/sensors/simulate.py`
`simulate_android(sensor_set_canonical, profile, rng, *, rate_hz=50.0, jitter_ms=3.0, noise_acc_ms2=0.02, noise_gyr_rads=0.002, gyro_bias_rads=0.0, start_offset_ms=(0, 20)) -> SensorSet` with `frame="android"` and Android units.

1. **Upsample** each canonical stream to `rate_hz` with band-limited interpolation: `scipy.signal.resample_poly` from the stream's native rate, ×4 for acc and ×2 for gyro at 50 Hz.
2. **Apply the inverses**: `device = Rᵀ · canonical`, then divide by `unit_scale`.
3. **Add noise**: white Gaussian noise (σ above), plus an optional constant gyro bias.
4. **Generate timestamps**:
   - Nominal period `1/rate_hz`.
   - Per-sample jitter ~ N(0, `jitter_ms`), clipped to ±(0.4 × period), and kept strictly increasing.
   - Acc and gyro get **independent** start offsets drawn from `start_offset_ms`, so the streams are not synchronized.
   - Anchor the timestamps to the original set's first timestamp, so 200 ms bins line up with the source.
5. **Optional gap injection**: `gaps=[(start_s, dur_ms), ...]` removes samples, to test segmentation.

## 6. Tests (pytest; synthetic or fixture data; no raw-data dependency in CI)
- **Units:** a round trip through scale and inverse scale is identity, and 9.80665 m/s² converts to 1 g.
- **Rotation:**
  - Random proper rotations preserve per-sample norms.
  - det = −1 is rejected.
  - Non-orthonormal R is rejected.
  - Acc/gyro consistency: rotating a synthetic rigid-body rotation keeps the gyro integral consistent with the change in the gravity direction.
- **Emulation:**
  - A 0.5 Hz sine at 50 Hz with 3 ms jitter, after emulation to 12.5 Hz, keeps its frequency within 2% (FFT peak) and its amplitude within 5%.
  - A 6 Hz component is attenuated by at least 20 dB, which shows the anti-alias filter works.
- **Gaps:** a stream with a 1 s hole yields two segments, and no CSF bins fall inside the hole.
- **Binning:** bins are epoch-aligned to 200 ms; a bin with only one sensor is dropped; values are arithmetic means.
- **Profiles:** both YAMLs load, `transform_id` is stable, and changing a number changes `transform_id`.
- **MetaMotion regression (critical):** on synthetic MetaMotion-format CSVs, the new `metamotion reader → to_csf` path gives the same sensor values and index as the old `DataLoader.merge_sensors + resample_data` path (`assert_frame_equal`, `check_exact=False`, rtol 1e-12). Keep a frozen copy of the old resample logic **inside the test file** as the reference, so the old code can be deleted.
- **Fake-watch round-trip (signal level):** for a synthetic canonical set, `to_csf(simulate_android(x, P), P)` vs `to_csf(x, metamotion)` gives acc RMSE ≤ 0.03 g and gyro RMSE ≤ 3 deg/s over shared bins, with R random.

## 7. Analyses (run on real data and logged to MLflow; not part of CI)

### 7.1 Fake-watch round trip at model level (S2b)
- Use the Phase 0 best pipeline (`basic+derived+pca+temporal` / NN) with the same LOPO folds.
- Train each fold on the **original** CSF of the training participants.
- Evaluate on the held-out participant's sets **after** `simulate_android` (random R per set, the default noise and jitter) and `to_csf` with the matching profile.
- **Pass:** set accuracy and window macro-F1 within **1 pp** of Phase 0 (0.963 / 0.957), and bench/OHP confusion within ±20% of Phase 0.
- Also report signal-level RMSE per set.
- MLflow experiment: `proprio-sensor-roundtrip`.

### 7.2 Rotation sensitivity (S2a; independent of S1, operates in CSF space)
- Rotation commutes with 200 ms bin means, so it can be applied directly to today's cleaned CSF data, before feature engineering, to both acc and gyro.
- **Model and folds:** the Phase 0 best pipeline with LOPO folds, trained on unrotated data.
- **Rotations applied to the held-out participant only:**
  - Angles θ ∈ {0, 2, 5, 10, 15, 20, 30}°.
  - Axis = each of ±x, ±y, ±z (canonical frame).
  - Plus 10 random axes per angle (fixed seed).
- **Metrics per (θ, axis):** set accuracy, window macro-F1, bench recall, OHP recall, and the bench↔OHP confusion rate.
- **Reference curves**, so the decision is informed:
  - **R1, magnitude-only features:** `acc_r` and `gyr_r` plus their temporal statistics. Rotation-invariant by construction.
  - **R2, rotation augmentation:** the best pipeline trained with each training set duplicated under K = 4 random rotations of up to 15° (the test protocol is unchanged).
- **Outputs:**
  - `docs/reports/phase1-rotation-sensitivity.md` (generated tables).
  - A plot of set accuracy vs θ (worst axis and mean of the random axes) for baseline, R1 and R2.
  - MLflow experiment `proprio-sensitivity`.

### 7.3 Decision rule (orchestrator, after S2a)
- Let **θ_tol** be the largest θ where, on the **worst axis**, set accuracy drops by ≤ 2 pp and bench and OHP recall each drop by ≤ 5 pp, relative to θ = 0.
- A gravity-direction error of θ gives a Kabsch residual of about 2·sin(θ/2) g, so the current gate of 0.15 g corresponds to θ ≈ 8.6°.
- **If θ_tol ≥ 10°:** keep the baseline features and keep the 0.15 g gate.
- **If 5° ≤ θ_tol < 10°:** tighten the residual gate to 2·sin(θ_tol/2) g and keep the features.
- **If θ_tol < 5°:** adopt R2 (augmentation), or R1 if R2 can't hold θ_tol ≥ 10°, for Phase 4. Prefer whichever loses less at θ = 0. Log a DECISIONS entry.

## 8. Tasks
| id | who | depends on | scope |
|---|---|---|---|
| S1 | Sonnet | this spec | `sensors/` package (types, transforms, resample, profiles, metamotion reader, `to_csf`), §6 tests except the round-trip test, rewire `pipeline.py` + `DataLoader` onto `to_csf`, delete the superseded merge/resample code, add `pyyaml` as an explicit dependency (lock regenerated with uv), **pipeline metrics on real data unchanged** (set acc 0.963 / macro-F1 0.957 exactly) |
| S2a ‖ S1 | Sonnet | Phase 0 code only | §7.2 rotation sensitivity (own module, e.g. `workout_ml/analysis/rotation_sensitivity.py`; doesn't touch `sensors/` or the locks) |
| S2b | Sonnet | S1 merged | `simulate.py`, the §6 round-trip test, §7.1 analysis |
| — | orchestrator | S2a, S2b | §7.3 decision, review, PROJECT/PROGRESS/DECISIONS updates |

## 9. Out of scope for Phase 1
- Raw Format v1 file layout and the Android file reader (Phase 2, `:protocol`).
- Fitting R from real watch data (Phase 3).
- Gyro bias estimation. Android `TYPE_GYROSCOPE` is bias-compensated; this gets checked in U1.
