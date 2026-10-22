# Spec: Phase 2 — Wear OS recorder, phone labeling app, Raw Format v1

> Owner: orchestrator. Implementation: Sonnet subagents (tasks P1–P4, §9). Status: approved for implementation.
> Context: [`PROJECT.md`](../PROJECT.md) §4 (architecture), §6.4 (recording protocol); [`sensor_spec.md`](../sensor_spec.md) (`RawStream`/`SensorSet`, Galaxy prior profile). Decisions D-001, D-005, D-006, D-012, D-013.

## 1. Goals
- Record **raw** accelerometer and gyroscope data on a Galaxy Watch (Wear OS), worn on the left wrist, with honest timestamps and full sensor metadata.
- Label it on the phone: exercise, load, reps the user confirms, rest, other.
- Move a recording from watch → phone → laptop as one versioned file (**Raw Format v1**) that Python reads into `SensorSet`s and converts with `to_csf`.
- Provide a guided **calibration mode** (U1) and a **gym session mode** (U2/U3).

Not in Phase 2: upload to a backend (Phase 5), live inference, and live rep counting.

## 2. Core design decision: continuous sessions plus segments
- A recording is a **session**: the watch records continuously from session start to session end. Sets, rest and "other" are **segments**: time intervals marked inside the session.
- **Why:**
  1. Rest and "other" periods get captured as real sensor data. The training data has no "other" class (PROJECT.md §10), so this is how we get negatives.
  2. Set boundaries can be corrected later, in the review screen, without losing samples.
  3. There's one transfer per session instead of dozens of small files.
- **Cost:** about 60 min × 2 sensors × 50 Hz is about 360k rows, roughly 10 MB as CSV and 3 MB zipped. That's acceptable.
- The watch's raw files are **never modified after the session ends**. The phone writes labels into a **separate** file (`labels.json`). Raw data stays immutable (D-012).

## 3. Raw Format v1 (session zip)
File name: `proprio-session-<session_id>.zip`. Contents:

| file | written by | content |
|---|---|---|
| `meta.json` | watch | format version, session, device, sensors, clock anchors (§3.1) |
| `acc.csv` | watch | `t_ns,x,y,z`: `t_ns` = `SensorEvent.timestamp` (elapsedRealtimeNanos clock, **not** Unix time); x,y,z in **m/s²**, Android device frame, `TYPE_ACCELEROMETER` (gravity included) |
| `gyr.csv` | watch | `t_ns,x,y,z`: same clock; **rad/s**, `TYPE_GYROSCOPE` (calibrated, bias-compensated) |
| `segments.csv` | watch | `segment_id,t_start_ns,t_end_ns,source,step`: marks created on the watch, either by a watch tap or by a phone command relayed to the watch. Same clock as the samples. `source` ∈ {`watch`,`phone`,`auto`}. `step` = calibration step id, or empty |
| `labels.json` | phone | per-segment labels plus session-level info (§3.2). Absent from a watch-only export |

**CSV rules**
- UTF-8, `\n` line endings, a header row, and no index column.
- Values use enough digits to round-trip a float32: Kotlin `Float.toString()` is fine.
- Rows are in arrival order. Readers must sort by `t_ns` and drop exact duplicate timestamps (count them in stats).
- **Crash safety:** the watch writes `acc.csv`/`gyr.csv` incrementally with a buffered writer, flushing at least every 2 s. A session interrupted by a crash or a dead battery still yields readable files. `meta.json` is then missing its `ended_*` fields and has `"complete": false`.

### 3.1 `meta.json` (watch)
```json
{
  "raw_format": 1,
  "session_id": "uuid-v4",
  "kind": "gym" | "calibration",
  "complete": true,
  "app": {"package": "dev.proprio.wear", "version_name": "0.1.0", "version_code": 1},
  "device": {"manufacturer": "...", "model": "...", "product": "...", "sdk_int": 34, "release": "14", "fingerprint": "..."},
  "wrist": "left",
  "clock_anchor_start": {"elapsed_realtime_ns": 0, "unix_time_ns": 0},
  "clock_anchor_end":   {"elapsed_realtime_ns": 0, "unix_time_ns": 0},
  "sensors": {
    "acc": {"type": 1, "name": "...", "vendor": "...", "version": 0, "resolution": 0.0, "maximum_range": 0.0,
            "min_delay_us": 0, "max_delay_us": 0, "fifo_reserved": 0, "fifo_max": 0,
            "requested_period_us": 20000, "max_report_latency_us": 0, "is_wake_up": false},
    "gyr": { "...same keys...": null }
  },
  "sample_counts": {"acc": 0, "gyr": 0},
  "started_at_utc": "ISO-8601", "ended_at_utc": "ISO-8601"
}
```
**Clock conversion** (reader side): `unix_ns = t_ns − anchor_start.elapsed + anchor_start.unix`.
- The end anchor measures clock drift over the session. The reader reports it, but doesn't correct for it unless it exceeds 50 ms.
- Anchors are captured as close together as possible: `SystemClock.elapsedRealtimeNanos()` and `System.currentTimeMillis()` in adjacent statements.

### 3.2 `labels.json` (phone)
```json
{
  "labels_format": 1,
  "session_id": "uuid-v4",
  "participant_id": "P01",
  "consent": {"training_use": true, "recorded_at_utc": "ISO-8601"},
  "segments": [
    {"segment_id": 3, "kind": "set" | "rest" | "other" | "calibration",
     "exercise": "bench" | "squat" | "dead" | "ohp" | "row" | null,
     "load_kg": 60.0, "category": "heavy" | "medium" | null,
     "reps_user": 5, "rpe": 8, "note": "",
     "t_start_ns": null, "t_end_ns": null}
  ],
  "app": {"package": "dev.proprio.mobile", "version_name": "0.1.0"}
}
```
- `t_start_ns` and `t_end_ns` are **overrides** from the review screen. `null` means "use the watch segment's times". The watch's `segments.csv` stays untouched.
- **Rest:** every gap between two consecutive `set` segments that isn't covered by another segment becomes an implicit `rest` segment (reader-side). It's trimmed 2 s at each edge to avoid transition motion.
- `participant_id` is pseudonymous. No names, emails or other PII anywhere in the zip.

### 3.3 Schemas and golden files
- JSON Schemas `meta.schema.json` and `labels.schema.json` live in `protocol/schema/` at the repo root. Both Kotlin and Python validate against them.
- **Golden files** live in `protocol/golden/`: `session_gym_v1.zip` and `session_calibration_v1.zip`.
  - They're generated **deterministically** by a Kotlin test in `:protocol`, using a synthetic signal and fixed timestamps with jitter.
  - They're committed.
  - Python reader tests and Kotlin reader tests both read them, which makes them a **cross-language conformance** test.
  - Regeneration is an explicit Gradle task, never a side effect of `test`.

## 4. Android project: `apps/android/`
- Gradle Kotlin DSL with a version catalog (`gradle/libs.versions.toml`), and a Gradle wrapper (≥ 8.14) **committed**.
- JDK 21: Android Studio's JBR at `C:\Program Files\Android\Android Studio\jbr`. SDK at `C:\Users\taimo\AppData\Local\Android\Sdk`, written to `local.properties`, which is **gitignored**.
- Use the latest **stable** AGP and Kotlin that resolve. `compileSdk = 36`, `targetSdk = 36`.
  - Watch `minSdk = 30` (Wear OS 3 = Galaxy Watch4, the oldest Galaxy Watch with Wear OS).
  - Phone `minSdk = 29`.
- Package ids: `dev.proprio.wear` and `dev.proprio.mobile`, sharing an `applicationId`-compatible signing setup for the Data Layer. Debug builds use the same default debug keystore, so they pair. (The owner hasn't overridden the default package id; PROGRESS lists it as a pending confirmation.)

**Modules**
- **`:protocol`**: pure Kotlin/JVM library with no Android dependency.
  - Data classes for meta, segments and labels (kotlinx.serialization).
  - `SessionWriter`: streaming CSV writers plus `meta.json`.
  - `SessionReader`, `SessionZip` (pack and unpack), and a schema-version check.
  - JUnit 5 tests, including golden generation and read-back.
- **`:wear`**: the Wear OS app (Compose for Wear OS). Depends on `:protocol`.
- **`:mobile`**: the phone app (Jetpack Compose, Material 3). Depends on `:protocol`.

## 5. Watch app (`:wear`)
**Sensor capture**
- `SensorRecorder` registers `TYPE_ACCELEROMETER` and `TYPE_GYROSCOPE` with `samplingPeriodUs = 20_000` and `maxReportLatencyUs = 0`.
- It runs on a dedicated `HandlerThread`, never the main thread.
- Each event goes to a lock-free or bounded queue, and a writer coroutine drains it to `SessionWriter`.
- It records `event.timestamp` exactly as delivered. **No resampling and no unit conversion on the watch** (D-012).
- **Testability:** hide `SensorManager` behind an interface (`SensorSource`), so JVM tests can inject fake event streams with jitter, duplicates, out-of-order events and gaps.

**Foreground service**
- `RecordingService` is a `foregroundServiceType="health"` service with an ongoing notification.
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_HEALTH`, `HIGH_SAMPLING_RATE_SENSORS`, `WAKE_LOCK`, plus `ACTIVITY_RECOGNITION` or `BODY_SENSORS` only if the health foreground-service type requires it at runtime on API 34+. Verify against the platform docs, request at runtime, and document what was needed.
- Hold a partial wake lock while recording.

**Marks and segments**
- A watch tap starts or stops a segment. A phone command does the same, via `MessageClient` paths:
  - `/proprio/v1/session/start`
  - `/proprio/v1/session/end`
  - `/proprio/v1/segment/start` (payload: JSON `{kind}`)
  - `/proprio/v1/segment/stop`
- The watch assigns `segment_id`s, monotonically increasing per session.
- Haptic: a short buzz on segment start, a double buzz on stop.

**Calibration mode**
- A guided sequence with on-screen instructions and haptic cues. Each step is an automatic segment with `source=auto` and `step=<id>`:
  - `table_flat_face_up` 120 s (noise and bias), then 6 static poses of 10 s each: `face_up`, `face_down`, `buttons_up`, `buttons_down`, `twelve_up`, `twelve_down`;
  - then `rot_x`, `rot_y`, `rot_z`: 3 slow 90° rotations each, about 5 s per rotation.
- There's a 5 s transition between steps, and it isn't segmented.

**Transfer**
- At session end, the watch writes the final `meta.json` and zips the files.
- It sends the zip with `ChannelClient` to the paired phone, retrying until the phone acknowledges.
- The watch deletes its copy only after the phone acks with a SHA-256 match.

**UI** (minimal; the phone is the main UI): start session, start/stop segment, end session, calibration mode, a live sample-rate indicator, and recording time.

## 6. Phone app (`:mobile`)
**Session screen**
- Start or end a session (sends commands to the watch).
- Pick the next set's exercise, load (kg) and category (heavy/medium).
- Big start-set and stop-set buttons.
- After stop, a **rep-count picker**, prefilled with nothing in Phase 2, plus optional RPE.
- "Other" start/stop button.
- A left-wrist reminder on first run.
- A participant id and consent toggle, set once per install and editable.

**Recordings list**
- Received sessions, each showing its status: complete or incomplete; labeled or unlabeled.

**Review screen**
- A plot of acceleration magnitude and gyro magnitude over the session, with segment overlays.
- Tap a segment to edit its labels or nudge its start and end (writes overrides into `labels.json`).

**Export**: through the share sheet, as a zip containing the watch files plus `labels.json`. Phase 5 replaces this with upload.

**Storage**: app-private storage; Room isn't needed. Labels live as JSON next to the received zip, and the export bundles them.

## 7. Python reader: `workout_ml/sensors/android.py`
- `read_session(path) -> Session`
  - Validates `meta.json` and `labels.json` against the schemas (dependency: `jsonschema`, added to the locks).
  - Sorts and dedups each stream.
  - Converts timestamps with the anchors.
  - Builds `RawStream(frame="android", unit="m/s^2"|"rad/s")`.
- `Session.segments(kinds=...) -> list[SensorSet]`
  - Applies label overrides and implicit rest.
  - Puts `label`, `category`, `reps_user`, `participant`, `segment_id` and `session_id` into each set's `meta`, ready for `to_csf(…, galaxy profile)`.
- `recording_stats(session) -> dict`, per sensor:
  - actual rate;
  - inter-sample interval p50, p95, p99 and max;
  - number of gaps > 100 ms;
  - number of duplicate timestamps;
  - acc–gyro start offset;
  - clock drift (end anchor);
  - fraction of acc samples with any |axis| > 1.9 g (PROJECT.md §6.2 clip question).
- CLI: `python -m workout_ml.sensors.android <zip>` prints the stats and a segment table.
- **Tests:** on the golden zips, with the exact expected counts, segments and stats from the golden generator, plus an end-to-end `to_csf` call on one segment.

## 8. Tests and CI
- **`:protocol`** (JUnit 5): write/read round trip, float precision, crash-safety (truncate a file mid-row → reader recovers all complete rows), golden read-back, schema validation.
- **`:wear`** (JUnit, plus Robolectric where needed):
  - `SensorRecorder` with a fake `SensorSource`: jitter, duplicates, out-of-order events, gaps;
  - segment state machine;
  - calibration sequencer timing;
  - message command parsing.
- **`:mobile`** (JUnit, plus Robolectric Compose UI tests):
  - labeling flow (start set → stop → rep picker → label saved);
  - implicit-rest preview;
  - review override writes `labels.json` correctly.
- **Instrumented tests:** one smoke test per app, which can't run until emulator or device images exist. They're written now and run later.
- **CI:** new workflow `.github/workflows/android-ci.yml` on `ubuntu-latest`, path filter `apps/android/**` and `protocol/**`. It sets up JDK 21 (temurin) and the Gradle cache, then runs `./gradlew :protocol:test :wear:testDebugUnitTest :mobile:testDebugUnitTest :wear:assembleDebug :mobile:assembleDebug lint`. It uploads test reports and the debug APKs as artifacts.

## 9. Tasks
| id | who | depends on | scope |
|---|---|---|---|
| P1 | Sonnet | this spec | Gradle project skeleton (wrapper, version catalog, 3 modules building), `:protocol` complete with tests, schemas, deterministic golden zips, Python reader (§7) with tests, `jsonschema` dependency + locks |
| P2 ‖ P3 | Sonnet | P1 merged | P2 `:wear` (§5) + tests; P3 `:mobile` (§6) + tests |
| P4 | Sonnet | P2+P3 merged | `android-ci.yml`, lint clean-up, README/CLAUDE.md commands |
| A1 ‖ P1 | Sonnet | D-018 | rotation augmentation as the default in the training pipeline (Python only) |
| — | owner | P2+P3 | install a Wear OS emulator image and AVD **or** enable watch Wi-Fi debugging; smoke test; then U1 |

## 10. Acceptance (Phase 2 overall)
1. `./gradlew` builds both APKs, all unit tests pass, and lint has no errors.
2. The golden zips round-trip in both Kotlin and Python, with identical parsed content.
3. `python -m workout_ml.sensors.android protocol/golden/session_gym_v1.zip` prints stats and segments; `to_csf` on a segment works with the Galaxy prior profile.
4. **Device or emulator, deferred to the owner:** a 2-minute session recorded on the Galaxy Watch reaches the phone, is exported, and is read in Python. The measured rate and jitter are recorded in PROGRESS and fed into the Phase 3 plan.
