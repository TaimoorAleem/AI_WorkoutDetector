"""Inference on a raw session zip: the one code path behind the Phase 3 harness and the service.

``analyze_session`` reads a Raw Format v1 session, converts every segment with a device profile
(``to_csf``), cleans and engineers features exactly as in training, predicts windows with a
``ModelBundle`` and applies the harness's decision logic (window predictions -> set vote -> tau
rejection, ``workout_ml.watch.metrics.segment_decisions``), then counts reps on the segments
logged as lifts (``workout_ml.reps.count_set``). D-012: the service calls this, it does not
re-implement it.

A ``ModelBundle`` is a fitted sklearn pipeline plus the feature columns it expects and metadata
(name, version, feature set, classes, default tau). It is saved with joblib next to a readable
``metadata.json``; the files are trusted artifacts (never load a bundle from an untrusted source).
"""

from __future__ import annotations

import hashlib
import json
import logging
import subprocess
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd

from workout_ml import config
from workout_ml.reps.counter import count_set
from workout_ml.sensors import load_profile, to_csf
from workout_ml.sensors.android import Session, read_session
from workout_ml.sensors.profiles import Profile
from workout_ml.watch import metrics as M
from workout_ml.watch.common import clean, engineer
from workout_ml.watch.ingest import MIN_BINS

logger = logging.getLogger(__name__)

BUNDLE_FILE = "model.joblib"
METADATA_FILE = "metadata.json"
DEFAULT_MODEL_ID = "M1"  # KNN + rotation augmentation, the production candidate (evaluate.py)
REJECTED = "rejected"


# --------------------------------------------------------------------------- model bundle


@dataclass
class ModelBundle:
    """A fitted pipeline, the engineered-feature columns it takes, and its metadata."""

    pipeline: Any
    columns: list[str]
    meta: dict = field(default_factory=dict)

    @property
    def name(self) -> str:
        return self.meta.get("name", "unnamed")

    @property
    def version(self) -> str:
        return str(self.meta.get("version", "unknown"))

    @property
    def uri(self) -> str | None:
        return self.meta.get("uri")

    @property
    def classes(self) -> list[str]:
        return list(self.meta.get("classes") or getattr(self.pipeline, "classes_", []))

    @property
    def default_tau(self) -> float:
        return float(self.meta.get("tau_default", M.DEFAULT_TAU))

    def predict(self, feat: pd.DataFrame) -> np.ndarray:
        """Window-level class predictions for an engineered-feature frame."""
        return np.asarray(self.pipeline.predict(feat[self.columns]))

    def save(self, directory) -> Path:
        d = Path(directory)
        d.mkdir(parents=True, exist_ok=True)
        meta = {**self.meta, "classes": self.classes, "columns": list(self.columns)}
        joblib.dump({"pipeline": self.pipeline, "columns": list(self.columns)}, d / BUNDLE_FILE)
        (d / METADATA_FILE).write_text(json.dumps(meta, indent=2) + "\n", encoding="utf-8")
        return d

    @classmethod
    def load(cls, directory) -> ModelBundle:
        d = Path(directory)
        blob = joblib.load(d / BUNDLE_FILE)
        meta = json.loads((d / METADATA_FILE).read_text(encoding="utf-8"))
        meta.pop("columns", None)
        return cls(pipeline=blob["pipeline"], columns=list(blob["columns"]), meta=meta)


def load_model(uri: str) -> ModelBundle:
    """Load a bundle from a local directory, or an MLflow model URI (``runs:/``, ``models:/``).

    An MLflow model must be an sklearn pipeline logged with a signature naming the feature columns.
    """
    p = Path(str(uri))
    if p.is_dir():
        return ModelBundle.load(p)
    if "://" in str(uri) or str(uri).startswith(("runs:/", "models:/")):
        import mlflow.sklearn
        from mlflow.models import get_model_info

        info = get_model_info(uri)
        if info.signature is None or info.signature.inputs is None:
            raise ValueError(f"MLflow model {uri!r} has no input signature (feature columns)")
        columns = list(info.signature.inputs.input_names())
        pipeline = mlflow.sklearn.load_model(uri)
        meta = {
            "name": "mlflow-model",
            "version": str(getattr(info, "run_id", None) or uri),
            "uri": uri,
            "classes": [str(c) for c in getattr(pipeline, "classes_", [])],
        }
        return ModelBundle(pipeline=pipeline, columns=columns, meta=meta)
    raise FileNotFoundError(f"model not found: {uri!r} (not a directory or an MLflow URI)")


def _git_sha(root: Path) -> str:
    try:
        out = subprocess.run(
            ["git", "rev-parse", "--short=10", "HEAD"],
            cwd=root,
            capture_output=True,
            text=True,
            timeout=10,
            check=True,
        )
        return out.stdout.strip() or "nogit"
    except Exception:  # not a checkout, git missing
        return "nogit"


def data_md5(df: pd.DataFrame) -> str:
    """Stable fingerprint of the cleaned training frame (values and set ids)."""
    h = pd.util.hash_pandas_object(df, index=True).to_numpy()
    return hashlib.md5(h.tobytes()).hexdigest()[:12]  # noqa: S324 (fingerprint, not security)


def train_bundle(
    mm_clean: pd.DataFrame,
    *,
    model_id: str = DEFAULT_MODEL_ID,
    aug_cfg: dict | None = None,
    tau_default: float = M.DEFAULT_TAU,
    name: str = "proprio-classifier",
    version: str | None = None,
) -> ModelBundle:
    """Train a production candidate on all cleaned MetaMotion data, exactly as the harness does.

    Uses ``watch.evaluate.ModelBank`` (rotation augmentation per D-018 for M1), so the saved model
    is the one the Phase 3 verdict evaluated.
    """
    from workout_ml.watch.evaluate import FEATURE_SET, MODEL_DEFS, ModelBank

    bank = ModelBank(mm_clean, aug_cfg)
    pipeline = bank.get(model_id)
    spec = MODEL_DEFS[model_id]
    meta = {
        "name": name,
        "version": version or f"{_git_sha(config.PROJECT_ROOT)}-{data_md5(mm_clean)}",
        "model_id": model_id,
        "model": spec["model"],
        "augmentation": bank.aug_cfg if spec["augment"] else None,
        "feature_set": FEATURE_SET,
        "classes": [str(c) for c in pipeline.classes_],
        "tau_default": float(tau_default),
        "n_training_sets": int(mm_clean["set"].nunique()),
    }
    return ModelBundle(pipeline, list(bank.feature_set.columns), meta)


# --------------------------------------------------------------------------- analysis


@dataclass
class SegmentResult:
    """One segment of a session. ``predicted_*``/``confidence`` are None when not classified
    (calibration segments, segments too short for the filters); ``rep_count`` only for lifts."""

    segment_id: int
    kind: str  # set | rest | other | calibration (an unlabeled gym mark is reported as "set")
    start_s: float
    end_s: float
    predicted_exercise: str | None = None
    predicted_kind: str | None = None  # "set" when logged as a lift, else "rest"
    confidence: float | None = None  # share of windows behind the voted class
    rep_count: int | None = None
    vote: str | None = None
    rejected: bool = False

    def to_event(self) -> dict:
        """The ``segments[]`` item of ``session-analyzed.schema.json``."""
        out: dict = {
            "segment_id": self.segment_id,
            "kind": self.kind,
            "start_s": round(self.start_s, 3),
            "end_s": round(self.end_s, 3),
            "predicted_exercise": self.predicted_exercise,
            "rep_count": self.rep_count,
        }
        if self.predicted_kind is not None:
            out["predicted_kind"] = self.predicted_kind
        if self.confidence is not None:
            out["confidence"] = round(min(max(self.confidence, 0.0), 1.0), 4)
        return out


def resolve_profile(profile) -> Profile:
    return profile if isinstance(profile, Profile) else load_profile(profile)


def _segment_frames(session: Session, profile: Profile, min_bins: int):
    """(results skeleton, CSF frame of classifiable segments) for every non-discarded segment."""
    t0 = session.to_unix_ns(session.meta["clock_anchor_start"]["elapsed_realtime_ns"])
    results: dict[str, SegmentResult] = {}
    frames = []
    for ss in session.segments():
        m = ss.meta
        kind = "set" if m["kind"] == "unlabeled" else m["kind"]
        res = SegmentResult(
            segment_id=int(m["segment_id"]),
            kind=kind,
            start_s=(m["t_start_unix_ns"] - t0) / 1e9,
            end_s=(m["t_end_unix_ns"] - t0) / 1e9,
        )
        results[m["set"]] = res
        if kind == "calibration" or len(ss.acc) < 2 or len(ss.gyr) < 2:
            continue
        df = to_csf(ss, profile)
        if len(df) < min_bins:
            continue
        df["participant"] = m["participant"] or "unknown"
        df["label"] = ""  # unknown at inference
        df["category"] = ""
        df["set"] = m["set"]
        frames.append(df)
    return results, (pd.concat(frames) if frames else None)


def analyze_session(
    session_or_path,
    model: ModelBundle,
    profile,
    tau: float = M.DEFAULT_TAU,
    *,
    min_bins: int = MIN_BINS,
) -> list[SegmentResult]:
    """Classify and rep-count every segment of a session.

    Args:
        session_or_path: a ``Session`` or the path of a Raw Format v1 zip.
        model: a ``ModelBundle``.
        profile: a ``Profile`` or profile id/path for ``to_csf``.
        tau: share of windows the winning lift class needs to be logged (else not a lift).

    Returns:
        One ``SegmentResult`` per non-discarded segment, in time order. A segment is judged on
        its own windows as ``predicted_kind`` ``set`` (logged lift, with ``predicted_exercise``
        and ``rep_count``) or ``rest`` (voted rest, or lift vote below ``tau``), whatever its
        user label; rest/other segments are therefore "rejected" when not logged as a lift.
    """
    session = (
        session_or_path if isinstance(session_or_path, Session) else read_session(session_or_path)
    )
    profile = resolve_profile(profile)
    results, frame = _segment_frames(session, profile, min_bins)
    if frame is not None:
        cleaned = clean(frame)
        feat = engineer(cleaned)
        win = pd.DataFrame({"set": feat["set"].to_numpy(), "pred": model.predict(feat)})
        win["kind"] = "set"  # the verdict logic only groups by set; the user's kind is not used
        win["truth"] = ""
        dec = M.segment_decisions(win, tau)
        for row in dec.itertuples():
            res = results[row.set]
            res.vote, res.confidence = row.vote, float(row.share)
            res.rejected = row.final == REJECTED
            if row.logged:
                res.predicted_kind, res.predicted_exercise = "set", row.final
                set_df = cleaned[cleaned["set"] == row.set].sort_index(kind="stable")
                try:
                    res.rep_count = int(count_set(set_df, row.final).count)
                except Exception as e:  # too short for the filter, unknown exercise, ...
                    logger.warning("rep counter failed on %s: %s", row.set, e)
            else:
                res.predicted_kind = "rest"
    return sorted(results.values(), key=lambda r: (r.start_s, r.segment_id))
