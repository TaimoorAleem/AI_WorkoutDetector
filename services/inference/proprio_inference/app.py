"""FastAPI app: /health, /metrics, and (dev/test only) POST /v1/analyze.

By default the AMQP worker runs inside this process as a daemon thread, so one container has one
model in memory and one ``/health``. ``python -m proprio_inference.worker`` runs the worker alone.
"""

from __future__ import annotations

import logging
import tempfile
import threading
import time
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI, File, HTTPException, Response, UploadFile
from workout_ml.watch.predict import ModelBundle, load_model

from proprio_inference import __version__, metrics
from proprio_inference.broker import Worker, probe_broker
from proprio_inference.config import Settings
from proprio_inference.processor import Analyzer, PermanentError, Processor
from proprio_inference.storage import S3Storage, Storage

logger = logging.getLogger(__name__)
PROBE_TTL_S = 5.0


class Service:
    """Everything the process needs, assembled once: model, analyzer, processor, worker."""

    def __init__(
        self,
        settings: Settings,
        *,
        storage: Storage | None = None,
        model: ModelBundle | None = None,
    ):
        self.settings = settings
        self.storage = storage or S3Storage(settings)
        self.model: ModelBundle | None = model
        self.model_error: str | None = None
        if self.model is None:
            try:
                if not settings.model_uri:
                    raise ValueError("MODEL_URI is not set")
                self.model = load_model(settings.model_uri)
            except Exception as e:  # report through /health instead of crashing the container
                self.model_error = f"{type(e).__name__}: {e}"
                logger.error("model not loaded: %s", self.model_error)
        self.analyzer = Analyzer(self.model, settings) if self.model else None
        self.processor = Processor(self.analyzer, self.storage, settings) if self.model else None
        self.worker: Worker | None = None
        self._thread: threading.Thread | None = None
        self._probes: dict[str, tuple[float, bool]] = {}

    def start_worker(self) -> None:
        if self.processor is None or self.worker is not None:
            return
        self.worker = Worker(self.settings, self.processor)
        self._thread = threading.Thread(target=self.worker.run, name="amqp-worker", daemon=True)
        self._thread.start()

    def stop_worker(self) -> None:
        if self.worker is not None:
            self.worker.stop()
        if self._thread is not None:
            self._thread.join(timeout=5)

    def _cached(self, name: str, fn) -> bool:
        now = time.monotonic()
        hit = self._probes.get(name)
        if hit and now - hit[0] < PROBE_TTL_S:
            return hit[1]
        try:
            ok = bool(fn())
        except Exception:  # noqa: BLE001
            ok = False
        self._probes[name] = (now, ok)
        return ok

    def health(self) -> tuple[dict, bool]:
        """(body, healthy). Healthy means the model is loaded and, if embedded, the worker runs.
        Broker and storage are reported but do not fail liveness (they recover by themselves)."""
        if self.worker is not None:
            broker_ok = self.worker.connected
        else:
            broker_ok = self._cached("broker", lambda: probe_broker(self.settings))

        def storage_check():
            self.storage.check()
            return True

        storage_ok = self._cached("storage", storage_check)
        worker_alive = self._thread is not None and self._thread.is_alive()
        body = {
            "status": "ok" if self.model else "unhealthy",
            "version": __version__,
            "model": (
                {
                    "status": "loaded",
                    "name": self.model.name,
                    "version": self.model.version,
                    "uri": self.settings.model_uri,
                }
                if self.model
                else {"status": "error", "error": self.model_error}
            ),  # fmt: skip
            "broker": "up" if broker_ok else "down",
            "storage": "up" if storage_ok else "down",
            "worker": "disabled"
            if self.worker is None
            else ("running" if worker_alive else "stopped"),
            "tau": self.settings.tau,
        }
        healthy = self.model is not None and (self.worker is None or worker_alive)
        if self.model and (not healthy):
            body["status"] = "unhealthy"
        elif self.model and (not broker_ok or not storage_ok):
            body["status"] = "degraded"
        return body, healthy


def create_app(
    settings: Settings | None = None,
    *,
    storage: Storage | None = None,
    model: ModelBundle | None = None,
    start_worker: bool | None = None,
) -> FastAPI:
    settings = settings or Settings.from_env()
    service = Service(settings, storage=storage, model=model)
    run_worker = settings.worker_enabled if start_worker is None else start_worker

    @asynccontextmanager
    async def lifespan(app: FastAPI):
        if run_worker:
            service.start_worker()
        yield
        service.stop_worker()

    app = FastAPI(title="proprio inference", version=__version__, lifespan=lifespan)
    app.state.service = service

    @app.get("/health")
    def health(response: Response):
        body, healthy = service.health()
        response.status_code = 200 if healthy else 503
        return body

    @app.get("/metrics")
    def prometheus() -> Response:
        return Response(metrics.render(), media_type="text/plain; version=0.0.4")

    if settings.debug_endpoints:

        @app.post("/v1/analyze")
        def analyze(file: UploadFile = File(...), device_model: str = "SM-R920"):
            """Debug: analyze an uploaded session zip synchronously (no broker, no storage)."""
            if service.analyzer is None:
                raise HTTPException(503, f"model not loaded: {service.model_error}")
            with tempfile.TemporaryDirectory(prefix="proprio-analyze-") as tmp:
                path = Path(tmp) / "session.zip"
                with open(path, "wb") as out:
                    while chunk := file.file.read(1 << 20):
                        out.write(chunk)
                try:
                    return {
                        "status": "ok",
                        **_clean(service.analyzer.analyze_file(path, device_model)),
                    }
                except PermanentError as e:
                    raise HTTPException(422, {"code": e.code, "message": e.message}) from e

    return app


def _clean(payload: dict) -> dict:
    from proprio_inference.events import json_clean

    return json_clean(payload)
