"""Event contracts: schema validation and construction of ``session.analyzed`` events.

The schemas in ``contracts/events/`` are the contract (owned by the spec, not edited here).
"""

from __future__ import annotations

import json
import math
import uuid
from datetime import UTC, datetime
from functools import lru_cache
from pathlib import Path

from jsonschema import Draft202012Validator, FormatChecker

UPLOADED_SCHEMA = "session-uploaded.schema.json"
ANALYZED_SCHEMA = "session-analyzed.schema.json"
ROUTING_UPLOADED = "session.uploaded"
ROUTING_ANALYZED = "session.analyzed"
# Fixed namespace: the analyzed event_id is a pure function of the event it answers, so a redelivery
# re-publishes the same event_id and core-api's dedupe on event_id makes it a no-op.
EVENT_NAMESPACE = uuid.UUID("6f1c3c0e-52b1-4a5b-9a0f-3f3a5e7d9b11")


class InvalidEvent(ValueError):
    """The incoming message is not a valid ``session.uploaded`` event."""


@lru_cache(maxsize=4)
def find_schema_dir(configured: str | None = None) -> Path:
    if configured:
        return Path(configured)
    for parent in Path(__file__).resolve().parents:
        candidate = parent / "contracts" / "events"
        if (candidate / UPLOADED_SCHEMA).is_file():
            return candidate
    raise FileNotFoundError("contracts/events not found; set EVENT_SCHEMA_DIR")


@lru_cache(maxsize=8)
def validator(name: str, schema_dir: str | None = None) -> Draft202012Validator:
    path = find_schema_dir(schema_dir) / name
    schema = json.loads(path.read_text(encoding="utf-8"))
    Draft202012Validator.check_schema(schema)
    return Draft202012Validator(schema, format_checker=FormatChecker())


def validation_errors(doc: dict, name: str, schema_dir: str | None = None) -> list[str]:
    errs = sorted(validator(name, schema_dir).iter_errors(doc), key=lambda e: list(e.path))
    return [f"{'/'.join(map(str, e.path)) or '<root>'}: {e.message}" for e in errs]


def parse_uploaded(body: bytes, schema_dir: str | None = None) -> dict:
    """Decode and validate a ``session.uploaded`` message body."""
    try:
        doc = json.loads(body)
    except (ValueError, UnicodeDecodeError) as e:
        raise InvalidEvent(f"not JSON: {e}") from e
    if not isinstance(doc, dict):
        raise InvalidEvent("not a JSON object")
    errors = validation_errors(doc, UPLOADED_SCHEMA, schema_dir)
    if errors:
        raise InvalidEvent("; ".join(errors[:5]))
    return doc


def analyzed_event_id(caused_by: str) -> str:
    return str(uuid.uuid5(EVENT_NAMESPACE, f"session.analyzed:{caused_by}"))


def json_clean(value):
    """Recursively convert numpy scalars and NaN/inf so the value is plain JSON."""
    if isinstance(value, dict):
        return {str(k): json_clean(v) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [json_clean(v) for v in value]
    if hasattr(value, "item") and not isinstance(value, (str, bytes)):
        value = value.item()
    if isinstance(value, float) and not math.isfinite(value):
        return None
    return value


def _envelope(caused_by: dict, status: str) -> dict:
    return {
        "schema_version": 1,
        "event_id": analyzed_event_id(caused_by["event_id"]),
        "occurred_at": datetime.now(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z"),
        "caused_by": caused_by["event_id"],
        "session_id": caused_by["session_id"],
        "status": status,
    }


def analyzed_ok(uploaded: dict, payload: dict) -> dict:
    """``payload``: model, profile_id, transform_id, recording_stats, segments."""
    return {**_envelope(uploaded, "ok"), **json_clean(payload)}


def analyzed_failed(
    uploaded: dict, code: str, message: str, *, retryable: bool, model: dict | None = None
) -> dict:
    event = {
        **_envelope(uploaded, "failed"),
        "error": {"code": code, "message": message[:1000], "retryable": retryable},
    }
    if model:
        event["model"] = model
    return event
