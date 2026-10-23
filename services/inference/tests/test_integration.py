"""Testcontainers: real RabbitMQ and MinIO. Skipped (not failed) when no Docker daemon is reachable.

A real ``session.uploaded`` goes onto ``proprio.events``; the worker (a thread, as in the image)
consumes it, downloads from MinIO, and a ``session.analyzed`` arrives on a test queue bound to the
exchange. Playing core-api's part: the test declares the exchange and its own analyzed queue.
"""

from __future__ import annotations

import hashlib
import json
import time
import uuid

import boto3
import pika
import pytest
from botocore.config import Config
from support import GOLDEN_GYM, uploaded_event

from proprio_inference import events
from proprio_inference.app import Service
from proprio_inference.broker import connection_params, declare_topology
from proprio_inference.config import Settings

pytestmark = pytest.mark.integration

MINIO_IMAGE = "cgr.dev/chainguard/minio:latest"  # minio/minio is gone from Docker Hub
RABBIT_IMAGE = "rabbitmq:3-management"
BUCKET = "proprio-raw"
ACCESS, SECRET = "testaccess", "testsecret123"


def _docker_available() -> bool:
    try:
        import docker

        docker.from_env().ping()
        return True
    except Exception:  # noqa: BLE001
        return False


if not _docker_available():
    pytest.skip("no Docker daemon reachable", allow_module_level=True)

from testcontainers.core.container import DockerContainer  # noqa: E402
from testcontainers.core.waiting_utils import wait_for_logs  # noqa: E402


@pytest.fixture(scope="module")
def rabbit():
    c = DockerContainer(RABBIT_IMAGE).with_exposed_ports(5672)
    c.with_env("RABBITMQ_DEFAULT_USER", "proprio").with_env("RABBITMQ_DEFAULT_PASS", "proprio")
    with c:
        wait_for_logs(c, "Server startup complete", timeout=120)
        yield f"amqp://proprio:proprio@{c.get_container_host_ip()}:{c.get_exposed_port(5672)}/%2F"


@pytest.fixture(scope="module")
def minio():
    c = DockerContainer(MINIO_IMAGE).with_exposed_ports(9000)
    c.with_env("MINIO_ROOT_USER", ACCESS).with_env("MINIO_ROOT_PASSWORD", SECRET)
    c.with_command("server /data")
    with c:
        wait_for_logs(c, "API:", timeout=120)
        endpoint = f"http://{c.get_container_host_ip()}:{c.get_exposed_port(9000)}"
        s3 = boto3.client(
            "s3", endpoint_url=endpoint, aws_access_key_id=ACCESS, aws_secret_access_key=SECRET,
            region_name="us-east-1", config=Config(s3={"addressing_style": "path"}),
        )  # fmt: skip
        s3.create_bucket(Bucket=BUCKET)
        yield endpoint, s3


def make_settings(amqp, endpoint, **over) -> Settings:
    base = dict(
        app_env="test", amqp_url=amqp, s3_endpoint=endpoint, s3_bucket=BUCKET,
        s3_access_key=ACCESS, s3_secret_key=SECRET, retry_delays_ms=(300, 300, 300),
    )  # fmt: skip
    base.update(over)
    return Settings(**base)


class Rig:
    """Worker thread + a test consumer bound to proprio.events for session.analyzed."""

    def __init__(self, settings, model, test_queue):
        self.settings = settings
        self.test_queue = test_queue
        self.service = Service(settings, model=model)
        self.conn = pika.BlockingConnection(connection_params(settings))
        self.ch = self.conn.channel()
        declare_topology(self.ch, settings)  # the exchange exists before core would bind
        self.ch.queue_declare(test_queue, exclusive=True)
        self.ch.queue_bind(test_queue, settings.exchange, events.ROUTING_ANALYZED)

    def start(self):
        self.service.start_worker()
        deadline = time.monotonic() + 30
        while not self.service.worker.connected and time.monotonic() < deadline:
            time.sleep(0.1)
        assert self.service.worker.connected, self.service.worker.last_error

    def publish_uploaded(self, doc):
        self.ch.basic_publish(
            self.settings.exchange, events.ROUTING_UPLOADED, json.dumps(doc).encode(),
            pika.BasicProperties(content_type="application/json", delivery_mode=2),
            mandatory=True,
        )  # fmt: skip

    def next_analyzed(self, timeout_s=90) -> dict:
        deadline = time.monotonic() + timeout_s
        while time.monotonic() < deadline:
            method, _, body = self.ch.basic_get(self.test_queue, auto_ack=True)
            if method:
                doc = json.loads(body)
                assert events.validation_errors(doc, events.ANALYZED_SCHEMA) == []
                return doc
            time.sleep(0.2)
        raise AssertionError("no session.analyzed arrived")

    def dlq_depth(self) -> int:
        return self.ch.queue_declare(self.settings.dlq, passive=True).method.message_count

    def close(self):
        self.service.stop_worker()
        self.conn.close()


@pytest.fixture
def rig(rabbit, minio, model):
    endpoint, _ = minio
    r = Rig(make_settings(rabbit, endpoint), model, f"test.analyzed.{uuid.uuid4().hex[:8]}")
    r.start()
    yield r
    r.close()


def upload(minio, data: bytes) -> str:
    _, s3 = minio
    key = f"raw/{uuid.uuid4()}/{uuid.uuid4()}.zip"
    s3.put_object(Bucket=BUCKET, Key=key, Body=data, ContentType="application/zip")
    return key


def test_uploaded_to_analyzed_roundtrip(rig, minio):
    data = GOLDEN_GYM.read_bytes()
    doc = uploaded_event(data, key=upload(minio, data))
    rig.publish_uploaded(doc)
    ev = rig.next_analyzed()
    assert ev["status"] == "ok" and ev["caused_by"] == doc["event_id"]
    assert ev["segments"] and ev["model"]["name"] == "fixture-classifier"
    assert ev["event_id"] == events.analyzed_event_id(doc["event_id"])
    assert rig.dlq_depth() == 0


def test_hash_mismatch_over_the_wire(rig, minio):
    data = GOLDEN_GYM.read_bytes()
    doc = uploaded_event(data, key=upload(minio, data), sha=hashlib.sha256(b"x").hexdigest())
    rig.publish_uploaded(doc)
    ev = rig.next_analyzed()
    assert ev["status"] == "failed" and ev["error"]["code"] == "hash_mismatch"


def test_missing_object_over_the_wire(rig):
    data = GOLDEN_GYM.read_bytes()
    rig.publish_uploaded(uploaded_event(data, key="raw/none/none.zip"))
    assert rig.next_analyzed()["error"]["code"] == "object_missing"


def test_transient_failures_retry_through_ttl_queues_then_dlq(rabbit, model):
    # storage endpoint that refuses connections: every attempt is a transient error
    settings = make_settings(rabbit, "http://127.0.0.1:1")
    rig = Rig(settings, model, f"test.analyzed.{uuid.uuid4().hex[:8]}")
    try:
        rig.start()
        data = GOLDEN_GYM.read_bytes()
        doc = uploaded_event(data)
        t0 = time.monotonic()
        rig.publish_uploaded(doc)
        ev = rig.next_analyzed(timeout_s=60)
        assert ev["status"] == "failed" and ev["error"]["code"] == "internal"
        assert ev["session_id"] == doc["session_id"]
        assert time.monotonic() - t0 >= 0.9  # three 300 ms delay-queue hops happened
        deadline = time.monotonic() + 10
        while rig.dlq_depth() == 0 and time.monotonic() < deadline:
            time.sleep(0.2)
        assert rig.dlq_depth() == 1
    finally:
        rig.close()
