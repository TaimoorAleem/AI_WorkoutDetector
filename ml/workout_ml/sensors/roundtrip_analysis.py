"""Model-level fake-watch round trip (docs/sensor_spec.md section 7.1).

Train each LOPO fold on the ORIGINAL CSF of the training participants; evaluate the held-out
participant three ways: original, after the fake-watch round trip (random rotation per set,
default noise and jitter) and after a stress round trip (3x noise, 6 ms jitter, one 1 s gap
per set; informational only).

Usage:
    python -m workout_ml.sensors.roundtrip_analysis [--seed 0] [--tracking-uri URI]
"""

from __future__ import annotations

import argparse
import logging
import warnings
from dataclasses import dataclass

import matplotlib

matplotlib.use("Agg")

import mlflow  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from sklearn.metrics import accuracy_score, f1_score  # noqa: E402

from workout_ml import config, tracking  # noqa: E402
from workout_ml.data.preprocessor import DataLoader, OutlierRemover  # noqa: E402
from workout_ml.features.engineer_features import METADATA_COLUMNS, FeatureEngineer  # noqa: E402
from workout_ml.models import lopo  # noqa: E402
from workout_ml.sensors import (  # noqa: E402
    load_profile,
    profile_with_rotation,
    random_rotation,
    simulate_android,
    to_csf,
)

logger = logging.getLogger(__name__)

EXPERIMENT = "proprio-sensor-roundtrip"
FEATURE_SET = "basic+derived+pca+temporal"
MODEL = "NN"
REPORT_PATH = config.PROJECT_ROOT / "docs" / "reports" / "phase1-fake-watch-roundtrip.md"
BENCH, OHP = "bench", "ohp"

DEFAULT_SIM = {"jitter_ms": 3.0, "noise_acc_ms2": 0.02, "noise_gyr_rads": 0.002}
STRESS_SIM = {"jitter_ms": 6.0, "noise_acc_ms2": 0.06, "noise_gyr_rads": 0.006}
GAP_MS = 1000.0

# Spec 7.1 pass criteria
TOL_PP = 0.01
CONFUSION_REL_TOL = 0.20


@dataclass
class Variant:
    name: str
    sim_kwargs: dict
    gap: bool


VARIANTS = [
    Variant("roundtrip", DEFAULT_SIM, False),
    Variant("stress", STRESS_SIM, True),
]


def _add_meta(df, meta):
    for col in ("participant", "label", "category", "set"):
        df[col] = meta[col]
    return df


def _combine(frames):
    out = pd.concat(frames).sort_index(kind="stable")
    out.index.name = "epoch (ms)"
    return out


def _shared_rmse(rt: pd.DataFrame, ref: pd.DataFrame) -> dict:
    j = rt.join(ref, how="inner", lsuffix="_rt", rsuffix="_ref")
    out = {"n_bins": len(ref), "n_shared": len(j)}
    for sensor in ("acc", "gyr"):
        d = np.concatenate(
            [(j[f"{sensor}_{a}_rt"] - j[f"{sensor}_{a}_ref"]).to_numpy() for a in "xyz"]
        )
        out[f"rmse_{sensor}"] = float(np.sqrt(np.mean(d**2)))
    return out


def build_variant_csf(sets, ref_frames, variant: Variant, base_profile, seed: int):
    """Round-trip every set; returns (combined CSF frame, per-set RMSE rows)."""
    rng = np.random.default_rng(seed)
    frames, rows = [], []
    for s, ref in zip(sets, ref_frames):
        R = random_rotation(rng)
        profile = profile_with_rotation(base_profile, R)
        gaps = None
        if variant.gap:
            dur_s = (ref.index[-1] - ref.index[0]).total_seconds()
            gaps = [(float(rng.uniform(0.1, 0.8) * dur_s), GAP_MS)]
        sim = simulate_android(s, profile, rng, gaps=gaps, **variant.sim_kwargs)
        df = to_csf(sim, profile)
        row = {"variant": variant.name, "set": s.meta["set"], "participant": s.meta["participant"]}
        row.update(_shared_rmse(df, ref))
        rows.append(row)
        frames.append(_add_meta(df, s.meta))
    return _combine(frames), rows


def featurize(csf_df: pd.DataFrame) -> pd.DataFrame:
    oc = config.OUTLIER_CONFIG
    cols = config.FEATURE_CONFIG["predictor_columns"]
    cleaned = OutlierRemover.clean_outliers(
        csf_df, cols, method=oc["method"], scope=oc["scope"], C=oc["C"], n=oc["n"]
    )
    return FeatureEngineer(config.FEATURE_CONFIG).engineer_features(cleaned)


def _split(featured: pd.DataFrame):
    meta = [c for c in METADATA_COLUMNS if c in featured.columns]
    X = featured.drop(columns=meta).reset_index(drop=True)
    return (
        X,
        featured["label"].to_numpy(),
        featured["participant"].to_numpy(),
        featured["set"].to_numpy(),
    )


def _set_votes(sets, y_true, y_pred):
    frame = pd.DataFrame({"set": sets, "true": y_true, "pred": y_pred})
    voted = frame.groupby("set")["pred"].agg(lopo.majority_vote)
    truth = frame.groupby("set")["true"].first()
    return truth, voted


def _confusions(y_true, y_pred):
    return {
        "bench_to_ohp": int(((y_true == BENCH) & (y_pred == OHP)).sum()),
        "ohp_to_bench": int(((y_true == OHP) & (y_pred == BENCH)).sum()),
    }


def run_folds(featured: dict, seed_note: str = ""):
    """LOPO folds: train on original, predict every variant's held-out sets.

    ``featured`` maps variant name (``original`` first) to its featured frame.
    Returns (per-fold DataFrame, pooled predictions dict name -> (sets, y, pred)).
    """
    data = {name: _split(df) for name, df in featured.items()}
    X0, y0, p0, s0 = data["original"]
    classes = sorted(np.unique(y0))
    fs = next(f for f in lopo.build_feature_sets(X0.columns) if f.name == FEATURE_SET)
    cols = list(fs.columns)

    rows = []
    pooled = {n: ([], [], []) for n in data}
    for fold, (train, test) in enumerate(lopo.lopo_splits(p0)):
        held = p0[test][0]
        assert held not in set(p0[train])
        est, _ = lopo.fit_estimator(
            lopo.build_pipeline(fs, MODEL), X0.iloc[train][cols], y0[train], p0[train]
        )
        for name, (X, y, p, s) in data.items():
            m = p == held
            pred = est.predict(X[m][cols])
            row = {"fold": fold, "test_participant": held, "variant": name}
            row.update(lopo._fold_metrics(y[m], pred, s[m], classes))
            row.update(_confusions(y[m], pred))
            truth, voted = _set_votes(s[m], y[m], pred)
            row["set_bench_to_ohp"] = int(((truth == BENCH) & (voted == OHP)).sum())
            row["set_ohp_to_bench"] = int(((truth == OHP) & (voted == BENCH)).sum())
            rows.append(row)
            for store, val in zip(pooled[name], (s[m], y[m], pred)):
                store.append(val)
        logger.info(f"fold {fold} ({held}) done")
    pooled = {n: tuple(np.concatenate(v) for v in vals) for n, vals in pooled.items()}
    return pd.DataFrame(rows), pooled, classes


def summarize(folds: pd.DataFrame, pooled: dict, classes) -> pd.DataFrame:
    out = []
    for name, g in folds.groupby("variant", sort=False):
        sets, y, pred = pooled[name]
        truth, voted = _set_votes(sets, y, pred)
        conf = _confusions(y, pred)
        out.append(
            {
                "variant": name,
                "set_acc_mean": g["set_acc"].mean(),
                "macro_f1_mean": g["window_macro_f1"].mean(),
                "window_acc_mean": g["window_acc"].mean(),
                f"recall_{BENCH}_mean": g[f"recall_{BENCH}"].mean(),
                f"recall_{OHP}_mean": g[f"recall_{OHP}"].mean(),
                "set_acc_pooled": float((truth == voted.loc[truth.index]).mean()),
                "macro_f1_pooled": float(
                    f1_score(y, pred, labels=classes, average="macro", zero_division=0)
                ),
                "window_acc_pooled": float(accuracy_score(y, pred)),
                f"recall_{BENCH}_pooled": float((pred[y == BENCH] == BENCH).mean()),
                f"recall_{OHP}_pooled": float((pred[y == OHP] == OHP).mean()),
                "bench_to_ohp": conf["bench_to_ohp"],
                "ohp_to_bench": conf["ohp_to_bench"],
                "confusion_total": conf["bench_to_ohp"] + conf["ohp_to_bench"],
                "set_confusion_total": int(
                    g["set_bench_to_ohp"].sum() + g["set_ohp_to_bench"].sum()
                ),
                "n_windows": len(y),
            }
        )
    return pd.DataFrame(out).set_index("variant")


def pass_criteria(summary: pd.DataFrame, variant="roundtrip") -> dict:
    o, r = summary.loc["original"], summary.loc[variant]
    d_set = float(r["set_acc_mean"] - o["set_acc_mean"])
    d_f1 = float(r["macro_f1_mean"] - o["macro_f1_mean"])
    c0, c1 = int(o["confusion_total"]), int(r["confusion_total"])
    rel = (c1 - c0) / c0 if c0 else (0.0 if c1 == 0 else float("inf"))
    return {
        "delta_set_acc": d_set,
        "delta_macro_f1": d_f1,
        "confusion_original": c0,
        "confusion_roundtrip": c1,
        "confusion_rel_change": rel,
        "pass_set_acc": abs(d_set) <= TOL_PP + 1e-12,
        "pass_macro_f1": abs(d_f1) <= TOL_PP + 1e-12,
        "pass_confusion": abs(rel) <= CONFUSION_REL_TOL + 1e-12,
    }


def _md_table(df: pd.DataFrame, fmt="{:.3f}") -> str:
    cols = list(df.columns)
    lines = ["| " + " | ".join(cols) + " |", "| " + " | ".join("---" for _ in cols) + " |"]
    for _, r in df.iterrows():
        cells = [fmt.format(v) if isinstance(v, (float, np.floating)) else str(v) for v in r]
        lines.append("| " + " | ".join(cells) + " |")
    return "\n".join(lines)


def write_report(path, run_id, summary, folds, rmse_df, crit, n_sets, args):
    sm = summary.reset_index()
    show = [
        "variant",
        "set_acc_mean",
        "macro_f1_mean",
        f"recall_{BENCH}_mean",
        f"recall_{OHP}_mean",
        "bench_to_ohp",
        "ohp_to_bench",
        "set_confusion_total",
    ]
    pooled_cols = [
        "variant",
        "set_acc_pooled",
        "macro_f1_pooled",
        "window_acc_pooled",
        f"recall_{BENCH}_pooled",
        f"recall_{OHP}_pooled",
        "n_windows",
    ]
    per_fold = folds.pivot(
        index="test_participant", columns="variant", values=["set_acc", "window_macro_f1"]
    )
    per_fold.columns = [f"{m}:{v}" for m, v in per_fold.columns]
    per_fold = per_fold.reset_index()
    rmse_sum = (
        rmse_df.groupby("variant")[["rmse_acc", "rmse_gyr"]]
        .agg(["mean", "max"])
        .pipe(lambda d: d.set_axis([f"{a}_{b}" for a, b in d.columns], axis=1))
        .reset_index()
    )
    shared = rmse_df.groupby("variant").apply(
        lambda g: (g["n_bins"] - g["n_shared"]).sum(), include_groups=False
    )
    verdict = lambda ok: "PASS" if ok else "FAIL"  # noqa: E731
    text = f"""# Phase 1: fake-watch round trip (model level)

Generated by `python -m workout_ml.sensors.roundtrip_analysis`. Do not edit by hand.

- MLflow experiment `{EXPERIMENT}`, run ID: `{run_id}`
- Pipeline: `{FEATURE_SET}` / `{MODEL}`, default parameters, LOPO folds; trained on the ORIGINAL CSF of the training participants
- Sets: {n_sets}; seed: {args.seed}
- Held-out sets after `simulate_android` (random rotation per set, galaxy prior profile with that rotation) -> `to_csf` -> `clean_outliers` -> `FeatureEngineer`
- `roundtrip`: {DEFAULT_SIM}
- `stress` (informational): {STRESS_SIM}, plus one {GAP_MS:.0f} ms gap per set at a random position

## Pass criteria (spec 7.1, `roundtrip` vs `original`, fold means)

| criterion | measured | threshold | result |
| --- | --- | --- | --- |
| set accuracy delta | {crit["delta_set_acc"] * 100:+.2f} pp | within 1 pp | {verdict(crit["pass_set_acc"])} |
| window macro-F1 delta | {crit["delta_macro_f1"] * 100:+.2f} pp | within 1 pp | {verdict(crit["pass_macro_f1"])} |
| bench<->OHP window confusion | {crit["confusion_original"]} -> {crit["confusion_roundtrip"]} ({crit["confusion_rel_change"] * 100:+.1f}%) | within +-20% | {verdict(crit["pass_confusion"])} |

## Summary (mean over LOPO folds; confusion counts are pooled windows)

{_md_table(sm[show])}

## Pooled over all held-out windows

{_md_table(sm[pooled_cols])}

## Per fold (set accuracy / window macro-F1)

{_md_table(per_fold)}

## Signal-level RMSE per set (CSF bins before cleaning, vs original CSF, shared bins)

{_md_table(rmse_sum, "{:.4f}")}

Bins missing from the round trip (original bins without a counterpart, summed over sets): {", ".join(f"{k}: {int(v)}" for k, v in shared.items())}.
"""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--seed", type=int, default=0)
    ap.add_argument("--tracking-uri", default=None)
    ap.add_argument("--no-report", action="store_true")
    args = ap.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(name)s %(levelname)s %(message)s")
    warnings.filterwarnings("ignore", category=UserWarning, module="sklearn")
    warnings.filterwarnings("ignore", category=FutureWarning)
    config.ensure_dirs()

    loader = DataLoader(config.RAW_DATA_PATH)
    sets = loader.load_sets()
    mm = loader.profile
    galaxy = load_profile("galaxy-watch-prior-v0")
    ref_frames = [to_csf(s, mm) for s in sets]
    original_csf = _combine([_add_meta(f.copy(), s.meta) for f, s in zip(ref_frames, sets)])

    csf = {"original": original_csf}
    rmse_rows = []
    for i, v in enumerate(VARIANTS):
        logger.info(f"Simulating variant {v.name}")
        csf[v.name], rows = build_variant_csf(sets, ref_frames, v, galaxy, args.seed + i)
        rmse_rows += rows
    rmse_df = pd.DataFrame(rmse_rows)

    featured = {name: featurize(df) for name, df in csf.items()}
    folds, pooled, classes = run_folds(featured)
    summary = summarize(folds, pooled, classes)
    crit = pass_criteria(summary)
    logger.info("\n" + summary.round(3).T.to_string())
    logger.info(f"criteria: {crit}")

    tracking.setup_tracking(args.tracking_uri, experiment=EXPERIMENT)
    sha, dirty = tracking.git_info()
    with mlflow.start_run(
        run_name=f"roundtrip-{FEATURE_SET}-{MODEL}",
        tags={
            "git.sha": sha,
            "git.dirty": dirty,
            "data.raw.md5": tracking.data_md5(),
            "phase": "1",
        },
    ) as run:
        mlflow.log_params(
            {
                "feature_set": FEATURE_SET,
                "model": MODEL,
                "seed": args.seed,
                "n_sets": len(sets),
                "base_profile": galaxy.profile_id,
                "gap_ms_stress": GAP_MS,
                **{f"sim.roundtrip.{k}": v for k, v in DEFAULT_SIM.items()},
                **{f"sim.stress.{k}": v for k, v in STRESS_SIM.items()},
                "model_params": str(lopo.model_hyperparameters(MODEL)),
            }
        )
        for name, row in summary.iterrows():
            mlflow.log_metrics({f"{name}.{k}": float(v) for k, v in row.items()})
        for name, g in rmse_df.groupby("variant"):
            mlflow.log_metrics(
                {
                    f"{name}.rmse_acc_mean": float(g.rmse_acc.mean()),
                    f"{name}.rmse_acc_max": float(g.rmse_acc.max()),
                    f"{name}.rmse_gyr_mean": float(g.rmse_gyr.mean()),
                    f"{name}.rmse_gyr_max": float(g.rmse_gyr.max()),
                }
            )
        mlflow.log_metrics(
            {
                "delta_set_acc": crit["delta_set_acc"],
                "delta_macro_f1": crit["delta_macro_f1"],
                "confusion_rel_change": crit["confusion_rel_change"],
                "pass_all": float(
                    crit["pass_set_acc"] and crit["pass_macro_f1"] and crit["pass_confusion"]
                ),
            }
        )
        out_dir = config.METRICS_DIR
        out_dir.mkdir(parents=True, exist_ok=True)
        for fname, df in (
            ("roundtrip_folds.csv", folds),
            ("roundtrip_summary.csv", summary.reset_index()),
            ("roundtrip_set_rmse.csv", rmse_df),
        ):
            p = out_dir / fname
            df.to_csv(p, index=False)
            mlflow.log_artifact(str(p))
        run_id = run.info.run_id

    if not args.no_report:
        write_report(REPORT_PATH, run_id, summary, folds, rmse_df, crit, len(sets), args)
        logger.info(f"Report written to {REPORT_PATH}")
    logger.info(f"MLflow run: {run_id}")
    return summary, crit, run_id


if __name__ == "__main__":
    main()
