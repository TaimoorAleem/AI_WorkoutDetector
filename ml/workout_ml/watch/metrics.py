"""Pure metric and gate logic for the watch verdict (no I/O): Wilson CI, PSI, voting,
rejection, tau selection and gate statuses (PROJECT.md 6.5, spec 3.3)."""

from __future__ import annotations

import numpy as np
import pandas as pd
from sklearn.metrics import f1_score

from workout_ml.models.lopo import majority_vote

DEFAULT_TAU = 0.6
TAU_TOLERANCE = 0.02  # tau keeps fit-set accuracy within 2 pp of tau = 0
TAU_MAX = 0.8  # grid cap: a unanimous-vote fit set would otherwise select tau = 1.0
PROVISIONAL_MARGIN = 0.10
SMALL_N_SETS = 30  # non-proportion gates (macro-F1, recall, MAE) are provisional below this
MIN_SETS_FOR_RECALL = 2

GATES = {
    "set_accuracy": 0.90,
    "baseline_drop_pp": 0.05,
    "macro_f1": 0.85,
    "class_recall": 0.75,
    "rejection": 0.80,
    "reps_mae_overall": 1.0,
    "reps_mae_per_exercise": 1.5,
    "reps_exact": 0.70,
    "reps_within1": 0.90,
    "calibration_residual_g": 0.15,
}

PASS, PROVISIONAL, FAIL, NA = "PASS", "PASS (provisional)", "FAIL", "N/A (insufficient data)"


def wilson_ci(k: int, n: int, z: float = 1.96) -> tuple[float, float]:
    """Wilson score interval for ``k`` successes in ``n`` trials (95% by default)."""
    if n <= 0:
        return (float("nan"), float("nan"))
    p = k / n
    denom = 1 + z**2 / n
    centre = (p + z**2 / (2 * n)) / denom
    half = z * np.sqrt(p * (1 - p) / n + z**2 / (4 * n**2)) / denom
    return (float(max(0.0, centre - half)), float(min(1.0, centre + half)))


def psi(reference, current, bins: int = 10, eps: float = 1e-4) -> float:
    """Population stability index of ``current`` vs ``reference`` (quantile bins of reference)."""
    ref = np.asarray(reference, float)
    cur = np.asarray(current, float)
    edges = np.unique(np.quantile(ref, np.linspace(0, 1, bins + 1)))
    if len(edges) < 2:  # constant reference: below / equal / above
        edges = np.array([-np.inf, ref[0] - 1e-9, ref[0] + 1e-9, np.inf])
    else:
        edges[0], edges[-1] = -np.inf, np.inf
    e = np.histogram(ref, edges)[0] / len(ref)
    a = np.histogram(cur, edges)[0] / len(cur)
    e, a = np.clip(e, eps, None), np.clip(a, eps, None)
    return float(np.sum((a - e) * np.log(a / e)))


def vote(preds) -> tuple[str, float]:
    """(winning class, its share of the windows) for one segment."""
    preds = np.asarray(preds)
    top = majority_vote(preds)
    return str(top), float((preds == top).mean())


def segment_decisions(win: pd.DataFrame, tau: float) -> pd.DataFrame:
    """One row per segment from window predictions.

    ``win`` needs ``set``, ``kind``, ``truth``, ``pred``. Adds ``vote``, ``share`` and ``final``:
    ``final`` is ``rest`` when the vote is rest, ``rejected`` when the winning lift class has a
    share below ``tau``, else the lift class. ``logged`` is True when ``final`` is a lift.
    """
    rows = []
    for s, g in win.groupby("set", sort=True):
        v, share = vote(g["pred"])
        if v == "rest":
            final = "rest"
        elif share < tau:
            final = "rejected"
        else:
            final = v
        rows.append(
            {
                "set": s,
                "kind": g["kind"].iloc[0],
                "truth": g["truth"].iloc[0],
                "vote": v,
                "share": share,
                "final": final,
                "logged": final not in ("rest", "rejected"),
                "n_windows": len(g),
            }
        )
    return pd.DataFrame(rows)


def lift_set_accuracy(dec: pd.DataFrame) -> float:
    lift = dec[dec["kind"] == "set"]
    return float((lift["final"] == lift["truth"]).mean()) if len(lift) else float("nan")


def select_tau(win: pd.DataFrame, default: float = DEFAULT_TAU, grid=None) -> dict:
    """Largest tau (grid 0..TAU_MAX) whose lift-set accuracy is within 2 pp of the tau = 0 accuracy
    (fit set only)."""
    grid = np.round(np.arange(0.0, TAU_MAX + 1e-9, 0.05), 2) if grid is None else grid
    if not (win["kind"] == "set").any():
        return {"tau": default, "reason": "no lift sets; default", "curve": {}}
    curve = {float(t): lift_set_accuracy(segment_decisions(win, float(t))) for t in grid}
    base = curve[float(min(grid))]
    ok = [t for t, a in curve.items() if a >= base - TAU_TOLERANCE - 1e-12]
    tau = max(ok)
    return {
        "tau": float(tau),
        "reason": f"largest tau within 2 pp of tau=0 ({base:.3f})",
        "curve": curve,
    }


def window_metrics(win: pd.DataFrame) -> dict:
    """Window macro-F1 and per-class recall over the lift windows (classes present)."""
    lift = win[win["kind"] == "set"]
    if lift.empty:
        return {"macro_f1": float("nan"), "window_acc": float("nan"), "recall": {}}
    classes = sorted(lift["truth"].unique())
    f1 = f1_score(lift["truth"], lift["pred"], labels=classes, average="macro", zero_division=0)
    recall = {}
    for c in classes:
        m = lift["truth"] == c
        recall[c] = {
            "recall": float((lift.loc[m, "pred"] == c).mean()),
            "n_windows": int(m.sum()),
            "n_sets": int(lift.loc[m, "set"].nunique()),
        }
    return {
        "macro_f1": float(f1),
        "window_acc": float((lift["truth"] == lift["pred"]).mean()),
        "recall": recall,
    }


def bench_ohp(win: pd.DataFrame, dec: pd.DataFrame) -> dict:
    """The known confusion pair, as its own line item (window and set level)."""
    lift = win[win["kind"] == "set"]
    ds = dec[dec["kind"] == "set"]
    out = {}
    for a, b in (("bench", "ohp"), ("ohp", "bench")):
        w = lift[lift["truth"] == a]
        s = ds[ds["truth"] == a]
        out[f"{a}_as_{b}"] = {
            "windows": int((w["pred"] == b).sum()),
            "n_windows": len(w),
            "window_rate": float((w["pred"] == b).mean()) if len(w) else float("nan"),
            "sets": int((s["vote"] == b).sum()),
            "n_sets": len(s),
        }
    return out


def rejection(dec: pd.DataFrame) -> dict:
    """Share of rest + other segments not logged as a lift."""
    ro = dec[dec["kind"].isin(["rest", "other"])]
    n = len(ro)
    k = int((~ro["logged"]).sum())
    out = {"n": n, "rejected": k, "rate": k / n if n else float("nan"), "ci": wilson_ci(k, n)}
    for kind in ("rest", "other"):
        sub = ro[ro["kind"] == kind]
        out[kind] = {"n": len(sub), "rejected": int((~sub["logged"]).sum())}
    return out


def rep_summary(rep_rows: pd.DataFrame) -> dict:
    """MAE, exact % and +-1 % overall and per exercise from rows (label, predicted, reps_user)."""
    if rep_rows.empty:
        return {"overall": None, "per_exercise": {}}

    def stats(g):
        err = (g["predicted"] - g["reps_user"]).abs()
        n = len(g)
        ex, w1 = int((err == 0).sum()), int((err <= 1).sum())
        return {
            "n": n,
            "mae": float(err.mean()),
            "exact": ex / n,
            "exact_ci": wilson_ci(ex, n),
            "within1": w1 / n,
            "within1_ci": wilson_ci(w1, n),
        }

    return {
        "overall": stats(rep_rows),
        "per_exercise": {e: stats(g) for e, g in rep_rows.groupby("label")},
    }


# --------------------------------------------------------------------------- gates


def prop_status(passed: bool, k: int, n: int, threshold: float) -> str:
    """Status of a proportion gate: N/A without data, FAIL, provisional when the Wilson lower
    bound is more than 10 pp below the threshold, else PASS."""
    if n <= 0:
        return NA
    if not passed:
        return FAIL
    lower = wilson_ci(k, n)[0]
    return PROVISIONAL if lower < threshold - PROVISIONAL_MARGIN else PASS


def count_status(passed: bool, n_sets: int) -> str:
    """Status of a non-proportion gate (macro-F1, recall, MAE): provisional below 30 sets."""
    if n_sets <= 0:
        return NA
    if not passed:
        return FAIL
    return PROVISIONAL if n_sets < SMALL_N_SETS else PASS


def combine(statuses) -> str:
    """Worst of several statuses (FAIL > N/A > provisional > PASS)."""
    statuses = list(statuses)
    for s in (FAIL, NA, PROVISIONAL):
        if s in statuses:
            return s
    return PASS if statuses else NA


def evaluate_gates(*, dec, win, wm, reps, baseline=None, calibration_status=NA) -> dict[str, dict]:
    """The five PROJECT.md 6.5 gates as ``{name: {"status", "value", "detail"}}``."""
    lift = dec[dec["kind"] == "set"]
    n_lift = len(lift)
    gates: dict[str, dict] = {"calibration": {"status": calibration_status, "value": None}}

    # set accuracy: >= 0.90 and <= 5 pp below the LOPO baseline (relative only if baseline < 0.90)
    k = int((lift["final"] == lift["truth"]).sum())
    acc = k / n_lift if n_lift else float("nan")
    thr = GATES["set_accuracy"]
    rel = None if baseline is None else baseline - GATES["baseline_drop_pp"]
    # the level actually required: relative only if the baseline itself is below the threshold
    required = thr if rel is None else (rel if baseline < thr else max(thr, rel))
    passed = bool(n_lift) and acc >= required - 1e-12
    gates["set_accuracy"] = {
        "status": prop_status(passed, k, n_lift, required),
        "value": acc,
        "required": required,
        "ci": wilson_ci(k, n_lift),
        "baseline": baseline,
        "detail": f">= {thr:.2f} and <= 5 pp below baseline "
        + ("(none)" if baseline is None else f"{baseline:.3f}"),
    }

    # macro-F1 + class recall (classes with >= 2 sets only)
    rec = wm["recall"]
    eligible = {c: v for c, v in rec.items() if v["n_sets"] >= MIN_SETS_FOR_RECALL}
    insufficient = sorted(set(rec) - set(eligible))
    f1 = wm["macro_f1"]
    if n_lift:
        passed = f1 >= GATES["macro_f1"] and all(
            v["recall"] >= GATES["class_recall"] for v in eligible.values()
        )
        status = count_status(passed, n_lift)
        if not eligible:
            status = NA if not passed else status
    else:
        status = NA
    gates["macro_f1"] = {
        "status": status,
        "value": f1,
        "detail": f"macro-F1 >= {GATES['macro_f1']}, every recall >= {GATES['class_recall']} "
        f"(classes with >= 2 sets); insufficient data: {', '.join(insufficient) or 'none'}",
        "min_recall": min((v["recall"] for v in eligible.values()), default=float("nan")),
    }

    # rejection
    rj = rejection(dec)
    gates["rejection"] = {
        "status": prop_status(
            rj["rate"] >= GATES["rejection"] if rj["n"] else False,
            rj["rejected"],
            rj["n"],
            GATES["rejection"],
        ),
        "value": rj["rate"],
        "ci": rj["ci"],
        "detail": f">= {GATES['rejection']:.2f} of rest+other not logged as a lift",
    }

    # reps
    ov = reps["overall"]
    if ov is None:
        gates["reps"] = {"status": NA, "value": None, "detail": "no confirmed sets"}
    else:
        per_ok = all(
            s["mae"] <= GATES["reps_mae_per_exercise"] for s in reps["per_exercise"].values()
        )
        sub = [
            count_status(ov["mae"] <= GATES["reps_mae_overall"] and per_ok, ov["n"]),
            prop_status(
                ov["exact"] >= GATES["reps_exact"],
                round(ov["exact"] * ov["n"]),
                ov["n"],
                GATES["reps_exact"],
            ),
            prop_status(
                ov["within1"] >= GATES["reps_within1"],
                round(ov["within1"] * ov["n"]),
                ov["n"],
                GATES["reps_within1"],
            ),
        ]
        gates["reps"] = {
            "status": combine(sub),
            "value": ov["mae"],
            "detail": "MAE <= 1.0 overall and <= 1.5 per exercise; exact >= 70%; +-1 >= 90%",
        }
    return gates


def suffix_status(status: str, in_sample: bool) -> str:
    return f"{status} (in-sample)" if in_sample else status
