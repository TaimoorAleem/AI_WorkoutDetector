"""Reader for Proprio Raw Format v1 session zips (docs/specs/phase2-recorder.md sections 3 and 7).

``read_session`` turns ``proprio-session-<id>.zip`` (or an unpacked directory) into a ``Session``:
schema-validated metadata, sorted and de-duplicated ``RawStream``s (``frame="android"``, m/s^2 and
rad/s, timestamps as Unix ns), the watch's segment marks, and the phone's labels if present.
``Session.segments`` slices it into ``SensorSet``s ready for ``to_csf(..., load_profile("galaxy-..."))``.

Schemas: the source of truth is ``protocol/schema/*.json`` at the repo root, shared with the Kotlin
tests. This module looks there first (editable/source installs) and falls back to a copy packaged as
``workout_ml/sensors/schemas/`` (copied from the repo at wheel build time by ``ml/setup.py``).

CLI: ``python -m workout_ml.sensors.android <session.zip>`` prints stats and the segment table.
"""

from __future__ import annotations

import argparse
import io
import json
import logging
import zipfile
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd
from jsonschema import Draft202012Validator

from workout_ml.sensors.types import RawStream, SensorSet

logger = logging.getLogger(__name__)

RAW_FORMAT = 1
LABELS_FORMAT = 1
ACC_UNIT = "m/s^2"
GYR_UNIT = "rad/s"
GRAVITY = 9.80665
CLIP_G = 1.9
REST_TRIM_NS = 2_000_000_000
DRIFT_CORRECTION_THRESHOLD_MS = 50.0
GAP_MS = 100.0
SENSOR_HEADER = "t_ns,x,y,z"
SEGMENTS_HEADER = "segment_id,t_start_ns,t_end_ns,source,step"
KINDS = ("set", "rest", "other", "calibration", "unlabeled")


# Convention (mobile README): the label schema has no "discarded" kind, so it is kind "other" + this note.
DISCARDED_NOTE = "discarded"


class SessionFormatError(ValueError):
    """The session does not conform to Raw Format v1 (schema violation, bad CSV, wrong version)."""


# --------------------------------------------------------------------------- schemas


def schema_dir() -> Path:
    """Directory holding ``meta.schema.json`` and ``labels.schema.json``."""
    repo = Path(__file__).resolve().parents[3] / "protocol" / "schema"
    if (repo / "meta.schema.json").is_file():
        return repo
    packaged = Path(__file__).resolve().parent / "schemas"
    if (packaged / "meta.schema.json").is_file():
        return packaged
    raise FileNotFoundError(
        f"Raw Format schemas not found in {repo} or {packaged}; run from a repo checkout or build "
        "the package from the repo (ml/setup.py copies protocol/schema into the wheel)"
    )


def _validator(name: str) -> Draft202012Validator:
    with open(schema_dir() / name, encoding="utf-8") as f:
        return Draft202012Validator(json.load(f))


def _validate(doc: dict, schema_name: str, what: str) -> None:
    errors = sorted(_validator(schema_name).iter_errors(doc), key=lambda e: list(e.path))
    if errors:
        detail = "; ".join(
            f"{'/'.join(map(str, e.absolute_path)) or '<root>'}: {e.message}" for e in errors[:5]
        )
        raise SessionFormatError(f"{what} violates {schema_name}: {detail}")


# --------------------------------------------------------------------------- parsing


def _load_entries(path: Path) -> dict[str, bytes]:
    names = ("meta.json", "acc.csv", "gyr.csv", "segments.csv", "labels.json")
    if path.is_dir():
        return {n: (path / n).read_bytes() for n in names if (path / n).is_file()}
    with zipfile.ZipFile(path) as zf:
        out = {}
        for info in zf.infolist():
            if info.is_dir():
                continue
            if "/" in info.filename or "\\" in info.filename or info.filename in ("..", "."):
                raise SessionFormatError(f"unsafe zip entry name: {info.filename!r}")
            out[info.filename] = zf.read(info)
        return out


def _complete_text(data: bytes | None, header: str, name: str) -> tuple[str, int]:
    """Return the CSV text without a torn trailing row, and the number of rows dropped (0 or 1).

    A file whose last byte is not a newline was cut mid-write (crash, dead battery); the last row
    cannot be trusted even if it happens to parse.
    """
    if not data:
        return "", 0
    text = data.decode("utf-8")
    dropped = 0
    if not text.endswith("\n"):
        text = text[: text.rfind("\n") + 1]
        dropped = 1
    if not text:
        return "", dropped
    first, _, _ = text.partition("\n")
    if first.rstrip("\r") != header:
        raise SessionFormatError(f"{name}: unexpected header {first!r}, expected {header!r}")
    return text, dropped


def _read_sensor_csv(data: bytes | None, name: str) -> tuple:
    """Parse to (t_sensor_clock, xyz, raw_rows, duplicates, out_of_order, dropped_truncated)."""
    text, dropped = _complete_text(data, SENSOR_HEADER, name)
    if not text:
        return np.empty(0, np.int64), np.empty((0, 3)), 0, 0, 0, dropped
    try:
        df = pd.read_csv(
            io.StringIO(text),
            dtype={"t_ns": np.int64, "x": np.float32, "y": np.float32, "z": np.float32},
            float_precision="round_trip",
        )
    except (ValueError, pd.errors.ParserError) as e:
        raise SessionFormatError(f"{name}: {e}") from e
    t = df["t_ns"].to_numpy(np.int64)
    xyz = df[["x", "y", "z"]].to_numpy(np.float32).astype(np.float64)
    rows = len(t)
    out_of_order = int((np.diff(t) < 0).sum()) if rows > 1 else 0
    order = np.argsort(t, kind="stable")  # stable: arrival order is kept among equal timestamps
    t, xyz = t[order], xyz[order]
    keep = np.concatenate([[True], np.diff(t) > 0]) if rows else np.empty(0, bool)
    t, xyz = t[keep], xyz[keep]
    return t, xyz, rows, rows - len(t), out_of_order, dropped


def _read_marks(data: bytes | None) -> tuple[list[dict], int]:
    text, dropped = _complete_text(data, SEGMENTS_HEADER, "segments.csv")
    if not text:
        return [], dropped
    try:
        df = pd.read_csv(
            io.StringIO(text),
            dtype={
                "segment_id": np.int64,
                "t_start_ns": np.int64,
                "t_end_ns": np.int64,
                "source": str,
                "step": str,
            },
            keep_default_na=False,
        )
    except (ValueError, pd.errors.ParserError) as e:
        raise SessionFormatError(f"segments.csv: {e}") from e
    marks = []
    for r in df.itertuples(index=False):
        if r.source not in ("watch", "phone", "auto"):
            raise SessionFormatError(f"segments.csv: bad source {r.source!r}")
        marks.append(
            {
                "segment_id": int(r.segment_id),
                "t_start_ns": int(r.t_start_ns),
                "t_end_ns": int(r.t_end_ns),
                "source": r.source,
                "step": r.step or None,
            }
        )
    return marks, dropped


# --------------------------------------------------------------------------- segments


@dataclass(frozen=True)
class Segment:
    """A segment after label overrides and implicit rest. Times are in the sensor clock (ns).

    ``kind`` is ``"unlabeled"`` for a gym mark with no label entry. Implicit rest segments have
    ``implicit=True``, a negative ``segment_id`` (-1, -2, ... in time order) and ``source=None``.
    """

    segment_id: int
    kind: str
    t_start_ns: int
    t_end_ns: int
    source: str | None = None
    step: str | None = None
    exercise: str | None = None
    load_kg: float | None = None
    category: str | None = None
    reps_user: int | None = None
    rpe: int | None = None
    note: str = ""
    implicit: bool = False


def _subtract(piece: tuple[int, int], cut: tuple[int, int]) -> list[tuple[int, int]]:
    s, e = piece
    cs, ce = cut
    if ce <= s or cs >= e:
        return [piece]
    out = []
    if cs > s:
        out.append((s, cs))
    if ce < e:
        out.append((ce, e))
    return out


def resolve_segments(session_kind: str, marks: list[dict], labels: dict | None) -> list[Segment]:
    """Apply label overrides and add implicit rest (spec section 3.2); mirrors the Kotlin resolver.

    Rest: each gap between two consecutive ``set`` segments (by effective start) that is not covered
    by another segment becomes a ``rest`` segment, trimmed 2 s at each edge; a gap partly covered by
    e.g. an ``other`` segment gives one rest per uncovered part. Non-positive pieces are dropped.
    """
    by_id = {s["segment_id"]: s for s in (labels or {}).get("segments", [])}
    explicit: list[Segment] = []
    for m in marks:
        lab = by_id.get(m["segment_id"])
        if lab is not None:
            kind = lab["kind"]
        else:
            kind = "calibration" if session_kind == "calibration" else "unlabeled"
        lab = lab or {}
        start = lab.get("t_start_ns")
        end = lab.get("t_end_ns")
        explicit.append(
            Segment(
                segment_id=m["segment_id"],
                kind=kind,
                t_start_ns=m["t_start_ns"] if start is None else start,
                t_end_ns=m["t_end_ns"] if end is None else end,
                source=m["source"],
                step=m["step"],
                exercise=lab.get("exercise"),
                load_kg=lab.get("load_kg"),
                category=lab.get("category"),
                reps_user=lab.get("reps_user"),
                rpe=lab.get("rpe"),
                note=lab.get("note") or "",
            )
        )
    explicit.sort(key=lambda s: (s.t_start_ns, s.segment_id))
    sets = [s for s in explicit if s.kind == "set"]
    rests: list[tuple[int, int]] = []
    for a, b in zip(sets, sets[1:]):
        if b.t_start_ns <= a.t_end_ns:
            continue
        pieces = [(a.t_end_ns, b.t_start_ns)]
        for o in explicit:
            if o is a or o is b or o.t_end_ns <= o.t_start_ns:
                continue
            pieces = [p for piece in pieces for p in _subtract(piece, (o.t_start_ns, o.t_end_ns))]
        for s, e in pieces:
            if e - REST_TRIM_NS > s + REST_TRIM_NS:
                rests.append((s + REST_TRIM_NS, e - REST_TRIM_NS))
    rests.sort()
    implicit = [
        Segment(segment_id=-(i + 1), kind="rest", t_start_ns=s, t_end_ns=e, implicit=True)
        for i, (s, e) in enumerate(rests)
    ]
    return sorted(explicit + implicit, key=lambda s: (s.t_start_ns, s.segment_id))


# --------------------------------------------------------------------------- session


@dataclass
class Session:
    """A parsed recording. ``acc``/``gyr`` carry Unix-ns timestamps; ``t_*`` arrays are sensor-clock."""

    meta: dict
    labels: dict | None
    acc: RawStream
    gyr: RawStream
    marks: list[dict]
    report: dict
    path: str | None = None
    _t_acc: np.ndarray = field(repr=False, default_factory=lambda: np.empty(0, np.int64))
    _t_gyr: np.ndarray = field(repr=False, default_factory=lambda: np.empty(0, np.int64))

    @property
    def session_id(self) -> str:
        return self.meta["session_id"]

    @property
    def kind(self) -> str:
        return self.meta["kind"]

    def resolved_segments(self) -> list[Segment]:
        return resolve_segments(self.kind, self.marks, self.labels)

    def to_unix_ns(self, t_ns: int) -> int:
        """Sensor-clock ns -> Unix ns with the start anchor (plus drift correction if > 50 ms)."""
        return int(
            _convert_clock(self.meta, np.asarray([t_ns], np.int64), self.report["drift_corrected"])[
                0
            ]
        )

    def segments(self, kinds=None, include_discarded: bool = False) -> list[SensorSet]:
        """Slice into ``SensorSet``s (``[start, end)``), optionally only the given ``kinds``.

        Each set's ``meta`` has ``label`` (the exercise for sets, else the kind), ``kind``,
        ``category``, ``reps_user``, ``participant``, ``segment_id``, ``session_id`` and more.
        A segment with no samples of one sensor yields an empty stream for it.
        Segments the phone user discarded as accidental (``note == "discarded"``, kind ``other``)
        are skipped unless ``include_discarded`` is true; the raw watch data is never altered.
        """
        if isinstance(kinds, str):
            kinds = (kinds,)
        wanted = None if kinds is None else set(kinds)
        out = []
        participant = (self.labels or {}).get("participant_id")
        for seg in self.resolved_segments():
            if wanted is not None and seg.kind not in wanted:
                continue
            if not include_discarded and seg.note == DISCARDED_NOTE:
                continue
            a0, a1 = np.searchsorted(self._t_acc, [seg.t_start_ns, seg.t_end_ns], side="left")
            g0, g1 = np.searchsorted(self._t_gyr, [seg.t_start_ns, seg.t_end_ns], side="left")
            acc = RawStream("acc", self.acc.t_ns[a0:a1], self.acc.xyz[a0:a1], ACC_UNIT, "android")
            gyr = RawStream("gyr", self.gyr.t_ns[g0:g1], self.gyr.xyz[g0:g1], GYR_UNIT, "android")
            out.append(
                SensorSet(
                    acc=acc,
                    gyr=gyr,
                    meta={
                        "label": seg.exercise if seg.kind == "set" else seg.kind,
                        "kind": seg.kind,
                        "category": seg.category,
                        "reps_user": seg.reps_user,
                        "participant": participant,
                        "segment_id": seg.segment_id,
                        "session_id": self.session_id,
                        "set": f"{self.session_id[:8]}-{seg.segment_id}",
                        "load_kg": seg.load_kg,
                        "rpe": seg.rpe,
                        "step": seg.step,
                        "source": seg.source,
                        "implicit": seg.implicit,
                        "note": seg.note,
                        "t_start_unix_ns": self.to_unix_ns(seg.t_start_ns),
                        "t_end_unix_ns": self.to_unix_ns(seg.t_end_ns),
                        "device_model": self.meta["device"]["model"],
                        "source_file": self.path,
                    },
                )
            )
        return out


def _convert_clock(meta: dict, t: np.ndarray, corrected: bool) -> np.ndarray:
    a0 = meta["clock_anchor_start"]
    rel = t - a0["elapsed_realtime_ns"]
    if corrected:
        a1 = meta["clock_anchor_end"]
        factor = (a1["unix_time_ns"] - a0["unix_time_ns"]) / (
            a1["elapsed_realtime_ns"] - a0["elapsed_realtime_ns"]
        )
        rel = np.round(rel * factor).astype(np.int64)
    return rel + a0["unix_time_ns"]


def clock_drift_ms(meta: dict) -> float | None:
    """End-anchor drift: (Unix delta) - (elapsed delta), in ms. None for an incomplete session."""
    end = meta.get("clock_anchor_end")
    if end is None:
        return None
    a0 = meta["clock_anchor_start"]
    return (
        (end["unix_time_ns"] - a0["unix_time_ns"])
        - (end["elapsed_realtime_ns"] - a0["elapsed_realtime_ns"])
    ) / 1e6


def read_session(path) -> Session:
    """Read a session zip (or unpacked directory), validating JSON against the schemas.

    Sorts and de-duplicates each stream (first occurrence wins), recovers files cut mid-row, and
    converts timestamps to Unix ns with the start anchor. Raises ``SessionFormatError`` on a schema
    violation, wrong format version or malformed CSV.
    """
    path = Path(path)
    entries = _load_entries(path)
    if "meta.json" not in entries:
        raise SessionFormatError(f"{path}: meta.json missing")
    try:
        meta = json.loads(entries["meta.json"])
        labels = json.loads(entries["labels.json"]) if "labels.json" in entries else None
    except json.JSONDecodeError as e:
        raise SessionFormatError(f"{path}: invalid JSON: {e}") from e
    _validate(meta, "meta.schema.json", "meta.json")
    if labels is not None:
        _validate(labels, "labels.schema.json", "labels.json")
        if labels["session_id"] != meta["session_id"]:
            raise SessionFormatError("labels.json session_id does not match meta.json")

    t_acc, xyz_acc, acc_rows, acc_dup, acc_ooo, acc_drop = _read_sensor_csv(
        entries.get("acc.csv"), "acc.csv"
    )
    t_gyr, xyz_gyr, gyr_rows, gyr_dup, gyr_ooo, gyr_drop = _read_sensor_csv(
        entries.get("gyr.csv"), "gyr.csv"
    )
    marks, seg_drop = _read_marks(entries.get("segments.csv"))

    drift = clock_drift_ms(meta)
    corrected = drift is not None and abs(drift) > DRIFT_CORRECTION_THRESHOLD_MS
    if corrected:
        logger.warning(
            f"clock drift {drift:.1f} ms exceeds {DRIFT_CORRECTION_THRESHOLD_MS} ms; correcting"
        )
    acc = RawStream("acc", _convert_clock(meta, t_acc, corrected), xyz_acc, ACC_UNIT, "android")
    gyr = RawStream("gyr", _convert_clock(meta, t_gyr, corrected), xyz_gyr, GYR_UNIT, "android")
    report = {
        "acc_rows": acc_rows,
        "gyr_rows": gyr_rows,
        "acc_duplicates": acc_dup,
        "gyr_duplicates": gyr_dup,
        "acc_out_of_order": acc_ooo,
        "gyr_out_of_order": gyr_ooo,
        "dropped_truncated_rows": acc_drop + gyr_drop + seg_drop,
        "drift_corrected": corrected,
    }
    if report["dropped_truncated_rows"]:
        logger.warning(
            f"{path}: dropped {report['dropped_truncated_rows']} truncated trailing row(s)"
        )
    return Session(
        meta=meta, labels=labels, acc=acc, gyr=gyr, marks=marks, report=report,
        path=str(path), _t_acc=t_acc, _t_gyr=t_gyr,
    )  # fmt: skip


# --------------------------------------------------------------------------- stats


def _sensor_stats(t: np.ndarray, duplicates: int) -> dict:
    n = len(t)
    if n < 2:
        return {"n": n, "duration_s": 0.0, "rate_hz": 0.0, "dt_p50_ms": 0.0, "dt_p95_ms": 0.0,
                "dt_p99_ms": 0.0, "dt_max_ms": 0.0, "gaps_gt_100ms": 0, "duplicates": duplicates}  # fmt: skip
    dt = np.diff(t) / 1e6
    duration_s = (t[-1] - t[0]) / 1e9
    p50, p95, p99 = np.percentile(dt, [50, 95, 99])
    return {
        "n": n,
        "duration_s": float(duration_s),
        "rate_hz": float((n - 1) / duration_s),
        "dt_p50_ms": float(p50),
        "dt_p95_ms": float(p95),
        "dt_p99_ms": float(p99),
        "dt_max_ms": float(dt.max()),
        "gaps_gt_100ms": int((dt > GAP_MS).sum()),
        "duplicates": duplicates,
    }


def recording_stats(session: Session) -> dict:
    """Timing and quality statistics (sensor clock; matches ``protocol/golden/expected.json``)."""
    acc = _sensor_stats(session._t_acc, session.report["acc_duplicates"])
    gyr = _sensor_stats(session._t_gyr, session.report["gyr_duplicates"])
    offset = (
        float((session._t_gyr[0] - session._t_acc[0]) / 1e6)
        if len(session._t_acc) and len(session._t_gyr)
        else 0.0
    )
    thr = CLIP_G * GRAVITY
    frac = float((np.abs(session.acc.xyz) > thr).any(axis=1).mean()) if len(session.acc) else 0.0
    return {
        "acc": acc,
        "gyr": gyr,
        "acc_gyr_start_offset_ms": offset,
        "clock_drift_ms": clock_drift_ms(session.meta),
        "acc_frac_above_1p9g": frac,
    }


# --------------------------------------------------------------------------- CLI


def format_report(session: Session) -> str:
    st = recording_stats(session)
    m = session.meta
    lines = [
        f"session {session.session_id}  kind={session.kind}  complete={m['complete']}  "
        f"device={m['device']['manufacturer']} {m['device']['model']}",
        f"started {m['started_at_utc']}  participant={(session.labels or {}).get('participant_id')}",
        f"repairs: {session.report}",
        "",
        f"{'sensor':<6}{'n':>8}{'dur_s':>9}{'rate_hz':>9}{'p50_ms':>8}{'p95_ms':>8}{'p99_ms':>8}{'max_ms':>9}{'gaps>100':>9}{'dups':>6}",
    ]
    for name in ("acc", "gyr"):
        s = st[name]
        lines.append(
            f"{name:<6}{s['n']:>8}{s['duration_s']:>9.1f}{s['rate_hz']:>9.2f}{s['dt_p50_ms']:>8.2f}"
            f"{s['dt_p95_ms']:>8.2f}{s['dt_p99_ms']:>8.2f}{s['dt_max_ms']:>9.1f}{s['gaps_gt_100ms']:>9}{s['duplicates']:>6}"
        )
    drift = st["clock_drift_ms"]
    lines += [
        "",
        f"acc-gyr start offset: {st['acc_gyr_start_offset_ms']:.2f} ms",
        f"clock drift (end anchor): {'n/a' if drift is None else f'{drift:.2f} ms'}",
        f"acc samples with any |axis| > {CLIP_G} g: {100 * st['acc_frac_above_1p9g']:.2f}%",
        "",
        f"{'id':>4} {'kind':<11}{'start_s':>9}{'end_s':>9}{'dur_s':>7} {'exercise':<8}{'kg':>6} {'cat':<7}{'reps':>5}  src/step",
    ]
    t0 = session.meta["clock_anchor_start"]["elapsed_realtime_ns"]
    for seg in session.resolved_segments():
        a, b = (seg.t_start_ns - t0) / 1e9, (seg.t_end_ns - t0) / 1e9
        kg = "" if seg.load_kg is None else f"{seg.load_kg:g}"
        lines.append(
            f"{seg.segment_id:>4} {seg.kind:<11}{a:>9.1f}{b:>9.1f}{b - a:>7.1f} {seg.exercise or '':<8}{kg:>6} "
            f"{seg.category or '':<7}{'' if seg.reps_user is None else seg.reps_user:>5}  "
            f"{'implicit' if seg.implicit else seg.step or seg.source}"
        )
    return "\n".join(lines)


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description="Print stats and segments of a Proprio session zip.")
    p.add_argument("session", help="proprio-session-<id>.zip (or an unpacked session directory)")
    args = p.parse_args(argv)
    print(format_report(read_session(args.session)))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
