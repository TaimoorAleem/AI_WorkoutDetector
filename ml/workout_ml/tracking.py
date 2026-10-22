"""MLflow helpers: tracking setup, provenance tags, flat parameter logging."""

from __future__ import annotations

import logging
import os
import re
import subprocess
from pathlib import Path

import mlflow

from workout_ml import config

logger = logging.getLogger(__name__)


def setup_tracking(tracking_uri=None, artifact_root=None, experiment=config.MLFLOW_EXPERIMENT):
    """Point MLflow at the tracking store and make sure the experiment exists.

    URI precedence: argument, ``MLFLOW_TRACKING_URI``, then ``sqlite:///<repo>/mlflow.db``.
    When the default sqlite store is used, artifacts go under ``<repo>/mlartifacts``.
    An explicit ``artifact_root`` always wins (used by tests).
    """
    uri = tracking_uri or os.environ.get("MLFLOW_TRACKING_URI")
    if not uri:
        uri = f"sqlite:///{config.MLFLOW_DEFAULT_DB.as_posix()}"
        artifact_root = artifact_root or config.MLFLOW_ARTIFACT_ROOT
    mlflow.set_tracking_uri(uri)
    if mlflow.get_experiment_by_name(experiment) is None:
        location = Path(artifact_root).resolve().as_uri() if artifact_root else None
        mlflow.create_experiment(experiment, artifact_location=location)
    mlflow.set_experiment(experiment)
    return uri


def _git(*args):
    try:
        out = subprocess.run(
            ["git", *args],
            cwd=config.PROJECT_ROOT,
            capture_output=True,
            text=True,
            check=True,
            timeout=30,
        )
        return out.stdout.strip()
    except (OSError, subprocess.SubprocessError):
        return None


def git_info():
    """Return (sha, dirty) for the repo, or ("unknown", "unknown") outside a checkout."""
    sha = _git("rev-parse", "HEAD")
    if sha is None:
        return "unknown", "unknown"
    status = _git("status", "--porcelain")
    return sha, str(bool(status))


def data_md5(dvc_file=config.DVC_DATA_FILE):
    """MD5 of the raw data directory, read from the DVC pointer file."""
    try:
        text = Path(dvc_file).read_text()
    except OSError:
        return "unknown"
    match = re.search(r"md5:\s*([0-9a-f]+(?:\.dir)?)", text)
    return match.group(1) if match else "unknown"


def log_flat_params(prefix, mapping):
    """Log a (possibly nested) dict as ``prefix.key`` params, values stringified."""
    flat = {}

    def walk(path, value):
        if isinstance(value, dict):
            for k, v in value.items():
                walk(f"{path}.{k}", v)
        else:
            flat[path] = str(value)[:500]

    walk(prefix, mapping)
    mlflow.log_params(flat)
