"""Logical watch datasets: name -> session ids (``data/watch_datasets.yaml``) -> raw zips."""

from __future__ import annotations

from pathlib import Path

import yaml

from workout_ml import config

WATCH_RAW_DIR = config.DATA_DIR / "raw" / "watch"
DATASETS_FILE = config.DATA_DIR / "watch_datasets.yaml"
KINDS = ("calibration", "gym")


def load_dataset_map(path=None) -> dict[str, list[str]]:
    """``{dataset name: [session id, ...]}`` from the checked-in YAML (empty lists allowed)."""
    path = Path(path or DATASETS_FILE)
    with open(path, encoding="utf-8") as f:
        raw = yaml.safe_load(f) or {}
    out = {}
    for name, entry in raw.items():
        sessions = entry.get("sessions") if isinstance(entry, dict) else entry
        out[str(name)] = [str(s) for s in (sessions or [])]
    return out


def find_session_zip(session_id: str, root=None) -> Path:
    """Locate ``proprio-session-<id>.zip`` under ``<root>/<kind>/`` (kinds: calibration, gym)."""
    root = Path(root or WATCH_RAW_DIR)
    for kind in KINDS:
        p = root / kind / f"proprio-session-{session_id}.zip"
        if p.is_file():
            return p
    raise FileNotFoundError(f"session {session_id} not found under {root}/{{{','.join(KINDS)}}}")


def dataset_paths(dataset, root=None, datasets_file=None) -> list[Path]:
    """Zip paths of a dataset. ``dataset`` is a name, or an explicit list of zip paths."""
    if isinstance(dataset, (list, tuple)):
        return [Path(p) for p in dataset]
    ids = load_dataset_map(datasets_file).get(dataset)
    if ids is None:
        raise KeyError(f"unknown watch dataset {dataset!r}")
    return [find_session_zip(i, root) for i in ids]


def dataset_session_ids(dataset, datasets_file=None) -> list[str]:
    if isinstance(dataset, (list, tuple)):
        return [Path(p).stem.removeprefix("proprio-session-") for p in dataset]
    return list(load_dataset_map(datasets_file).get(dataset) or [])
