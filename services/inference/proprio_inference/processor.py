"""The worker's brain: one ``session.uploaded`` message in, one outcome out. No broker code here.

``Processor.handle`` returns an ``Outcome`` and the broker glue (``broker.dispatch``) performs it:

- ``PUBLISH``: publish ``event`` (ok, or failed for a permanent error), ack.
- ``RETRY``: transient error with retries left; re-queue through the next delay queue, ack.
- ``DEAD``: retries exhausted or a poison message; publish ``event`` if there is one (so the session
  never hangs), copy the message to the DLQ, ack.
"""

from __future__ import annotations

import hashlib
import json
import logging
import tempfile
import uuid
import zipfile
from dataclasses import dataclass
from enum import Enum
from pathlib import Path

from workout_ml.sensors.android import SessionFormatError, read_session, recording_stats
from workout_ml.watch.predict import ModelBundle, analyze_session, resolve_profile

from proprio_inference import events, metrics
from proprio_inference.config import Settings
from proprio_inference.storage import ObjectMissing, Storage

logger = logging.getLogger(__name__)


class Action(Enum):
    PUBLISH = "publish"
    RETRY = "retry"
    DEAD = "dead"


@dataclass
class Outcome:
    action: Action
    event: dict | None = None
    reason: str = ""


class PermanentError(Exception):
    """A failure no retry can fix; becomes a ``failed`` event with this code."""

    def __init__(self, code: str, message: str):
        super().__init__(message)
        self.code = code
        self.message = message


class Analyzer:
    """Profile choice + the shared ``workout_ml`` inference path, for one loaded model."""

    def __init__(self, model: ModelBundle, settings: Settings):
        self.model = model
        self.settings = settings
        self._profiles: dict[str, object] = {}

    def model_info(self) -> dict:
        info = {"name": self.model.name, "version": self.model.version}
        if self.model.uri:
            info["uri"] = self.model.uri
        return info

    def profile_for(self, device_model: str):
        """First loadable profile of the device's entry in PROFILE_MAP (``unsupported_device``
        for an unknown model or when none of its profiles exists)."""
        candidates = self.settings.profile_map.get(device_model)
        if not candidates:
            raise PermanentError("unsupported_device", f"no profile mapped for {device_model!r}")
        for pid in candidates:
            if pid not in self._profiles:
                try:
                    self._profiles[pid] = resolve_profile(pid)
                except FileNotFoundError:
                    logger.info(
                        "profile %s not available for %s, trying fallback", pid, device_model
                    )
                    continue
            return self._profiles[pid]
        raise PermanentError(
            "unsupported_device", f"none of the profiles {candidates} exists for {device_model!r}"
        )

    def analyze_file(self, path: Path, device_model: str) -> dict:
        """Payload fields of an ok ``session.analyzed`` event for the zip at ``path``."""
        try:
            session = read_session(path)
        except (SessionFormatError, zipfile.BadZipFile) as e:
            raise PermanentError("invalid_format", str(e)) from e
        profile = self.profile_for(device_model)
        results = analyze_session(session, self.model, profile, self.settings.tau)
        return {
            "model": self.model_info(),
            "profile_id": profile.profile_id,
            "transform_id": profile.transform_id,
            "recording_stats": recording_stats(session),
            "segments": [r.to_event() for r in results],
        }


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


class Processor:
    def __init__(self, analyzer: Analyzer, storage: Storage, settings: Settings):
        self.analyzer = analyzer
        self.storage = storage
        self.settings = settings

    def handle(self, body: bytes, attempt: int = 0) -> Outcome:
        """Process one message. ``attempt`` is the number of retries already made."""
        try:
            uploaded = events.parse_uploaded(body, self.settings.schema_dir)
        except events.InvalidEvent as e:
            logger.error("poison message (invalid session.uploaded): %s", e)
            metrics.inc("poison")
            return Outcome(
                Action.DEAD, self._failed_for_poison(body, str(e)), f"invalid event: {e}"
            )

        log = {"event_id": uploaded["event_id"], "session_id": uploaded["session_id"]}
        try:
            payload = self._analyze(uploaded)
        except PermanentError as e:
            logger.warning("permanent failure %s: %s", e.code, e.message, extra=log)
            metrics.inc("failed", code=e.code)
            event = events.analyzed_failed(uploaded, e.code, e.message, retryable=False)
            return Outcome(Action.PUBLISH, event, e.code)
        except Exception as e:  # transient until proven otherwise (S3 down, bug, memory, ...)
            logger.warning("transient failure (attempt %d): %r", attempt + 1, e, extra=log)
            if attempt < self.settings.max_retries:
                metrics.inc("retries")
                return Outcome(Action.RETRY, None, repr(e))
            metrics.inc("failed", code="internal")
            msg = f"giving up after {attempt + 1} attempts: {e!r}"
            event = events.analyzed_failed(uploaded, "internal", msg, retryable=True)
            return Outcome(Action.DEAD, event, msg)
        metrics.inc("analyzed")
        return Outcome(Action.PUBLISH, events.analyzed_ok(uploaded, payload), "ok")

    def _analyze(self, uploaded: dict) -> dict:
        if uploaded["size_bytes"] > self.settings.max_zip_bytes:
            raise PermanentError(
                "invalid_format", f"size_bytes {uploaded['size_bytes']} exceeds the limit"
            )
        with tempfile.TemporaryDirectory(prefix="proprio-inference-") as tmp:
            path = Path(tmp) / "session.zip"
            try:
                self.storage.download(uploaded["object_key"], path)
            except ObjectMissing as e:
                raise PermanentError(
                    "object_missing", f"no object {uploaded['object_key']!r}"
                ) from e
            actual = sha256_file(path)
            if actual != uploaded["sha256"]:
                raise PermanentError(
                    "hash_mismatch", f"sha256 {actual} != expected {uploaded['sha256']}"
                )
            return self.analyzer.analyze_file(path, uploaded["device_model"])

    def _failed_for_poison(self, body: bytes, why: str) -> dict | None:
        """A failed event for a malformed message, only if it still names a session and event."""
        try:
            doc = json.loads(body)
            ids = {"event_id": str(doc["event_id"]), "session_id": str(doc["session_id"])}
            uuid.UUID(ids["event_id"])
            uuid.UUID(ids["session_id"])
        except Exception:
            return None
        return events.analyzed_failed(
            ids, "internal", f"invalid session.uploaded event: {why}", retryable=False
        )
