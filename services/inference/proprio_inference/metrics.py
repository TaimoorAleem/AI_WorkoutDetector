"""Tiny in-process counters rendered as Prometheus text (no client library needed)."""

from __future__ import annotations

import threading
from collections import Counter

_lock = threading.Lock()
_counters: Counter = Counter()


def inc(name: str, **labels: str) -> None:
    key = (name, tuple(sorted(labels.items())))
    with _lock:
        _counters[key] += 1


def snapshot() -> dict:
    with _lock:
        return dict(_counters)


def reset() -> None:
    with _lock:
        _counters.clear()


def render() -> str:
    lines = []
    for (name, labels), value in sorted(snapshot().items()):
        lab = ",".join(f'{k}="{v}"' for k, v in labels)
        lines.append(
            f"proprio_inference_{name}_total{{{lab}}} {value}"
            if lab
            else f"proprio_inference_{name}_total {value}"
        )
    return "\n".join(lines) + "\n"
