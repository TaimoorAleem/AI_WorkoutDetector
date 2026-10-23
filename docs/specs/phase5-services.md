# Spec: Phase 5 — services MVP (core-api, inference-svc, local stack)

> Owner: orchestrator. Implementation: Sonnet subagents (tasks V1–V4, §8). Status: approved for implementation.
> Contracts (source of truth, contract-first):
> - REST: [`contracts/openapi/core-api.yaml`](../../contracts/openapi/core-api.yaml)
> - Events: [`contracts/events/session-uploaded.schema.json`](../../contracts/events/session-uploaded.schema.json) and [`session-analyzed.schema.json`](../../contracts/events/session-analyzed.schema.json)
> - Recording format: [`protocol/schema/`](../../protocol/schema/) (Raw Format v1, unchanged)
>
> Context: [`PROJECT.md`](../PROJECT.md) §4, §5, §9; decisions D-002, D-003, D-012–D-015, D-019.

## 1. Goal
A phone uploads a finished watch session. The backend stores it, analyzes it asynchronously, and returns per-segment exercise predictions and rep counts, merged with the user's own labels. The whole thing runs locally with `docker compose up` and is covered by automated end-to-end tests.

**Not in Phase 5:** the coach (Phase 6), cloud deployment (Phase 7) and personalization (Phase 8). The phone app gets its upload integration in task V4.

## 2. Architecture (local)
```
 phone ──HTTPS/REST+JWT──► core-api (Java 21, Spring Boot) ──JDBC──► PostgreSQL 16 (+pgvector image)
   │                          │  presigns PUT/GET           │
   │ PUT zip (presigned) ─────┼──────────────► MinIO (S3 API; IBM COS in the cloud)
   │                          │ AMQP publish session.uploaded            ▲ GET zip
   │                          ▼                                          │
   │                     RabbitMQ ── queue ──► inference-svc (Python 3.11, FastAPI + worker)
   │                          ▲                  read_session → to_csf → model → reps
   │                          └── session.analyzed ◄─┘
   └──── GET /analysis, /workouts ◄── core-api stores predictions
```
- **The event names follow the session design** (D-019): `session.uploaded` / `session.analyzed`. They replace the `set.*` names in early drafts of PROJECT.md §4.
- **Raw zips are stored unmodified** in object storage, under `raw/<user_id>/<session_id>.zip`. They're never transformed in place (D-012).

## 3. core-api (`services/core-api/`)
**Stack:** Java 21, Spring Boot 3.x (latest stable that resolves), **Gradle (Kotlin DSL)** with its own wrapper.
- Spring Web, Validation, Security (resource server), Data JPA.
- Flyway; the PostgreSQL driver; Spring AMQP; AWS SDK v2 S3, used for MinIO and COS presigning.
- springdoc for a runtime OpenAPI view.

**Contract-first.** The `openapi-generator` Gradle plugin generates the Spring interfaces and DTOs (`interfaceOnly`) from `contracts/openapi/core-api.yaml`; controllers implement them. A test asserts that every operation in the contract has an implementation.

**Database** (Flyway migrations `V1__init.sql` …):

| table | key columns |
|---|---|
| `users` | `id uuid pk`, `email citext unique`, `password_hash`, `participant_id`, `created_at`, `deleted_at` |
| `refresh_tokens` | `id`, `user_id fk`, `family_id`, `token_hash` (SHA-256, never the raw token), `expires_at`, `rotated_at`, `revoked_at` |
| `consents` | `id`, `user_id fk`, `training_use`, `recorded_at` (append-only history; current = latest) |
| `devices` | `id`, `user_id fk`, `manufacturer`, `model`, `sdk_int`, `first_seen_at` (unique per user+model) |
| `sessions` | `session_id uuid pk` (watch-generated), `user_id fk`, `device_id fk`, `kind`, `status`, `started_at`, `duration_s`, `object_key`, `sha256`, `size_bytes`, `raw_format`, `created_at`, `uploaded_at`, `analyzed_at`, `failure_reason` |
| `labels` | `session_id pk fk`, `document jsonb`, `version int` (ETag), `updated_at` |
| `segment_predictions` | `(session_id, segment_id, model_version)` pk, `kind`, `start_s`, `end_s`, `predicted_exercise`, `predicted_kind`, `confidence`, `rep_count`, `model_name`, `profile_id`, `transform_id`, `created_at` |
| `processed_events` | `event_id uuid pk`, `processed_at` (idempotent consumers) |

- **Every repository query is scoped by `user_id`.** Another user's resource returns **404**, never 403.
- `GET /v1/workouts` is built from `labels` (the user's truth) with `segment_predictions` as fallback, and `LoggedSet.source` says which one was used.

**Auth**
- Passwords are hashed with **Argon2id** (Spring Security `Argon2PasswordEncoder`, with Bouncy Castle) or **BCrypt cost 12**. Document the choice.
- Access tokens are **JWTs signed RS256**, lifetime 15 min. The key pair comes from files or env, never from the repo. A dev keypair is generated at startup when the `dev` profile is active and no key is configured.
- Refresh tokens are opaque random 256-bit values, lifetime 30 days, **rotated on every use**. Reusing a rotated token revokes the whole family.
- Login is rate-limited per email and per IP, with an in-memory token bucket that's fine for the MVP.
- Errors use **RFC 9457 problem details**. Never leak whether an email exists: login gives a generic 401, and register's 409 is acceptable.

**Upload flow**
1. `POST /v1/sessions` validates the metadata (size ≤ 50 MB, `raw_format` 1, `sha256` format), upserts the device, creates `sessions` with status `CREATED`, and returns a presigned PUT URL. The URL expires in 15 min and requires `Content-Type: application/zip`.
2. The client PUTs the zip directly to object storage.
3. `POST /complete` HEADs the object and checks that its size matches, then sets status `UPLOADED` and publishes `session.uploaded` (JSON validated against the schema in tests). The DB update and the publish use a **transactional outbox**: an `outbox` table plus a poller, so a crash between the two never loses or duplicates an event. The outbox table is added to the schema.
4. Consuming `session.analyzed`:
   - idempotent via `processed_events`;
   - upsert into `segment_predictions`;
   - status becomes `ANALYZED`, or `FAILED` with the reason.

**Labels.** `PUT /labels` validates the body against `protocol/schema/labels.schema.json`, using the networknt validator as `:protocol` does. It applies `If-Match`/`ETag` optimistic concurrency, and its `session_id` must match the path.

**Account.**
- `DELETE /v1/me` deletes the rows and enqueues deletion of the raw objects.
- `GET /v1/me/export` returns the user's data plus presigned GET URLs.

**Config.** Spring profiles `dev` and `test`; everything else comes from env vars, and no secrets are committed.

## 4. inference-svc (`services/inference/`)
**Stack:** Python 3.11, FastAPI (health and metrics endpoints, plus `POST /v1/analyze` for synchronous debugging, disabled outside `dev`), **pika** or **aio-pika** for RabbitMQ, boto3 for S3. It depends on the `workout_ml` package, installed from `ml/`, so **one code path** serves training and inference (D-012).

**Worker**
1. Consume `session.uploaded` from queue `inference.session-uploaded`: durable, manual ack, prefetch 1. Validate it against the event schema.
2. Download the zip and **verify the SHA-256**. On a mismatch, publish `failed` with `hash_mismatch` (not retryable).
3. Read it with `read_session`, choose the device profile by `device_model` (a mapping in config; an unknown model publishes `unsupported_device`), and convert each segment with `to_csf`.
4. Classify set segments, and rest/other segments for rejection, with the same feature pipeline and the same vote and τ logic as the Phase 3 harness (`workout_ml.watch`). Count reps with `workout_ml.reps.count_set`.
5. Publish `session.analyzed` with `model {name, version, uri}`, `profile_id`, `transform_id`, `recording_stats` and per-segment results, then ack.

**Retries.** Transient errors are retried 3× with exponential backoff, via per-message TTL delay queues. After that the message goes to the **dead-letter queue** `inference.session-uploaded.dlq`. A DLQ'd message still produces a `failed` event, so the session never hangs in `UPLOADED`.

**Idempotency.** Redelivery of the same `event_id` re-publishes the same results; core-api dedupes them.

**Model loading**
- `MODEL_URI` is an MLflow model URI (`runs:/…/model` now, `models:/proprio-classifier@production` once the registry exists in Phase 4/7), or a local path for tests.
- The model is loaded once at startup, and its version is reported in `/health`.
- A tiny **test model** fixture (trained on synthetic data in a test) keeps CI free of real data.

## 5. Local stack (`deploy/compose.yaml`)
- **Services:**
  - `postgres` (`pgvector/pgvector:pg16`; pgvector arrives now for Phase 6);
  - `rabbitmq` (`rabbitmq:3-management`, with exchange and queues declared by the services);
  - `minio` (plus a `createbuckets` init job);
  - `core-api`;
  - `inference`;
  - optionally `mlflow` (local tracking server pointing at Postgres and MinIO), behind a profile.
- **Images:**
  - **Red Hat UBI** bases: `registry.access.redhat.com/ubi9/openjdk-21-runtime` for core-api (Gradle builds the jar in a builder stage) and `ubi9/python-311` for inference;
  - non-root;
  - healthchecks;
  - multi-stage;
  - a `.dockerignore`.
- `docker compose up --build` brings everything up healthy. A seed script isn't needed; the E2E test registers its own user.

## 6. Tests
- **core-api** (JUnit 5):
  - unit tests for auth (token rotation, reuse detection), validation, and user scoping (IDOR tests: user B can't read, label or complete user A's session, and gets 404);
  - **Testcontainers** integration tests (PostgreSQL, RabbitMQ, MinIO) for upload → complete → outbox publish, and consuming `session.analyzed` idempotently;
  - a contract test that the generated interfaces cover every operation, plus JSON-schema validation of the published events.
- **inference-svc** (pytest):
  - the worker on the golden session zip with a fixture model;
  - SHA-256 mismatch;
  - an unknown device;
  - retries going to the DLQ (with a fake broker or Testcontainers RabbitMQ if Docker is available; skip cleanly otherwise);
  - output events validated against `session-analyzed.schema.json`.
- **E2E** (`services/e2e/`, **REST Assured + JUnit 5**, Gradle), against `docker compose`:
  1. register;
  2. login;
  3. create the session with the golden gym zip's metadata;
  4. PUT the zip to the presigned URL;
  5. complete;
  6. poll the analysis until `ANALYZED` (timeout 60 s);
  7. assert the segments, then PUT labels and see them reflected in `/v1/workouts`;
  8. refresh-token rotation;
  9. cross-user 404;
  10. delete the account.
- **CI:** `services-ci.yml` runs the core-api and inference unit/integration tests on push/PR (Testcontainers works on ubuntu runners), plus a compose-based E2E job. Lint: Spotless or Checkstyle for Java, ruff for Python. That's task V3.

## 7. Security checklist (PROJECT.md §9, the MVP subset)
- [ ] No secrets in git; `.env.example` only.
- [ ] Argon2id or BCrypt-12 password hashing.
- [ ] RS256 JWTs with short lifetimes.
- [ ] Refresh rotation with reuse detection.
- [ ] User-scoped queries, 404 on foreign ids, and IDOR tests.
- [ ] Upload size limit and content type enforced at presign.
- [ ] The SHA-256 verified server-side.
- [ ] Labels schema-validated.
- [ ] Problem details with no stack traces.
- [ ] Login rate limiting.
- [ ] Non-root UBI images.
- [ ] Dependency scanning comes in Phase 7.

## 8. Tasks
| id | who | depends on | scope |
|---|---|---|---|
| — | orchestrator | — | this spec + contracts (done) |
| V1 | Sonnet | contracts | core-api (§3, §6 core-api tests), its Dockerfile |
| V2 ‖ V1 | Sonnet | contracts; H1 merged (`workout_ml.watch`) | inference-svc (§4, §6 inference tests), its Dockerfile |
| V3 | Sonnet | V1 + V2 | compose stack (§5), E2E (§6), `services-ci.yml` |
| V4 | Sonnet | V3 | phone app: login, upload after transfer, analysis/history screens, generated client from the OpenAPI contract |

## 9. Acceptance
1. `docker compose -f deploy/compose.yaml up --build` gives all services healthy on Windows (Docker Desktop) and Linux.
2. The E2E suite passes against the stack: upload → analyzed in under 60 s locally, with the golden session's segments analyzed.
3. Killing the inference container mid-processing, then restarting it, still ends in `ANALYZED` (redelivery). A poison message ends in `FAILED` via the DLQ.
4. All unit and integration tests pass, plus the CI workflow (V3).
