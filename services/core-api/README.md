# core-api

Proprio's Java core service: accounts and auth, session upload (presigned S3), labels, analysis results and history. Spring Boot 3.5, Java 21, Gradle (Kotlin DSL). Spec: [`docs/specs/phase5-services.md`](../../docs/specs/phase5-services.md) §3; contract: [`contracts/openapi/core-api.yaml`](../../contracts/openapi/core-api.yaml).

## Build, test, run

Needs Android Studio's JBR 21 (or any JDK 21) as `JAVA_HOME`. Docker is needed only for the integration tests and the image.

```bash
cd services/core-api
export JAVA_HOME="/c/Program Files/Android/Android Studio/jbr"    # Git Bash on Windows

./gradlew unitTest          # fast, no Docker
./gradlew test              # unit + Testcontainers integration tests (PostgreSQL, RabbitMQ, MinIO)
./gradlew build             # compile, spotlessCheck, all tests, bootJar
./gradlew spotlessApply     # auto-format (google-java-format)
```

Integration tests are tagged `integration` and skip themselves (they do not fail) when no Docker daemon is reachable.
First run pulls `pgvector/pgvector:pg16`, `rabbitmq:3-management` and a MinIO image.

Run locally with the `dev` profile against PostgreSQL, RabbitMQ and an S3 endpoint (see `.env.example` for every variable):

```bash
set -a; source .env; set +a      # created from .env.example
./gradlew bootRun --args='--spring.profiles.active=dev'
curl localhost:8080/health
# dev profile also serves /v3/api-docs and /swagger-ui.html
```

The `dev` profile generates an ephemeral RS256 key pair when `JWT_PRIVATE_KEY[_PATH]` is empty, and creates the bucket on start. Any other profile refuses to start without a key.

### Docker image

The build context is the **repository root** (the contract and the shared JSON schemas live outside this directory):

```bash
docker build -f services/core-api/Dockerfile -t proprio/core-api .
```

Stage 1 compiles with Gradle on `ubi9/openjdk-21`; stage 2 runs the jar on `ubi9/openjdk-21-runtime` as non-root uid 185 with a `/health` healthcheck. BuildKit applies `Dockerfile.dockerignore` (a root-level context needs it next to the Dockerfile).

## Layout

```
build.gradle.kts            plugins: spring boot, openapi-generator (interfaceOnly), spotless
src/main/resources/
  application*.yml          env-driven config; profiles dev, test
  db/migration/V1__init.sql Flyway: spec tables + outbox
src/main/java/dev/proprio/coreapi/
  api/ (generated)          build/generated: *Api interfaces + DTOs from the OpenAPI contract
  auth/                     register/login/refresh/logout, rotating refresh tokens, login rate limiter
  security/                 RS256 JWT issue/verify, key loading, SecurityFilterChain, CurrentUser
  session/                  create/complete/list, presigned URLs (StorageService, AWS SDK v2)
  labels/                   labels.json storage, schema validation, ETag/If-Match
  analysis/                 predictions, merged analysis, /v1/workouts history
  events/                   outbox + poller, AMQP topology, session.analyzed consumer, JSON schemas
  user/                     account: me, consent, export, delete
  web/                      RFC 9457 problem handling, /health
src/test/java/.../unit      no Docker: limiter, JWT, Argon2, refresh rotation/reuse, labels schema, scoping, contract coverage
src/test/java/.../integration  Testcontainers: auth, IDOR, upload+outbox, consumer, labels, account
```

## Design notes

- **Contract-first.** `openApiGenerateServer` generates the Spring interfaces and models into `build/generated`; controllers implement them. `ContractCoverageTest` fails if any `operationId` in the contract has no implementing controller method. The generator's one wart (it initialises the free-form `labels` property with `new HashMap<>()`) is patched in a `doLast` in `build.gradle.kts`.
- **Passwords: Argon2id** (`Argon2PasswordEncoder`, BouncyCastle), parameters m=19 MiB, t=2, p=1 (OWASP minimum), configurable. Memory-hard, so it resists GPU cracking better than BCrypt-12 at similar latency. Login compares against a dummy hash for unknown emails so response time does not reveal which emails exist.
- **Tokens.** Access: RS256 JWT, 15 min, validated by Spring's resource server. Refresh: opaque 256-bit, stored only as SHA-256, rotated on every use within a family; presenting an already-rotated token revokes the family (the revocation commits even though the request answers 401). Logout revokes the caller's family.
- **Rate limiting.** In-memory token bucket per email and per client IP on login (`remoteAddr`; `X-Forwarded-For` is deliberately not trusted). Single-instance only: move to a shared store before running replicas.
- **User scoping.** Every repository method takes the caller's user id; foreign ids are 404 (never 403). `UserScopingTest` asserts this structurally, `IdorIT` end to end. Deliberate exceptions: `POST /v1/sessions` with another user's session id answers 409 (the contract's conflict), and the `session.analyzed` consumer works by session id because the id comes from our own event.
- **Transactional outbox.** `complete` updates the session and inserts the `session.uploaded` row in one transaction. A poller publishes with publisher confirms and `mandatory`, so an unroutable message (no consumer queue bound yet) stays in the outbox and is retried instead of being lost. Delivery is at-least-once; consumers dedupe on `event_id`. The same outbox carries raw-object deletions for account deletion (`kind = S3_DELETE`).
- **Consumer.** `session.analyzed` from `core.session-analyzed` is validated against the event schema, deduped through `processed_events`, upserted into `segment_predictions` and flips the session to `ANALYZED` or `FAILED`. Poison messages go to `core.session-analyzed.dlq`. Core declares only its own queue; `inference-svc` must declare `inference.session-uploaded` bound to `session.uploaded`.
- **Presigned URLs.** PUT URLs pin `Content-Type: application/zip` and the exact `Content-Length`, expire in 15 min. `S3_ENDPOINT` (MinIO / IBM COS) with path-style addressing; `S3_PUBLIC_ENDPOINT` sets the host that gets signed when clients reach storage under a different name than the service does.
- **Account.** `DELETE /v1/me` removes all rows at once, queues raw-object deletion through the outbox, and leaves an anonymised tombstone user row (email freed). Status `ANALYZING` exists in the contract but nothing sets it yet.
