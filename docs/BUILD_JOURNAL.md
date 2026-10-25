# Proprio build journal

> The full story of building Proprio, in order. It's raw material for a multi-part blog series after launch.
> Every entry covers: **what I did, why, what I rejected, what happened (with numbers), and what I learned.**
> Numbers come from reproducible runs. Evidence lives in `docs/reports/`, MLflow and git history.
> Kept current alongside [`PROGRESS.md`](PROGRESS.md) (state) and [`DECISIONS.md`](DECISIONS.md) (decision log).

## Series outline
| Part | Working title | Covers | Status |
|---|---|---|---|
| 1 | From notebook to product plan | Auditing the proof of concept, the product vision, honest tech choices, the working setup | written below |
| 2 | The 99.9% that wasn't real | Packaging, reproducibility, the first end-to-end run, data leakage, duplicate data, the honest baseline | written below |
| 3 | Teaching a model trained on one sensor to read another | Measuring the domain gap, the canonical sensor format, the fake-watch test, rotation sensitivity | written below |
| 4 | Designing a recorder around the data you'll need | Session/segment design, the recording format, cross-language conformance, the watch and phone apps | written below |
| 5 | What only real hardware could tell me | Data Layer package ids, the Samsung Freecess saga, remote launch, UX from real use | written below |
| 6 | Proving the sensor assumptions | The calibration protocol and the physics-based checks | written below |
| 7 | Real gym data and domain shift | U2/U3 collection, fitting the axis rotation, pass/fail gates on watch data | upcoming |
| 8 | A PyTorch model and a real rep counter | 1D-CNN, augmentation on the fly, ONNX, rep-counter rework | upcoming |
| 9 | From scripts to services | Spring Boot core, FastAPI inference, PostgreSQL, RabbitMQ, Docker on Red Hat UBI | upcoming |
| 10 | An LLM coach that cites its sources | Granite on watsonx.ai, RAG with pgvector, tool calls, evaluation in MLflow | upcoming |
| 11 | Shipping it | CI/CD to IBM Cloud, security, battery tuning, launch | upcoming |

---

## Part 1 — From notebook to product plan (2026-10-21)

### Where it started
- **The proof of concept, from 2019.** A Python project that classified five barbell exercises (bench, squat, deadlift, overhead press, row) and counted reps. Its data came from a single MetaMotion wrist sensor worn by five people.
- **How the pipeline worked:**
  - load the accelerometer and gyroscope CSVs;
  - resample to 5 Hz;
  - remove outliers;
  - engineer features (low-pass filter, rolling statistics, FFT, PCA);
  - compare five classifiers.
- **The claim:** 99.9% accuracy.
- **The goal:** turn it into a real product that uses every technology in an IBM AI Center of Excellence job description. The rule: each technology must have a genuine job, and any weak fit gets called out instead of bolted on.

### What I did
1. **Audited the codebase before planning anything.**
   - I read every module.
   - I measured the raw data myself instead of trusting the README. That covered units, gravity, sampling regularity and per-exercise gravity vectors.
   - I found that the sensor's +y axis points along the forearm, from physics alone: +0.94 g during bench press (hand above wrist) and −1.02 g during deadlift (hand below wrist).
2. **Named the gaps up front:**
   - a random train/test split over overlapping time windows;
   - a "duration" feature that knows the future;
   - filters running across set boundaries;
   - no tests or CI;
   - pickles committed to git.
3. **Wrote a product vision:** "Proprio". You lift with a Galaxy Watch on; it recognizes the exercise and counts reps; a phone app logs it; an LLM coach explains your training.
4. **Mapped every technology to a real job, with a priority.** The honest calls:
   - Kafka is overkill, so RabbitMQ.
   - Cassandra is a poor fit, so PostgreSQL + pgvector.
   - Android forces Gradle, so no Maven.
   - The LLM must not classify sensor data. It explains and coaches.
   - Dropping the web client (an owner decision) leaves HTML/JS/Ajax uncovered, and the docs say so.
5. **Planned the hardest risk first: sensor domain shift.** A model trained on a 2019 research sensor has to work on a consumer smartwatch. I put a calibration and validation phase *before* any model-serving work, with pass/fail gates defined in advance.

### Decisions made with the owner
- Samsung Galaxy Watch (Wear OS).
- Java Spring Boot core plus Python ML services.
- IBM Cloud (free tier plus trial credits).
- A 12+ week timeline.
- No web client.
- Left wrist only.
- The name "Proprio".
- Friends help with validation data.

### How I set up to work
- **Documents as memory:**
  - `PROJECT.md` is the source of truth for scope;
  - `PROGRESS.md` holds the current state;
  - `DECISIONS.md` is an append-only log with the reason for every decision;
  - `CLAUDE.md` tells any new AI session how to resume.
- **Git flow:**
  - a stable `main`;
  - all work on feature branches off `dev`;
  - `main` only moves on explicit approval;
  - I'm the sole author of every commit.
- **AI workflow:**
  - a stronger model (Opus) orchestrates: analysis, architecture, specs, review;
  - smaller models (Sonnet) implement tightly scoped tasks in isolated git worktrees;
  - every result is reviewed before merging.

### Lessons
- Measure the data before you plan around it.
- Decide what "good enough" means before you see the results.
- Writing down why you rejected an option is as valuable as writing down what you chose.

---

## Part 2 — The 99.9% that wasn't real (2026-10-21 to 22)

### Cleaning up and making it reproducible
- **Removed legacy scripts** that duplicated the class-based pipeline, and stopped tracking generated artifacts.
- **Packaged the code** as an installable Python package (`workout_ml`) on Python 3.11, with locked dependencies.
- **Versioned the raw dataset with DVC.** DVC versions data; **MLflow** tracks runs and models. Every run records the data hash and git commit.
- **Added GitHub Actions CI** (lint, format, tests).
  - I caught that the lock files were Windows-only (`pywin32` pinned with no platform condition).
  - I switched to `uv` universal locks and verified them in a Linux Docker container before pushing.
- **Proved the refactor changed no logic.** I compared the parsed code structure of every module before and after. Behavior-preserving changes deserve proof, not trust.

### The first end-to-end run: four latent bugs
The class-based pipeline had never completed a full run. The first one found:
1. **pandas 3** stores text as Arrow-backed strings, which scikit-learn couldn't index.
2. **A 6.5-hour hang.** I diagnosed it by watching CPU time: completely flat, so the process was waiting, not working. A `plt.show()` was waiting for a window nobody would close.
3. **NaN rows** from incomplete rolling windows. I checked the blast radius first: exactly 10 rows.
4. **A model-name mismatch** between two functions.

I also built a watcher for long runs that alerts on stage changes, errors, flat CPU and exit.

### Finding the leaks
I audited how the 99.9% was produced and found five ways test data leaked into training:
1. **Random 80/20 split over overlapping 200 ms windows.** Near-identical neighbours landed on both sides.
2. **The `duration` feature,** computed over the whole set. It was selected second-best. Heavy sets are 5 reps and medium sets 10, so set length nearly gives the label away.
3. **Filters and rolling windows ran across set boundaries.**
4. **PCA and outlier statistics were fitted on all data,** test participants included.
5. **Duplicate files.** 23 raw files were byte-identical copies of other sets, shifted by exactly +24 h. The "94 vs 93 files" mismatch was the clue. My own first check got it wrong because it ignored timestamps, and I caught that too.

### The fix, written as a spec first
- **Three rules:**
  - anything computed at inference must work on one set without its label;
  - anything fitted happens inside the training fold, using scikit-learn Pipelines;
  - evaluation is **leave-one-participant-out (LOPO)**.
- **A tripwire set in advance:** if the "fixed" score still came out at 0.99 or higher, assume there's still a leak.
- **MLflow tracks every run:** a parent run per pipeline invocation, a child run per model × feature set, and the best model logged.

### Result
| | Before | After |
|---|---|---|
| Accuracy | 0.9994 (leaky) | **window 0.963 ± 0.047, macro-F1 0.957, set accuracy 0.963** (LOPO) |
| Dataset | "94 sets" | **82 unique sets** |
| Runtime | ~35 min | ~5 min |

- **The errors cluster where physics predicts: bench vs overhead press.** Their average gravity vectors differ by only about 0.13 g on one axis.
- **The rep counter fails every gate,** even on its own tuning data: off by 1.05 reps on average, exact on only 40% of sets. I reported that rather than hiding it.

### Lessons
- An impressive number is a question, not an answer. Ask how it was produced.
- Group your cross-validation by the unit you'll deploy to (a person), not by the sample.
- Pre-register your red flags.

---

## Part 3 — Teaching a model trained on one sensor to read another (2026-10-22)

### The gap, measured
- **MetaMotion:** g with gravity included, deg/s, 12.5/25 Hz with zero jitter, +y along the forearm, x/z unknown, wrist side undocumented (assumed left).
- **Android:** m/s², rad/s, roughly 50 Hz with irregular timing, separate unsynchronized sensor streams, and its own axes.

### The design: one canonical format (CSF-1)
- **One Python module** converts any device's raw data into the exact format the model was trained on. Training and serving use the same code, so there's no skew.
- **Raw data is always kept,** so the calibration can improve later and old data can be reprocessed.
- **The conversion steps:** unit scaling, a rotation matrix (determinant +1 enforced, so no accidental mirroring), anti-alias filtering, then **emulating MetaMotion's sampling rates** so the data matches spectrally, not just in units.
- **Per-device YAML profiles,** each with a hash of its numeric content, so two converted datasets can only be compared if they used the same transform.
- **Rewiring proof:** the old loader's output, saved before deleting it, matched the new path **bit for bit**. The leaderboard was identical.
- **The tests corrected the spec once.** I'd asked for 20 dB of filter attenuation at 6 Hz, but an order-4 Butterworth filter run forward and backward with a 5 Hz cutoff can only reach about 14.5 dB there. We fixed the spec rather than weaken the test.

### The fake watch
- **Before owning any watch data,** I simulated a Galaxy Watch from the training data: 50 Hz, Android units, timing jitter, unsynchronized streams, a random orientation, plus a stress variant with triple noise and 1-second gaps.
- **Result:** after converting back, the model lost **0.0 points of set accuracy and 0.27 points of macro-F1.**
- So later real-watch gaps would mean real domain differences, not conversion bugs.

### Rotation sensitivity, and a rule with a gap
- **The baseline model tolerated only 2° of misalignment** before bench and overhead press swapped places.
- **My pre-set rule picked rotation-invariant features,** but those scored 0.848 macro-F1 with **no rotation at all**, failing my own 0.85 gate.
- **One more controlled experiment instead.** Training on 8 copies of each set rotated by up to 30° raised the tolerance to **10°** for 0.5 points of macro-F1. I amended the rule: a fix must itself pass the gates.
- **Checked it per model.** With augmentation, KNN became the best model, with 0.986 set accuracy. Rerunning the rotation study for it showed **15°** of tolerance.
  - Random forest scored "0°" only because one set flipped at 2°, which exposed that my threshold was finer than one set in a small fold.
  - Augmentation became the pipeline default.

### Lessons
- Build the simulator before the hardware arrives.
- When a pre-registered rule turns out to have a gap, fix it openly, don't game it or follow it blindly.
- Re-validate when the winning model changes.

---

## Part 4 — Designing a recorder around the data you'll need (2026-10-22)

### Key design choices
- **Continuous sessions with segments,** not one file per set.
  - The watch records the whole session, and sets, rest and "other" are time intervals inside it.
  - This captures "not lifting" data for free. The training data has none, and a workout logger must not log walking as a lift.
  - Set boundaries can be fixed later without losing samples.
- **Immutable raw data.** The watch writes samples, device metadata and clock anchors; the phone writes labels into a separate file.
- **Explicit clocks.** Timestamps stay exactly as Android delivers them (boot-time nanoseconds), with anchor pairs, so converting to wall-clock time is explicit and drift is measurable.
- **Crash safety.** Files are flushed every 2 s, and a session cut short by a crash or dead battery is still readable and transferred.
- **Cross-language conformance.** A deterministic Kotlin generator produces "golden" session files that both the Kotlin and Python readers must parse identically. CI fails if they go stale.
- **One shared message contract** (paths, payloads) in a library both apps depend on. I wrote it myself before the two apps were built in parallel.

### What got built
- **A Gradle project with three modules.**
  - A pure-Kotlin protocol library.
  - A Wear OS recorder:
    - the sensor thread never blocks;
    - explicit hardware sensor selection;
    - a guided calibration mode;
    - hash-verified transfer, where the watch deletes its copy only after the phone confirms the SHA-256;
    - orphan recovery.
  - A phone app: session control, rep picker, review plot, label editing, export.
- **A Python reader** feeding the CSF-1 layer.
- **JSON Schemas,** validated in both languages.
- **Android CI** (tests, lint, both APKs), verified in a Linux container before its first GitHub run, which passed.

### Lessons
- Design data collection around the evaluation you'll need later.
- Contracts shared across parallel work should be written first, by one person.

---

## Part 5 — What only real hardware could tell me (2026-10-22)

### The devices
- **A Galaxy Watch5 Pro** (Wear OS 5) with an **STMicro LSM6DSO** IMU, at 6.25–100 Hz.
- **A Galaxy S25 Ultra** (Android 17).
- **A trap:** two sensors answer to "gyroscope", the real chip and a software "Corrected Gyroscope". Selection had to be explicit and logged.

### Bug 1: the apps couldn't see each other
- The phone never showed "Connected".
- The Wear OS Data Layer only connects apps with the **same package name and signing key**, and my spec had given them different ids.
- Unit tests with fake transports could never catch that. Fixed, spec corrected.

### Bug 2: the Samsung Freecess saga
1. **The first real session had 7 gaps,** up to 22.6 s, inside labeled sets.
2. **Lining up gap times with the watch's system log** showed Samsung's Freecess power manager freezing our process every 30–45 s and disabling its wake lock, despite a health foreground service.
3. **First hypothesis: battery optimization.** Allow-listing the app made it *worse*: frozen 133 s out of 152. Disproved, reverted.
4. **Blocked all data collection** rather than collect bad data.
5. **Built a debug-only adb hook** to run tests unattended, then tested four fixes as a matrix: Ongoing Activity API, ambient mode, a Health Services exercise session, and keeping the screen on.
   - The process-state dumps showed the pattern: Samsung freezes anything that isn't the top app, *including* foreground services.
   - The exercise session didn't help because it runs in Samsung's process, not ours.
6. **Keeping the recording screen on keeps the app on top.** On the wrist: **290 s at 49.85 Hz, zero gaps.**

### More from real use
- **A phone-started session failed when the watch app wasn't open,** because Android blocks background apps from starting foreground services. Fix: the phone opens the watch app remotely, then starts the session. Verified with the app closed.
- **Sets started from the watch inherited the phone's exercise selection,** so my squat got logged as OHP. The phone now asks for confirmation.
- **Accidental double taps** created 0.7 s segments. The fix is a 1 s debounce, plus "Discard (accidental)" offered for anything under 3 s.
- **"The screen isn't dimmed".** The logs showed the dimming *was* applied, but the room was so dark auto-brightness was already at minimum. When dimming did show, the text was barely readable. Readability won; battery tuning moved to pre-launch.
- **Real timing was far better than simulated:** 49.8 Hz with about 0.03 ms jitter, and sensors in sync. The fake watch had assumed 100× worse.

### Lessons
- Test on real hardware early. Fakes can't validate platform rules.
- Correlate symptoms with system logs, and treat every hypothesis as something to disprove.
- Stop collecting data rather than collect bad data.

---

## Part 6 — Proving the sensor assumptions (2026-10-23)

### What I did
- **Designed a 5-minute guided calibration:** 2 minutes flat on a table, six static poses, three slow rotations.
- **Tested every assumption against physics:**

| Check | Result |
|---|---|
| Units | |a| at rest = 0.989 g (gate 1.00 ± 0.03) |
| Axes | all six poses put gravity on the expected signed axis, so the frame is right-handed and as Android documents |
| Gyro sign and units | integrating the gyroscope reproduces the gravity change the accelerometer measured, within 2–3°. Flipping any single axis gives 16–74° error, and a unit error would be off by 57× |
| Gyro bias | 0.11 deg/s |
| Noise | accelerometer about 3.5 mg |

- **A minor finding:** a −0.03 g accelerometer offset on z. That's under 2° of tilt, inside the model's 15° tolerance, so it's noted rather than corrected.

### A human error, handled with data
I misread one rotation step and did mostly the wrong axis. Instead of re-recording, I checked whether the x-axis sign was still identifiable from the motion that *was* recorded. It was: 2.0° error as-is vs 66.9° with x flipped.

### Lessons
- Physics makes a great test oracle.
- Before throwing away imperfect data, check whether it still answers the question.

---

## Part 7 (in progress) — Getting ready for real gym data (2026-10-23)

### What I did before the first gym session
- **Wrote the evaluation criteria before collecting the data.** The Phase 3 spec defines:
  - how a watch session becomes a verdict;
  - how the watch-to-training axis rotation is fitted (weighted Kabsch on per-exercise gravity vectors, determinant constrained, with a mirror check that would flag a wrong-wrist assumption);
  - the pass/fail gates with Wilson confidence intervals;
  - a "not a lift" threshold tuned only on session 1;
  - an explicitly labeled in-sample mode for a first look the same evening.
- **Built the harness** (`workout_ml.watch`). It recovered a known synthetic rotation within 0.07°, and it reproduces the U1 calibration numbers exactly.
- **The spec was wrong once, and the implementer caught it.** I'd specified a "smallest singular value" conditioning warning, but real gravity data legitimately has one small singular value (0.30). It was replaced with a physically meaningful "weakest-axis lever" check.
- **Wrote a printable gym protocol:**
  - 20 sets per session in exercise blocks, with the order swapped in session 2;
  - five "other" periods for negative examples;
  - reps counted aloud.
- **Versioned the calibration data in DVC** as `watch-calib-v1`.

## Part 8 (started early) — A PyTorch model that ties the classical best (2026-10-23)
- **The model:** a small 1D-CNN (three conv blocks, about 4 s windows of 8 channels).
- **Training:** each sample gets a new random rotation every epoch (on-the-fly augmentation), with early stopping on a held-out *participant*, never a random split.
- **LOPO result:** set accuracy **0.986 ± 0.032**, macro-F1 0.959. That ties the augmented KNN; the fold std (about 0.05) says the two are indistinguishable on this data.
- **A subtle bug, caught before reporting:** the first early-stopping rule sometimes held out the only other participant with `rest` data, so the model never saw that class (macro-F1 fell to 0.83). The fix: the validation participant may never be the sole holder of a class.
- **ONNX export:** matches PyTorch to 1.3e-6. Preprocessing (channel order, normalization, classes) ships as JSON next to the model, so serving doesn't need PyTorch.
- **Kept CI light:** PyTorch lives in an optional extra with its own CPU-only lock, and tests skip cleanly without it.
- **An honest comparison caveat:** the CNN can't predict the first 4 s of a set, so window-level metrics cover fewer windows than the classical models. Set-level numbers are the fair comparison.

## Part 9 (started) — The first service: contract-first Spring Boot (2026-10-23)
- **Contracts first,** written by me before any service code:
  - an OpenAPI spec (17 operations);
  - JSON Schemas for the two RabbitMQ events (`session.uploaded`, `session.analyzed`);
  - the database design.

  Two services in two languages build against them.
- **core-api design:**
  - phone → presigned upload straight to object storage → a "complete" call → a **transactional outbox**, so a crash can't lose or duplicate the upload event;
  - Argon2id passwords, with a dummy-hash comparison so login timing doesn't reveal which emails exist;
  - RS256 access tokens plus **rotating refresh tokens with reuse detection**;
  - RFC 9457 errors;
  - another user's data returns **404**, proven with IDOR tests.
- **Tests:** 61, 35 of them against real PostgreSQL, RabbitMQ and MinIO via Testcontainers. The image is built on Red Hat UBI, runs as non-root, and starts healthy.
- **A real-world snag:** the `minio/minio` image had disappeared from Docker Hub, and the anonymous-pull alternative needed different permissions.

### The Python inference service (V2)
- **No train/serve skew by construction:** the service doesn't re-implement inference. It calls `analyze_session`, the same function the Phase 3 verdict uses, and a test proves the two agree segment by segment.
- **Failure design before happy path:** a permanent error (bad hash, bad zip, unknown watch, missing object) becomes a `failed` event straight away; anything else retries through 5 s / 15 s / 45 s delay queues and then lands in a dead-letter queue *and* emits `failed`, so the phone never waits forever.
- **A RabbitMQ subtlety:** per-message expiry only applies at the head of a queue, so mixed delays in one queue would block each other. One queue per delay avoids it.
- **Idempotent by design:** the result event's id is derived from the upload event's id, so a redelivered message produces an identical event that core-api ignores.
- **Tests:** 36, four of them against real RabbitMQ and MinIO containers, including actual TTL retries ending in the DLQ. The UBI image starts healthy and reports the loaded model's version (git SHA + training-data hash).

### The whole stack on one command (V3)
- `docker compose up --build --wait` brings up PostgreSQL (with pgvector for the coach later), RabbitMQ, MinIO, core-api and inference, all healthy, with one-shot jobs for the bucket and the model.
- **An end-to-end suite in Java (REST Assured)** plays the phone: register, log in, create a session, upload the golden recording straight to object storage, complete it, wait for the analysis to come back over RabbitMQ, edit labels with optimistic locking, rotate refresh tokens, prove another user gets 404, delete the account. 10 tests, about 7 s.
- **A subtle networking point:** a presigned URL is only valid for the host it was signed for, so the backend signs for the address the *phone* uses, not the internal container name.
- **CI without the private data:** the training data lives in DVC, so CI trains a deliberately labeled synthetic model just to exercise the plumbing.

### Closing the loop on the phone (V4)
- **The phone speaks the same contract:** its API client is generated from the OpenAPI file at build time, and a test fails if the contract and client ever drift.
- **Uploads that survive real life:** a background job uploads each session after the watch hands it over, through Wi-Fi drops and app kills, and never sends a finished session twice. The raw file goes byte for byte; labels go separately and only when they change.
- **Token handling done carefully:** one refresh no matter how many requests fail at once, sign-out only when the server actually refuses, and the refresh token encrypted with a key held in the phone's hardware keystore.
- **First full loop:** against the local stack, the client's own upload code took the golden recording from registration to ANALYZED. With a synthetic model the predictions are meaningless; the point was the plumbing.
- **Still to do by hand:** the same flow on the real phone over Wi-Fi.

## Housekeeping worth telling
- **Renamed the GitHub repo to Proprio** and rewrote the README around the product.
- **Made the interview notes private.** That meant learning the hard way that `.gitignore` doesn't untrack a committed file.
  - I purged it from history in a separate clone, so in-progress work wasn't disturbed.
  - I verified the rewritten branch tips matched the originals except for the file.
  - I force-pushed with leases. The lease caught a concurrent README edit, which was checked before anything was overwritten.

## Upcoming parts (to be written as they happen)
- **Part 7:** U2 gym sessions, fitting the watch-to-training rotation (Kabsch on per-exercise gravity vectors), and the domain-shift report against the pre-set gates, with bench/OHP as the canary.
- **Part 8:** PyTorch 1D-CNN with on-the-fly rotation augmentation, ONNX export, the MLflow registry, and the rep-counter rework.
- **Part 9:** Microservices: Spring Boot, FastAPI, PostgreSQL, RabbitMQ, Docker on Red Hat UBI.
- **Part 10:** The LLM coach: Granite on watsonx.ai, RAG with pgvector, tool calls, evaluation in MLflow.
- **Part 11:** CI/CD to IBM Cloud, security review, battery tuning (ambient mode, brightness), launch.
