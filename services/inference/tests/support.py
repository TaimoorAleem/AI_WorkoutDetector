"""Test doubles: in-memory storage and an in-memory broker with the real retry/DLQ semantics
(minus the TTL wait: a retry is redelivered immediately)."""

from __future__ import annotations

import hashlib
import json
import uuid
from collections import deque
from pathlib import Path

from proprio_inference import events
from proprio_inference.broker import ATTEMPT_HEADER, dispatch
from proprio_inference.storage import ObjectMissing

REPO = Path(__file__).resolve().parents[3]
GOLDEN_GYM = REPO / "protocol" / "golden" / "session_gym_v1.zip"
GOLDEN_CALIBRATION = REPO / "protocol" / "golden" / "session_calibration_v1.zip"


class FakeStorage:
    def __init__(self, objects: dict[str, bytes] | None = None):
        self.objects = dict(objects or {})
        self.failures: list[Exception] = []  # raised (one per download call) before serving
        self.always: Exception | None = None
        self.downloads = 0

    def download(self, key: str, dest: Path) -> None:
        self.downloads += 1
        if self.always is not None:
            raise self.always
        if self.failures:
            raise self.failures.pop(0)
        if key not in self.objects:
            raise ObjectMissing(key)
        Path(dest).write_bytes(self.objects[key])

    def check(self) -> None:
        if self.always is not None:
            raise self.always


class InMemoryBroker:
    """Publisher + a tiny consumer loop. Every published analyzed event is schema-validated."""

    def __init__(self, processor):
        self.processor = processor
        self.queue: deque[tuple[bytes, dict]] = deque()
        self.events: list[dict] = []
        self.dlq: list[tuple[bytes, dict]] = []
        self.retries: list[int] = []
        self.deliveries = 0

    # Publisher
    def publish_event(self, routing_key, body, message_id):
        assert routing_key == events.ROUTING_ANALYZED
        doc = json.loads(body)
        assert events.validation_errors(doc, events.ANALYZED_SCHEMA) == []
        assert doc["event_id"] == message_id
        self.events.append(doc)

    def publish_retry(self, attempt, body, headers):
        self.retries.append(attempt)
        assert headers[ATTEMPT_HEADER] == attempt
        self.queue.append((body, headers))

    def publish_dead(self, body, headers):
        self.dlq.append((body, headers))

    # consumer
    def deliver(self, body: bytes, headers: dict | None = None):
        self.queue.append((body, headers or {}))
        return self.drain()

    def drain(self, limit: int = 20):
        outcomes = []
        while self.queue:
            assert self.deliveries < limit, "runaway redelivery"
            body, headers = self.queue.popleft()
            self.deliveries += 1
            outcomes.append(dispatch(self.processor, self, body, headers))
        return outcomes


def uploaded_event(data: bytes, *, key="raw/u/s.zip", sha=None, device="SM-R920", **over) -> dict:
    doc = {
        "schema_version": 1,
        "event_id": str(uuid.uuid4()),
        "occurred_at": "2026-10-23T10:00:00Z",
        "session_id": str(uuid.uuid4()),
        "user_id": str(uuid.uuid4()),
        "object_key": key,
        "sha256": sha or hashlib.sha256(data).hexdigest(),
        "size_bytes": max(len(data), 1),
        "raw_format": 1,
        "kind": "gym",
        "device_model": device,
        "training_consent": False,
    }
    doc.update(over)
    return doc


def body_of(doc: dict) -> bytes:
    return json.dumps(doc).encode()
