"""Standalone worker: ``python -m proprio_inference.worker`` (no HTTP server)."""

from __future__ import annotations

import logging
import signal

from proprio_inference.app import Service
from proprio_inference.config import Settings


def main() -> int:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    service = Service(Settings.from_env())
    if service.processor is None:
        logging.getLogger(__name__).error(
            "cannot start: model not loaded (%s)", service.model_error
        )
        return 1
    service.start_worker()
    stop = service.worker.stop
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop())
    service._thread.join()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
