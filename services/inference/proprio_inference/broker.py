"""RabbitMQ glue: topology, publishing, and the consume loop (pika, blocking, prefetch 1).

Topology (everything durable; this service owns these names, core-api owns ``core.*``):

    proprio.events (topic, declared by both services)
      --session.uploaded--> inference.session-uploaded     main queue; x-dead-letter -> .dlq
    inference.session-uploaded.retry.N   one per retry, queue-level TTL = backoff delay N,
                                         dead-letters through the default exchange back to the main queue
    inference.session-uploaded.dlq       poison and exhausted messages (kept for inspection)

Per-queue TTL (rather than per-message TTL) is deliberate: RabbitMQ only expires the *head* of a
queue, so mixed per-message TTLs in one queue would let a long delay block a short one.
"""

from __future__ import annotations

import json
import logging
import threading
from typing import Protocol

import pika
from pika.exceptions import AMQPError

from proprio_inference import events
from proprio_inference.config import Settings
from proprio_inference.processor import Action, Outcome, Processor

logger = logging.getLogger(__name__)

ATTEMPT_HEADER = "x-attempt"  # retries already made


class Publisher(Protocol):
    def publish_event(self, routing_key: str, body: bytes, message_id: str) -> None: ...

    def publish_retry(self, attempt: int, body: bytes, headers: dict) -> None:
        """Send to the delay queue of the ``attempt``-th retry (1-based)."""

    def publish_dead(self, body: bytes, headers: dict) -> None: ...


def dispatch(
    processor: Processor, publisher: Publisher, body: bytes, headers: dict | None
) -> Outcome:
    """Process one delivery and perform the outcome. Returning means the caller may ack.

    Order matters for "sessions never hang": the failed event is published before the message is
    parked in the DLQ, and both happen before the ack. If any publish raises, the message is not
    acked and the broker redelivers it (the analyzed event_id is deterministic, so a duplicate
    publish is harmless).
    """
    headers = dict(headers or {})
    attempt = int(headers.get(ATTEMPT_HEADER, 0))
    outcome = processor.handle(body, attempt)
    if outcome.event is not None:
        data = events_to_bytes(outcome.event)
        publisher.publish_event(events.ROUTING_ANALYZED, data, outcome.event["event_id"])
    if outcome.action is Action.RETRY:
        publisher.publish_retry(attempt + 1, body, {**headers, ATTEMPT_HEADER: attempt + 1})
    elif outcome.action is Action.DEAD:
        publisher.publish_dead(body, {**headers, "x-dead-reason": outcome.reason[:500]})
    return outcome


def events_to_bytes(event: dict) -> bytes:
    return json.dumps(event, separators=(",", ":"), allow_nan=False).encode("utf-8")


# --------------------------------------------------------------------------- pika


def declare_topology(channel, settings: Settings) -> None:
    channel.exchange_declare(settings.exchange, exchange_type="topic", durable=True)
    channel.queue_declare(
        settings.queue,
        durable=True,
        arguments={"x-dead-letter-exchange": "", "x-dead-letter-routing-key": settings.dlq},
    )
    channel.queue_declare(settings.dlq, durable=True)
    for i, delay in enumerate(settings.retry_delays_ms, start=1):
        channel.queue_declare(
            settings.retry_queue(i),
            durable=True,
            arguments={
                "x-message-ttl": int(delay),
                "x-dead-letter-exchange": "",
                "x-dead-letter-routing-key": settings.queue,
            },
        )
    channel.queue_bind(settings.queue, settings.exchange, events.ROUTING_UPLOADED)


def connection_params(settings: Settings) -> pika.URLParameters:
    params = pika.URLParameters(settings.amqp_url)
    params.heartbeat = 600  # analysis blocks the IO loop for seconds
    params.blocked_connection_timeout = 60
    params.socket_timeout = 10
    return params


class PikaPublisher:
    """Publishes with confirms and ``mandatory``: an unroutable message raises instead of vanishing."""

    def __init__(self, channel, settings: Settings):
        self.channel = channel
        self.settings = settings
        channel.confirm_delivery()

    def _publish(
        self, exchange: str, routing_key: str, body: bytes, headers: dict, message_id=None
    ):
        self.channel.basic_publish(
            exchange,
            routing_key,
            body,
            pika.BasicProperties(
                content_type="application/json",
                delivery_mode=pika.DeliveryMode.Persistent,
                headers=headers or None,
                message_id=message_id,
            ),
            mandatory=True,
        )

    def publish_event(self, routing_key: str, body: bytes, message_id: str) -> None:
        self._publish(self.settings.exchange, routing_key, body, {}, message_id)

    def publish_retry(self, attempt: int, body: bytes, headers: dict) -> None:
        self._publish("", self.settings.retry_queue(attempt), body, headers)

    def publish_dead(self, body: bytes, headers: dict) -> None:
        self._publish("", self.settings.dlq, body, headers)


class Worker:
    """Consume loop with automatic reconnect. Run ``run()`` in a thread; ``stop()`` to end it."""

    def __init__(self, settings: Settings, processor: Processor, reconnect_delay_s: float = 3.0):
        self.settings = settings
        self.processor = processor
        self.reconnect_delay_s = reconnect_delay_s
        self._stop = threading.Event()
        self.connected = False
        self.last_error: str | None = None

    def stop(self) -> None:
        self._stop.set()

    def run(self) -> None:
        while not self._stop.is_set():
            try:
                self._consume_until_stopped()
            except (AMQPError, OSError) as e:
                self.last_error = repr(e)
                logger.warning("broker connection lost: %r; reconnecting", e)
            finally:
                self.connected = False
            self._stop.wait(self.reconnect_delay_s)

    def _consume_until_stopped(self) -> None:
        connection = pika.BlockingConnection(connection_params(self.settings))
        try:
            channel = connection.channel()
            declare_topology(channel, self.settings)
            publisher = PikaPublisher(channel, self.settings)
            channel.basic_qos(prefetch_count=1)
            self.connected, self.last_error = True, None
            logger.info("consuming %s", self.settings.queue)
            for method, props, body in channel.consume(self.settings.queue, inactivity_timeout=1.0):
                if self._stop.is_set():
                    break
                if method is None:
                    continue
                self._handle_one(channel, publisher, method, props, body)
        finally:
            try:
                if connection.is_open:
                    connection.close()
            except Exception:  # noqa: BLE001 (best-effort close)
                pass

    def _handle_one(self, channel, publisher, method, props, body) -> None:
        try:
            dispatch(self.processor, publisher, body, props.headers)
        except AMQPError:
            raise  # connection trouble: unacked, so the broker redelivers after reconnect
        except Exception:
            # a bug in dispatch itself: do not loop forever, park it via the queue's DLX
            logger.exception("unhandled error; rejecting to the DLQ")
            channel.basic_nack(method.delivery_tag, requeue=False)
            return
        channel.basic_ack(method.delivery_tag)


def probe_broker(settings: Settings, timeout_s: float = 2.0) -> bool:
    """True if a connection can be opened (used by /health when no worker runs in-process)."""
    params = connection_params(settings)
    params.socket_timeout = timeout_s
    params.blocked_connection_timeout = timeout_s
    params.connection_attempts = 1
    try:
        pika.BlockingConnection(params).close()
        return True
    except Exception:  # noqa: BLE001
        return False
