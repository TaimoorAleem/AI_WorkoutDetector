"""``python -m proprio_inference``: the API with the worker embedded (what the image runs)."""

from __future__ import annotations

import logging

import uvicorn

from proprio_inference.app import create_app
from proprio_inference.config import Settings


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    settings = Settings.from_env()
    uvicorn.run(create_app(settings), host="0.0.0.0", port=settings.port, log_level="info")  # noqa: S104


if __name__ == "__main__":
    main()
