"""Config parsing, profile choice, broker topology, model loading from MLflow."""

from __future__ import annotations

import shutil

import pytest
from workout_ml.sensors import profiles as profiles_mod

from proprio_inference.broker import declare_topology
from proprio_inference.config import Settings, parse_profile_map
from proprio_inference.processor import Analyzer, PermanentError


def test_parse_profile_map():
    m = parse_profile_map("SM-R920=a|b, SM-R925=c")
    assert m == {"SM-R920": ("a", "b"), "SM-R925": ("c",)}
    with pytest.raises(ValueError):
        parse_profile_map("SM-R920")


def test_settings_from_env_defaults_and_overrides():
    s = Settings.from_env({})
    assert s.tau == 0.6 and s.queue == "inference.session-uploaded" and s.max_retries == 3
    assert s.profile_map["SM-R920"] == ("galaxy-watch5pro-fit-v1", "galaxy-watch-prior-v0")
    s = Settings.from_env(
        {
            "TAU": "0.7",
            "RETRY_DELAYS_MS": "1,2",
            "S3_ENDPOINT": "http://m:9000",
            "APP_ENV": "prod",
            "WORKER_ENABLED": "false",
            "MODEL_URI": "/m",
        }  # fmt: skip
    )
    assert (s.tau, s.max_retries, s.s3_endpoint) == (0.7, 2, "http://m:9000")
    assert not s.debug_endpoints and not s.worker_enabled and s.model_uri == "/m"
    assert s.retry_queue(2) == "inference.session-uploaded.retry.2"


def test_sm_r920_falls_back_to_prior_when_fitted_profile_is_absent(model):
    a = Analyzer(model, Settings.from_env({}))
    assert a.profile_for("SM-R920").profile_id == "galaxy-watch-prior-v0"  # until the fit lands


def test_sm_r920_prefers_the_fitted_profile_when_present(model, tmp_path):
    src = profiles_mod.PROFILES_DIR / "galaxy-watch-prior-v0.yaml"
    fitted = tmp_path / "fit.yaml"
    fitted.write_text(
        src.read_text(encoding="utf-8").replace("galaxy-watch-prior-v0", "galaxy-watch5pro-fit-v1"),
        encoding="utf-8",
    )
    s = Settings(profile_map={"SM-R920": (str(fitted), "galaxy-watch-prior-v0")})
    assert Analyzer(model, s).profile_for("SM-R920").profile_id == "galaxy-watch5pro-fit-v1"


def test_unknown_device_and_no_loadable_profile(model):
    a = Analyzer(model, Settings(profile_map={"X": ("does-not-exist",)}))
    for device in ("nope", "X"):
        with pytest.raises(PermanentError) as e:
            a.profile_for(device)
        assert e.value.code == "unsupported_device"


class FakeChannel:
    def __init__(self):
        self.exchanges, self.queues, self.bindings = [], {}, []

    def exchange_declare(self, name, exchange_type, durable):
        self.exchanges.append((name, exchange_type, durable))

    def queue_declare(self, name, durable, arguments=None):
        self.queues[name] = (durable, arguments or {})

    def queue_bind(self, queue, exchange, routing_key):
        self.bindings.append((queue, exchange, routing_key))


def test_topology():
    s = Settings(retry_delays_ms=(5000, 15000, 45000))
    ch = FakeChannel()
    declare_topology(ch, s)
    assert ch.exchanges == [("proprio.events", "topic", True)]
    assert ch.bindings == [("inference.session-uploaded", "proprio.events", "session.uploaded")]
    assert set(ch.queues) == {
        "inference.session-uploaded", "inference.session-uploaded.dlq",
        "inference.session-uploaded.retry.1", "inference.session-uploaded.retry.2",
        "inference.session-uploaded.retry.3",
    }  # fmt: skip
    assert all(durable for durable, _ in ch.queues.values())
    main = ch.queues["inference.session-uploaded"][1]
    assert main["x-dead-letter-routing-key"] == "inference.session-uploaded.dlq"
    for i, ttl in enumerate((5000, 15000, 45000), start=1):
        args = ch.queues[f"inference.session-uploaded.retry.{i}"][1]
        assert args["x-message-ttl"] == ttl  # exponential backoff
        assert args["x-dead-letter-routing-key"] == "inference.session-uploaded"
        assert args["x-dead-letter-exchange"] == ""


def test_load_model_from_mlflow_uri(model, tmp_path):
    import mlflow
    import numpy as np
    import pandas as pd
    from mlflow.models import infer_signature
    from workout_ml.watch.predict import load_model

    mlflow.set_tracking_uri(f"sqlite:///{(tmp_path / 'mlflow.db').as_posix()}")
    X = pd.DataFrame(np.zeros((2, len(model.columns))), columns=model.columns)
    with mlflow.start_run() as run:
        mlflow.sklearn.log_model(
            model.pipeline,
            name="model",
            signature=infer_signature(X, ["rest", "rest"]),
            serialization_format="cloudpickle",  # as workout_ml.models.experiment does
        )
    loaded = load_model(f"runs:/{run.info.run_id}/model")
    assert loaded.columns == model.columns and loaded.version == run.info.run_id
    shutil.rmtree(tmp_path / "mlruns", ignore_errors=True)
