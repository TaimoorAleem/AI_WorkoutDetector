# deploy

The local Proprio stack: PostgreSQL (pgvector), RabbitMQ, MinIO, core-api and the inference service. Spec: [`docs/specs/phase5-services.md`](../docs/specs/phase5-services.md) section 5.

```bash
docker compose -f deploy/compose.yaml up --build -d --wait     # from the repo root; first build takes minutes
docker compose -f deploy/compose.yaml ps
curl localhost:8080/health          # core-api (dev profile: also /swagger-ui.html)
curl localhost:8000/health          # inference (model name and version)
docker compose -f deploy/compose.yaml down -v                  # stop and delete all data
```

No setup is needed: every credential has a dev-only default. `deploy/.env.example` lists the overrides (copy it to `deploy/.env`, which is gitignored). Ports are bound to `127.0.0.1` only: core-api 8080, inference 8000, MinIO 9000 (console 9001), RabbitMQ management 15672.

E2E suite against the running stack: `cd services/e2e && ./gradlew e2eTest` (see `services/e2e/`).

## Services

| service | image | notes |
|---|---|---|
| `postgres` | `pgvector/pgvector:pg16` | healthcheck `pg_isready` |
| `rabbitmq` | `rabbitmq:3-management` | healthcheck `rabbitmq-diagnostics ping`; the services declare their own queues |
| `minio` | `cgr.dev/chainguard/minio` | distroless, uid 65532, **no healthcheck** (no shell or client in the image); `createbuckets` waits for it |
| `createbuckets` | `cgr.dev/chainguard/minio-client` | one-shot, `restart: on-failure` until MinIO answers; alias via `MC_HOST_minio` |
| `model-init` | built from `services/inference` | one-shot, trains the synthetic model when none exists |
| `core-api` | built from `services/core-api` | UBI, uid 185, `/health` |
| `inference` | built from `services/inference` | UBI, uid 1001, `/health` |

Both builds use the repository root as context. `core-api` waits for healthy PostgreSQL and RabbitMQ and the bucket; `inference` for RabbitMQ, the bucket and `model-init`. (Third-party images start as root only to drop privileges themselves, as PostgreSQL and RabbitMQ do.) MLflow is deferred: nothing in Phase 5 needs it.

## Presigned URLs

core-api reaches MinIO as `http://minio:9000`, but a presigned URL is signed for a host and handed to the client, which is outside the compose network. So `S3_ENDPOINT` (internal, used by core-api to HEAD and by inference to download) differs from `S3_PUBLIC_ENDPOINT` (default `http://localhost:9000`, used only for signing). For a phone on your LAN set it to the PC's address, e.g. `http://192.168.1.20:9000`.

## Model

By default `model-init` runs `python -m proprio_inference.train_model --out /models/proprio-classifier --synthetic --if-missing` into the `model-data` volume. That model is trained on generated data and its metadata name is `synthetic-fixture`; it classifies nothing real (the golden session is synthetic too, so the E2E asserts structure, not labels). For a real model, train one (needs the MetaMotion data: `dvc pull`, then `python -m proprio_inference.train_model --out E:/models/proprio-classifier` in `services/inference`) and set `MODEL_DIR=E:/models` in `deploy/.env`; that host directory is mounted at `/models`. Recreate with `up -d --force-recreate inference`.

The default `PROFILE_MAP` adds `synthetic-watch=galaxy-watch-prior-v0` so the golden session (device model `synthetic-watch`) is analysable; real watches keep `SM-R920`.

## Keys and secrets

Nothing secret is committed. The `dev` profile of core-api generates an ephemeral RS256 key at startup, so access tokens do not survive a core-api restart. To use a fixed key, set `CORE_API_PROFILE=prod` and `JWT_PRIVATE_KEY` (PKCS#8 PEM, `\n` for newlines; `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`).

## Troubleshooting

`up` after a failed E2E run is fine, but a session left behind by a crashed run makes the next one answer 409 (the golden session id is fixed): `down -v` first.
