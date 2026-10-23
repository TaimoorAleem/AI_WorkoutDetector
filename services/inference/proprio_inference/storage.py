"""Object storage access (S3 / MinIO / IBM COS) behind a small interface."""

from __future__ import annotations

from pathlib import Path
from typing import Protocol

import boto3
from botocore.config import Config
from botocore.exceptions import ClientError

from proprio_inference.config import Settings


class ObjectMissing(Exception):
    """The object key does not exist."""


class Storage(Protocol):
    def download(self, key: str, dest: Path) -> None:
        """Download ``key`` to ``dest``; raise ``ObjectMissing`` if absent. Other errors are
        transient (connection, throttling) and propagate."""

    def check(self) -> None:
        """Raise if the bucket is unreachable (used by /health)."""


class S3Storage:
    def __init__(self, settings: Settings):
        self.bucket = settings.s3_bucket
        self.client = boto3.client(
            "s3",
            endpoint_url=settings.s3_endpoint,
            region_name=settings.s3_region,
            aws_access_key_id=settings.s3_access_key,
            aws_secret_access_key=settings.s3_secret_key,
            config=Config(
                s3={"addressing_style": "path"},
                retries={"max_attempts": 2, "mode": "standard"},
                connect_timeout=5,
                read_timeout=30,
            ),
        )

    def download(self, key: str, dest: Path) -> None:
        try:
            self.client.download_file(self.bucket, key, str(dest))
        except ClientError as e:
            code = str(e.response.get("Error", {}).get("Code", ""))
            if code in ("404", "NoSuchKey", "NotFound"):
                raise ObjectMissing(key) from e
            raise

    def check(self) -> None:
        self.client.head_bucket(Bucket=self.bucket)
