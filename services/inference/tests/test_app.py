"""HTTP surface: /health, /metrics, POST /v1/analyze (dev/test only)."""

from __future__ import annotations

import pytest
from fastapi.testclient import TestClient
from support import FakeStorage

from proprio_inference import events, metrics
from proprio_inference.app import create_app
from proprio_inference.config import Settings


@pytest.fixture
def client(model, settings):
    with TestClient(
        create_app(settings, storage=FakeStorage(), model=model, start_worker=False)
    ) as c:
        yield c


def test_health_reports_model_broker_storage(client, model):
    r = client.get("/health")
    assert r.status_code == 200
    body = r.json()
    assert body["model"]["status"] == "loaded"
    assert (body["model"]["name"], body["model"]["version"]) == (model.name, model.version)
    assert body["storage"] == "up" and body["worker"] == "disabled"
    assert body["broker"] in ("up", "down")  # no broker in this test
    assert body["status"] in ("ok", "degraded")


def test_health_503_without_model(settings):
    app = create_app(settings, storage=FakeStorage(), start_worker=False)  # MODEL_URI unset
    with TestClient(app) as c:
        r = c.get("/health")
    assert r.status_code == 503
    body = r.json()
    assert body["status"] == "unhealthy" and body["model"]["status"] == "error"


def test_health_degraded_when_storage_down(model, settings):
    storage = FakeStorage()
    storage.always = ConnectionError("down")
    with TestClient(create_app(settings, storage=storage, model=model, start_worker=False)) as c:
        r = c.get("/health")
    assert r.status_code == 200 and r.json()["storage"] == "down"
    assert r.json()["status"] == "degraded"


def test_analyze_golden(client, golden_gym_bytes):
    r = client.post("/v1/analyze", files={"file": ("s.zip", golden_gym_bytes, "application/zip")})
    assert r.status_code == 200, r.text
    body = r.json()
    assert body["status"] == "ok" and body["segments"] and body["profile_id"]
    # the debug response is the payload of a session.analyzed event
    event = {
        "schema_version": 1,
        "event_id": "00000000-0000-4000-8000-000000000001",
        "occurred_at": "2026-10-23T10:00:00Z",
        "caused_by": "00000000-0000-4000-8000-000000000002",
        "session_id": "00000000-0000-4000-8000-000000000003",
        **body,
    }
    assert events.validation_errors(event, events.ANALYZED_SCHEMA) == []


def test_analyze_errors(client, golden_gym_bytes):
    r = client.post("/v1/analyze", files={"file": ("s.zip", b"junk", "application/zip")})
    assert r.status_code == 422 and r.json()["detail"]["code"] == "invalid_format"
    r = client.post(
        "/v1/analyze?device_model=Nope",
        files={"file": ("s.zip", golden_gym_bytes, "application/zip")},
    )
    assert r.status_code == 422 and r.json()["detail"]["code"] == "unsupported_device"


def test_analyze_absent_in_prod(model):
    app = create_app(
        Settings(app_env="prod"), storage=FakeStorage(), model=model, start_worker=False
    )
    with TestClient(app) as c:
        assert c.post("/v1/analyze", files={"file": ("s.zip", b"x")}).status_code == 404
        assert c.get("/health").status_code == 200


def test_metrics_text(client):
    metrics.inc("analyzed")
    metrics.inc("failed", code="hash_mismatch")
    text = client.get("/metrics").text
    assert "proprio_inference_analyzed_total 1" in text
    assert 'proprio_inference_failed_total{code="hash_mismatch"} 1' in text
