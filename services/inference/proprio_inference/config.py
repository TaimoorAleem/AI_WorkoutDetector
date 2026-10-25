"""Service configuration, read from environment variables (see ``.env.example``)."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass, field

DEFAULT_PROFILE_MAP = "SM-R920=galaxy-watch5pro-fit-v1|galaxy-watch-prior-v0"
DEFAULT_RETRY_DELAYS_MS = (5_000, 15_000, 45_000)
MAIN_QUEUE = "inference.session-uploaded"


def parse_profile_map(text: str) -> dict[str, tuple[str, ...]]:
    """``"SM-R920=a|b,SM-R925=c"`` -> ``{"SM-R920": ("a", "b"), "SM-R925": ("c",)}``."""
    out: dict[str, tuple[str, ...]] = {}
    for item in filter(None, (p.strip() for p in text.split(","))):
        model, sep, profiles = item.partition("=")
        candidates = tuple(p.strip() for p in profiles.split("|") if p.strip())
        if not sep or not model.strip() or not candidates:
            raise ValueError(
                f"bad PROFILE_MAP entry {item!r} (want device_model=profile[|fallback])"
            )
        out[model.strip()] = candidates
    return out


def _bool(value: str) -> bool:
    return value.strip().lower() in ("1", "true", "yes", "on")


@dataclass(frozen=True)
class Settings:
    app_env: str = "dev"
    port: int = 8000
    amqp_url: str = "amqp://guest:guest@localhost:5672/%2F"
    exchange: str = "proprio.events"
    queue: str = MAIN_QUEUE
    s3_endpoint: str | None = None
    s3_region: str = "us-east-1"
    s3_bucket: str = "proprio-raw"
    s3_access_key: str | None = None
    s3_secret_key: str | None = None
    model_uri: str | None = None
    tau: float = 0.6
    profile_map: Mapping[str, tuple[str, ...]] = field(
        default_factory=lambda: parse_profile_map(DEFAULT_PROFILE_MAP)
    )
    retry_delays_ms: tuple[int, ...] = DEFAULT_RETRY_DELAYS_MS
    worker_enabled: bool = True
    max_zip_bytes: int = 256 * 1024 * 1024
    schema_dir: str | None = None

    @property
    def max_retries(self) -> int:
        return len(self.retry_delays_ms)

    @property
    def dlq(self) -> str:
        return f"{self.queue}.dlq"

    def retry_queue(self, attempt: int) -> str:
        """Delay queue for the ``attempt``-th retry (1-based)."""
        return f"{self.queue}.retry.{attempt}"

    @property
    def debug_endpoints(self) -> bool:
        return self.app_env in ("dev", "test")

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Settings:
        e = os.environ if env is None else env

        def get(name: str, default: str | None = None) -> str | None:
            value = e.get(name)
            return default if value is None or value == "" else value

        delays = get("RETRY_DELAYS_MS")
        return cls(
            app_env=get("APP_ENV", "dev"),
            port=int(get("PORT", "8000")),
            amqp_url=get("AMQP_URL", cls.amqp_url),
            exchange=get("AMQP_EXCHANGE", cls.exchange),
            s3_endpoint=get("S3_ENDPOINT"),
            s3_region=get("S3_REGION", cls.s3_region),
            s3_bucket=get("S3_BUCKET", cls.s3_bucket),
            s3_access_key=get("S3_ACCESS_KEY"),
            s3_secret_key=get("S3_SECRET_KEY"),
            model_uri=get("MODEL_URI"),
            tau=float(get("TAU", "0.6")),
            profile_map=parse_profile_map(get("PROFILE_MAP", DEFAULT_PROFILE_MAP)),
            retry_delays_ms=tuple(int(x) for x in delays.split(","))
            if delays
            else DEFAULT_RETRY_DELAYS_MS,
            worker_enabled=_bool(get("WORKER_ENABLED", "true")),
            max_zip_bytes=int(get("MAX_ZIP_BYTES", str(cls.max_zip_bytes))),
            schema_dir=get("EVENT_SCHEMA_DIR"),
        )
