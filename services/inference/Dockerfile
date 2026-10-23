# syntax=docker/dockerfile:1
#
# inference-svc image. The build context is the REPOSITORY ROOT (workout_ml, the event contracts and
# the shared JSON schemas live outside services/inference):
#
#   docker build -f services/inference/Dockerfile -t proprio/inference .
#
# Stage 1 installs the locked dependencies, workout_ml (from ml/) and this service into a venv.
# Stage 2 copies the venv onto a clean UBI Python image and runs as non-root (uid 1001).
# The model is not baked in: mount a directory written by `python -m proprio_inference.train_model`
# and point MODEL_URI at it (default /models/proprio-classifier), or use an MLflow URI.

FROM registry.access.redhat.com/ubi9/python-311:latest AS build
USER 0
WORKDIR /build
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH" PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1
# Locked third-party dependencies first (best layer caching). workout-ml itself is not in the lock.
COPY services/inference/requirements.lock services/inference/requirements.lock
RUN pip install -r services/inference/requirements.lock
# workout_ml's build hook copies protocol/schema into the wheel.
COPY protocol/schema/ protocol/schema/
COPY ml/pyproject.toml ml/setup.py ml/
COPY ml/workout_ml/ ml/workout_ml/
COPY services/inference/pyproject.toml services/inference/
COPY services/inference/proprio_inference/ services/inference/proprio_inference/
RUN pip install --no-deps ./ml ./services/inference

FROM registry.access.redhat.com/ubi9/python-311:latest
LABEL org.opencontainers.image.title="proprio inference" \
      org.opencontainers.image.description="Proprio inference service (FastAPI + AMQP worker)"
USER 0
COPY --from=build /opt/venv /opt/venv
# Same relative layout as the repo, so the service finds contracts/events by walking up.
COPY contracts/events/ /app/contracts/events/
RUN mkdir -p /models /tmp/mpl && chown -R 1001:0 /models /tmp/mpl && chmod -R g=u /models /tmp/mpl
USER 1001
WORKDIR /app
ENV PATH="/opt/venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    MPLCONFIGDIR=/tmp/mpl \
    EVENT_SCHEMA_DIR=/app/contracts/events \
    MODEL_URI=/models/proprio-classifier \
    APP_ENV=prod \
    PORT=8000
EXPOSE 8000
# /health is 200 once the model is loaded and the worker thread is alive; broker and storage are
# reported in the body ("degraded") but do not fail the check, so a slow RabbitMQ never flaps it.
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
    CMD python -c "import os,sys,urllib.request; sys.exit(0 if urllib.request.urlopen('http://localhost:%s/health' % os.environ.get('PORT','8000'), timeout=3).status == 200 else 1)"
ENTRYPOINT ["python", "-m", "proprio_inference"]
