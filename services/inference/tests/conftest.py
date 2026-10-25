"""Shared fixtures. The fixture model is trained on synthetic data inside the test run (no real
data in CI); it reuses the ml test helpers for the synthetic MetaMotion-like sets."""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pytest

REPO = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(REPO / "ml" / "tests"))  # watch_synth (synthetic sets), test-only

from support import GOLDEN_GYM, FakeStorage, InMemoryBroker  # noqa: E402
from watch_synth import mm_cleaned  # noqa: E402
from workout_ml.watch.predict import train_bundle  # noqa: E402

from proprio_inference import metrics  # noqa: E402
from proprio_inference.config import Settings  # noqa: E402
from proprio_inference.processor import Analyzer, Processor  # noqa: E402

AUG = {"copies": 1, "max_deg": 30.0, "seed": 1}


@pytest.fixture(scope="session")
def model():
    mm = mm_cleaned(np.random.default_rng(0), sets_per_label=3, duration_s=18.0)
    return train_bundle(mm, aug_cfg=AUG, version="fixture-1", name="fixture-classifier")


@pytest.fixture(scope="session")
def golden_gym_bytes() -> bytes:
    return GOLDEN_GYM.read_bytes()


@pytest.fixture
def settings() -> Settings:
    return Settings(app_env="test", retry_delays_ms=(10, 10, 10))


@pytest.fixture(autouse=True)
def _reset_metrics():
    metrics.reset()


@pytest.fixture
def storage() -> FakeStorage:
    return FakeStorage()


@pytest.fixture
def broker(model, settings, storage) -> InMemoryBroker:
    return InMemoryBroker(Processor(Analyzer(model, settings), storage, settings))
