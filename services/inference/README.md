# inference

Proprio's Python inference service. It consumes `session.uploaded`, downloads the raw session zip from S3/MinIO, runs the shared `workout_ml` inference path (the same code as the Phase 3 harness, D-012) and publishes `session.analyzed`. Spec: [`docs/specs/phase5-services.md`](../../docs/specs/phase5-services.md) §4; contracts: [`contracts/events/`](../../contracts/events) (consumed as written, never edited here).

## Layout

```
proprio_inference/
  config.py      env-driven Settings (AMQP_URL, S3_*, MODEL_URI, TAU, PROFILE_MAP, ...)
  events.py      schema validation, deterministic session.analyzed event ids
  processor.py   one message in, one Outcome out (publish / retry / dead); no broker code
  broker.py      pika glue: topology, publisher confirms, consume loop, dispatch()
  storage.py     boto3 download (endpoint configurable)
  app.py         FastAPI: /health, /metrics, POST /v1/analyze (dev/test only)
  worker.py      standalone worker entrypoint
  train_model.py CLI: train and save the production candidate
tests/           fast tests (in-memory fakes) + Testcontainers tests (marker `integration`)
```

Inference itself lives in `workout_ml.watch.predict` (`analyze_session`, `ModelBundle`, `load_model`, `train_bundle`), with its own tests in `ml/tests/test_watch_predict.py`. The service only adds transport, retries and the event envelope.

## Setup, test, run

Python 3.11. From `services/inference`:

```bash
uv venv --python 3.11 .venv && source .venv/Scripts/activate        # Git Bash on Windows
uv pip install -r requirements-dev.lock
uv pip install --no-deps -e ../../ml -e .

ruff check . && ruff format --check .
pytest -m "not integration"      # fast: in-memory broker/storage, fixture model trained on synthetic data
pytest                           # + Testcontainers (RabbitMQ, MinIO); the integration module skips itself without Docker
```

The first `pytest` run trains the fixture model (about 30 s; no real data is used). Integration tests pull `rabbitmq:3-management` and `cgr.dev/chainguard/minio` (`minio/minio` is no longer on Docker Hub).

Train a model from the MetaMotion data (`dvc pull` first) and run the service:

```bash
python -m proprio_inference.train_model --out models/proprio-classifier   # ~2.5 min
set -a; source .env; set +a                                               # from .env.example
python -m proprio_inference                                               # API + worker, port 8000
curl localhost:8000/health
```

`train_model` trains M1 (KNN, `basic+derived+pca+temporal`, rotation augmentation D-018) through the harness's own `ModelBank` on all MetaMotion data and writes `model.joblib` plus `metadata.json` (name, version = git SHA + data md5, feature set, classes, default tau). `MODEL_URI` is that directory or an MLflow URI (`runs:/<id>/model`, `models:/proprio-classifier@production`; the MLflow model needs a signature naming the feature columns). The bundle is a trusted artifact: joblib deserialises with pickle, so never point `MODEL_URI` at an untrusted location.

`POST /v1/analyze` (multipart `file`, optional `?device_model=SM-R920`) analyses a zip synchronously for debugging. It exists only when `APP_ENV` is `dev` or `test`.

## Docker image

Build context is the **repository root**:

```bash
docker build -f services/inference/Dockerfile -t proprio/inference .
docker run --rm -p 8000:8000 -v "$PWD/services/inference/models:/models:ro" \
  -e AMQP_URL=... -e S3_ENDPOINT=... -e S3_ACCESS_KEY=... -e S3_SECRET_KEY=... proprio/inference
```

Two stages on `ubi9/python-311`: the first installs the locked dependencies, `workout_ml` and the service into a venv; the second copies the venv, runs as uid 1001, and has a `/health` healthcheck. The model is mounted (or fetched from MLflow), not baked in.

## Design

- **Process model.** One image, one process: uvicorn serves the API and the AMQP worker runs as a daemon thread inside it (`WORKER_ENABLED=true`, the default), so there is one model in memory and one `/health`. `python -m proprio_inference.worker` runs the worker alone for scaling it separately. Processing is CPU-bound and prefetch is 1, so a thread is enough; a second consumer is a second container.
- **Queues** (all durable; declared by this service):
  - `inference.session-uploaded`, bound to `proprio.events` with `session.uploaded`; dead-letters to the DLQ on a reject.
  - `inference.session-uploaded.retry.1..3`: no consumers. Queue-level TTL (5 s, 15 s, 45 s, from `RETRY_DELAYS_MS`) and a dead-letter route through the default exchange back to the main queue. TTL is per queue on purpose: RabbitMQ only expires the head of a queue, so mixed per-message TTLs in one queue would block each other.
  - `inference.session-uploaded.dlq`: exhausted and poison messages, kept for inspection. It is a plain queue, not bound to `proprio.events.dlx` (a fanout that core-api's DLQ is bound to), so the two never mix.
- **Flow** (`processor.py`): validate the event, download, verify SHA-256, `read_session`, choose the profile from `device_model` via `PROFILE_MAP`, classify and count reps via `analyze_session`, publish `session.analyzed` with publisher confirms and `mandatory`, then ack.
- **Failures.** Permanent errors publish a `failed` event and ack (no retry): `hash_mismatch`, `invalid_format`, `unsupported_device`, `object_missing`. Anything else (S3 down, a bug) is transient: it is re-published to the next delay queue with an `x-attempt` header, up to 3 times, then the message goes to the DLQ **and** an `internal` `failed` event is published, so core-api never leaves a session in `UPLOADED`. A message that is not a valid event goes to the DLQ (plus a `failed` event when it still carries a session and event id). If a publish itself fails (broker down, or `session.analyzed` unroutable because core-api's queue is not declared yet) the message stays unacked and is redelivered after reconnect.
- **Idempotency.** The `session.analyzed` `event_id` is a UUIDv5 of the `session.uploaded` `event_id`, and the analysis is deterministic, so a redelivery re-publishes the same event (only `occurred_at` differs) and core-api's dedupe on `event_id` makes it a no-op.
- **Segments.** Every non-discarded segment is reported, in time order, with `start_s`/`end_s` relative to the session start. An unlabeled gym mark is reported as `kind: "set"`. A segment is judged on its own windows whatever the user labelled it: `predicted_kind: "set"` (a lift, with `predicted_exercise`, `confidence` = the voted share of windows, `rep_count`) or `"rest"` (voted rest, or lift vote below `TAU`). Calibration segments and segments shorter than 6 s (30 bins) carry no prediction.
- **Profiles.** `PROFILE_MAP` maps `device_model` to a profile, with `|` fallbacks. The default `SM-R920=galaxy-watch5pro-fit-v1|galaxy-watch-prior-v0` uses the fitted profile once it exists and the prior until then; the event always carries the `profile_id` and `transform_id` actually used. A model not in the map, or with no loadable profile, is `unsupported_device`.
- **Tau.** `TAU` (default 0.6) is a placeholder until the Sunday U2 data sets the real value.

## Dependencies and locks

`pyproject.toml` depends on `workout-ml` (a `[tool.uv.sources]` path to `../../ml`) plus FastAPI, uvicorn, python-multipart, pika, boto3 and jsonschema. `requirements.lock` / `requirements-dev.lock` are universal uv locks that **exclude** `workout-ml` (pip cannot take a relative path from a lock); it is installed separately with `pip install ml/ --no-deps`, as the Dockerfile does. `ml-constraints.txt` pins the shared libraries (numpy, pandas, scikit-learn, scipy, mlflow, ...) to the versions in `ml/requirements-dev.lock`, so a pickled model loads in the image exactly as it was trained. Regenerate:

```bash
uv pip compile pyproject.toml --universal --python-version 3.11 -c ml-constraints.txt --no-emit-package workout-ml -o requirements.lock
uv pip compile pyproject.toml --universal --python-version 3.11 -c ml-constraints.txt --extra dev --no-emit-package workout-ml -o requirements-dev.lock
```

(`ml-constraints.txt` is the matching subset of `ml/requirements-dev.lock`; refresh it when that lock changes. Constraining the whole ml lock makes uv's universal resolver fail on the boto3/urllib3 fork.)
