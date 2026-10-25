"""Worker flow against in-memory fakes: ok path, permanent failures, retries, DLQ, idempotency."""

from __future__ import annotations

import json
from pathlib import Path

import pytest
from support import GOLDEN_CALIBRATION, body_of, uploaded_event
from workout_ml.sensors import load_profile

from proprio_inference import events
from proprio_inference.processor import Action

EXPECTED = json.loads(
    (Path(__file__).resolve().parents[3] / "protocol" / "golden" / "expected.json").read_text()
)
LIFTS = {"bench", "squat", "dead", "ohp", "row"}


def put(storage, data, **over):
    doc = uploaded_event(data, **over)
    storage.objects[doc["object_key"]] = data
    return doc


def test_golden_gym_end_to_end(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes)
    [outcome] = broker.deliver(body_of(doc))
    assert outcome.action is Action.PUBLISH
    [ev] = broker.events
    assert events.validation_errors(ev, events.ANALYZED_SCHEMA) == []
    assert ev["status"] == "ok"
    assert ev["caused_by"] == doc["event_id"] and ev["session_id"] == doc["session_id"]
    assert ev["model"] == {"name": "fixture-classifier", "version": "fixture-1"}
    prior = load_profile("galaxy-watch-prior-v0")  # the fitted profile is not bundled yet
    assert ev["profile_id"] == prior.profile_id and ev["transform_id"] == prior.transform_id
    assert 11000 < ev["recording_stats"]["acc"]["n"] <= EXPECTED["gym"]["rows"]["acc"]
    ids = [s["segment_id"] for s in ev["segments"]]
    assert sorted(ids) == sorted(s["segment_id"] for s in EXPECTED["gym"]["segments"])
    for seg in ev["segments"]:
        assert seg["end_s"] > seg["start_s"] >= 0
        assert seg["predicted_kind"] in ("set", "rest")  # every golden segment is long enough
        assert 0 <= seg["confidence"] <= 1
        if seg["predicted_kind"] == "set":
            assert seg["predicted_exercise"] in LIFTS and seg["rep_count"] >= 0
        else:
            assert seg["predicted_exercise"] is None and seg["rep_count"] is None
    assert broker.dlq == [] and broker.retries == []


def test_calibration_session_has_no_predictions(broker, storage):
    doc = put(storage, GOLDEN_CALIBRATION.read_bytes(), kind="calibration")
    broker.deliver(body_of(doc))
    [ev] = broker.events
    assert ev["status"] == "ok" and ev["segments"]
    assert all(s["kind"] == "calibration" and "predicted_kind" not in s for s in ev["segments"])


def test_hash_mismatch_is_permanent(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes, sha="0" * 64)
    broker.deliver(body_of(doc))
    [ev] = broker.events
    assert ev["status"] == "failed" and ev["error"]["code"] == "hash_mismatch"
    assert ev["error"]["retryable"] is False
    assert broker.retries == [] and broker.dlq == [] and storage.downloads == 1


def test_unknown_device(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes, device="Pixel Watch 3")
    broker.deliver(body_of(doc))
    [ev] = broker.events
    assert ev["error"]["code"] == "unsupported_device" and ev["error"]["retryable"] is False
    assert broker.retries == [] and broker.dlq == []


def test_invalid_zip(broker, storage):
    doc = put(storage, b"this is not a zip file")
    broker.deliver(body_of(doc))
    [ev] = broker.events
    assert ev["status"] == "failed" and ev["error"]["code"] == "invalid_format"
    assert broker.retries == [] and broker.dlq == []


def test_zip_without_meta_is_invalid_format(broker, storage, tmp_path):
    import zipfile

    p = tmp_path / "x.zip"
    with zipfile.ZipFile(p, "w") as zf:
        zf.writestr("hello.txt", "hi")
    doc = put(storage, p.read_bytes())
    broker.deliver(body_of(doc))
    assert broker.events[0]["error"]["code"] == "invalid_format"


def test_object_missing(broker, storage, golden_gym_bytes):
    doc = uploaded_event(golden_gym_bytes)  # never stored
    broker.deliver(body_of(doc))
    [ev] = broker.events
    assert ev["error"]["code"] == "object_missing" and broker.dlq == []


def test_transient_error_is_retried_then_succeeds(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes)
    storage.failures = [ConnectionError("s3 down"), TimeoutError("slow")]
    broker.deliver(body_of(doc))
    assert broker.retries == [1, 2]
    assert [e["status"] for e in broker.events] == ["ok"]
    assert broker.dlq == []


def test_retries_exhausted_go_to_dlq_and_publish_failed(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes)
    storage.always = ConnectionError("s3 down")
    broker.deliver(body_of(doc))
    assert broker.retries == [1, 2, 3]  # three retries with growing attempt numbers
    assert broker.deliveries == 4
    [ev] = broker.events  # exactly one failed event, published when giving up
    assert ev["status"] == "failed" and ev["error"]["code"] == "internal"
    assert ev["session_id"] == doc["session_id"]
    [(dead_body, headers)] = broker.dlq
    assert json.loads(dead_body)["event_id"] == doc["event_id"]
    assert "giving up" in headers["x-dead-reason"]


def test_redelivery_is_idempotent(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes)
    broker.deliver(body_of(doc))
    broker.deliver(body_of(doc))  # e.g. ack lost, broker redelivers
    a, b = broker.events
    assert a["event_id"] == b["event_id"]
    drop = {"occurred_at"}
    assert {k: v for k, v in a.items() if k not in drop} == {
        k: v for k, v in b.items() if k not in drop
    }


def test_failed_event_ids_are_deterministic_too(broker, storage, golden_gym_bytes):
    doc = put(storage, golden_gym_bytes, sha="f" * 64)
    broker.deliver(body_of(doc))
    broker.deliver(body_of(doc))
    assert broker.events[0]["event_id"] == broker.events[1]["event_id"]


@pytest.mark.parametrize("mutate", [lambda d: d.pop("sha256"), lambda d: d.update(raw_format=2),
                                    lambda d: d.update(size_bytes=0)])  # fmt: skip
def test_invalid_event_with_ids_gets_failed_event_and_dlq(
    broker, storage, golden_gym_bytes, mutate
):
    doc = put(storage, golden_gym_bytes)
    mutate(doc)
    broker.deliver(body_of(doc))
    [ev] = broker.events
    assert ev["status"] == "failed" and ev["caused_by"] == doc["event_id"]
    assert len(broker.dlq) == 1 and storage.downloads == 0


@pytest.mark.parametrize("body", [b"not json", b"[]", b'{"event_id": "nope"}'])
def test_garbage_goes_to_dlq_only(broker, body):
    broker.deliver(body)
    assert broker.events == [] and len(broker.dlq) == 1


def test_oversized_object_is_rejected_without_download(broker, storage, golden_gym_bytes, settings):
    from dataclasses import replace

    broker.processor.settings = replace(settings, max_zip_bytes=10)
    doc = put(storage, golden_gym_bytes)
    broker.deliver(body_of(doc))
    assert broker.events[0]["error"]["code"] == "invalid_format" and storage.downloads == 0
