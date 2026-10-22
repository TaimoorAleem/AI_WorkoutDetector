"""Rotation sensitivity of the Phase 0 best pipeline (docs/sensor_spec.md section 7.2).

The held-out participant's six sensor columns (acc and gyro, same R) are rotated *before*
feature engineering; the models are fitted once per fold on unrotated data (R2 excepted)
and evaluated on every rotated version of the held-out data.

Variants:
- ``baseline``: basic+derived+pca+temporal / NN
- ``R1``: magnitude-only features (acc_r, gyr_r and their rolling statistics) / NN
- ``R2``, ``R2w``, ``R2w8``: baseline features and model, training sets = originals plus K
  randomly rotated copies (K=4/4/8, max angle 15/30/30 deg)

The model is a parameter (``--models``, default NN); results are cached per (model, variant).
Variant R1 (magnitude features) is defined for any model but is only computed on request.

Usage:
    python -m workout_ml.analysis.rotation_sensitivity [--models NN KNN RF]
        [--variants baseline R2w8] [--tracking-uri URI] [--no-mlflow]
"""

from __future__ import annotations

import argparse
import logging
import tempfile
import time
import warnings
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless

import matplotlib.pyplot as plt  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
from joblib import Parallel, delayed  # noqa: E402

from workout_ml import config, tracking  # noqa: E402
from workout_ml.augment import (  # noqa: E402
    ACC_COLS,  # noqa: F401 (re-exported)
    GYR_COLS,  # noqa: F401 (re-exported)
    augment_sets,
    engineer_augmented,
    random_rotations,
    random_unit_axes,
    rotate_sensor_frame,
    rotation_matrix,
)
from workout_ml.data.preprocessor import DataLoader, OutlierRemover  # noqa: E402
from workout_ml.features.engineer_features import FeatureEngineer  # noqa: E402
from workout_ml.features.TemporalAbstraction import NumericalAbstraction  # noqa: E402
from workout_ml.models import lopo  # noqa: E402

logger = logging.getLogger(__name__)

EXPERIMENT = "proprio-sensitivity"
MAG_COLS = ["acc_r", "gyr_r"]
THETAS = (0, 2, 5, 10, 15, 20, 30)
N_RANDOM_AXES = 10
AXIS_SEED = 20240611
AUG_SEED = 20240612
AUG_K = 4
AUG_MAX_DEG = 15.0
# augmentation variants: name -> (K rotated copies, max angle in degrees). The training set
# is the originals plus K rotated copies (one random R per set copy); same AUG_SEED for all.
AUG_VARIANTS = {"R2": (4, 15.0), "R2w": (4, 30.0), "R2w8": (8, 30.0)}
SIGNED_AXES = {
    "+x": (1, 0, 0),
    "-x": (-1, 0, 0),
    "+y": (0, 1, 0),
    "-y": (0, -1, 0),
    "+z": (0, 0, 1),
    "-z": (0, 0, -1),
}
VARIANTS = ("baseline", "R1", "R2", "R2w", "R2w8")
BASELINE_SET = "basic+derived+pca+temporal"
MODEL = "NN"  # default model; every (model, variant) pair is a separate cached result
DEFAULT_MODELS = ("NN",)
# section 7.3 tolerances (percentage points, expressed as fractions)
TOL_SET_ACC = 0.02
TOL_RECALL = 0.05
CSV_PATH = config.METRICS_DIR / "phase1_rotation_sensitivity.csv"
FOLDS_CSV_PATH = config.METRICS_DIR / "phase1_rotation_sensitivity_folds.csv"
REPORT_PATH = config.PROJECT_ROOT / "docs" / "reports" / "phase1-rotation-sensitivity.md"
PLOT_PATH = config.PROJECT_ROOT / "docs" / "reports" / "phase1-rotation-sensitivity.png"


# --------------------------------------------------------------------------- rotations
def build_conditions(thetas=THETAS, n_random=N_RANDOM_AXES, seed=AXIS_SEED):
    """List of (theta, axis_name, R). theta=0 appears once (axis ``none``)."""
    rng = np.random.default_rng(seed)
    out = []
    for theta in thetas:
        if theta == 0:
            out.append((0, "none", np.eye(3)))
            continue
        for name, axis in SIGNED_AXES.items():
            out.append((theta, name, rotation_matrix(axis, theta)))
        for i, axis in enumerate(random_unit_axes(n_random, rng)):
            out.append((theta, f"rand{i}", rotation_matrix(axis, theta)))
    return out


# the shared implementation lives in workout_ml.augment (D-018)
rotate_sensor_columns = rotate_sensor_frame


def rotate_sets_randomly(df, rng, max_deg=AUG_MAX_DEG):
    """Rotate each set by its own random R (angle ~ U[0, max_deg], random axis)."""
    out = df.copy()
    set_rows = df.groupby("set", sort=True).indices
    for (_, rows), R in zip(
        set_rows.items(), random_rotations(len(set_rows), max_deg, rng), strict=True
    ):
        out = rotate_sensor_frame(out, R, rows)
    return out


def kabsch_residual_g(theta_deg):
    """Residual |R v - v| for a unit gravity vector under a rotation error theta."""
    return 2.0 * np.sin(np.deg2rad(theta_deg) / 2.0)


# --------------------------------------------------------------------------- features
class MagnitudeFeatureEngineer(FeatureEngineer):
    """FeatureEngineer plus rolling statistics of acc_r / gyr_r (same windows).

    Extra columns are named ``acc_r_mag_<agg>_ws_<w>`` so the baseline feature sets (which
    match ``_temp_``) are unchanged.
    """

    def _engineer_set(self, set_df, cols):
        out = super()._engineer_set(set_df, cols)
        extra = out[MAG_COLS]
        numerical = NumericalAbstraction()
        for window_size in self.windows:
            for agg in ["mean", "std", "min", "max"]:
                extra = numerical.abstract_numerical(extra, MAG_COLS, window_size, agg)
        extra = extra.drop(columns=MAG_COLS)
        extra.columns = [c.replace("_temp_", "_mag_") for c in extra.columns]
        return pd.concat([out, extra], axis=1)


def engineer(df):
    return MagnitudeFeatureEngineer(config.FEATURE_CONFIG).engineer_features(df)


def magnitude_columns(columns):
    return MAG_COLS + [c for c in columns if "_mag_" in c]


def load_cleaned():
    """Same steps as pipeline.py: load, merge, resample (200 ms), clean outliers."""
    resampled = DataLoader(config.RAW_DATA_PATH).load_data()  # reader -> CSF-1, 200 ms bins
    oc = config.OUTLIER_CONFIG
    return OutlierRemover.clean_outliers(
        resampled,
        config.FEATURE_CONFIG["predictor_columns"],
        method=oc["method"],
        scope=oc["scope"],
        C=oc["C"],
        n=oc["n"],
    )


def _featurize_task(sub_df, R):
    warnings.filterwarnings("ignore")
    return engineer(rotate_sensor_columns(sub_df, R))


# --------------------------------------------------------------------------- metrics
def _confusion_counts(y, pred):
    return {
        "n_bench": int((y == "bench").sum()),
        "n_ohp": int((y == "ohp").sum()),
        "bench_to_ohp": int(((y == "bench") & (pred == "ohp")).sum()),
        "ohp_to_bench": int(((y == "ohp") & (pred == "bench")).sum()),
    }


def fold_row(variant, theta, axis, participant, y, pred, sets, classes):
    m = lopo._fold_metrics(y, pred, sets, classes)
    row = {
        "variant": variant,
        "theta": theta,
        "axis": axis,
        "test_participant": participant,
        "set_acc": m["set_acc"],
        "window_macro_f1": m["window_macro_f1"],
        "window_acc": m["window_acc"],
        "recall_bench": m["recall_bench"],
        "recall_ohp": m["recall_ohp"],
    }
    row.update(_confusion_counts(y, pred))
    return row


def aggregate(folds):
    """Per (variant, theta, axis): mean over folds (NaN-aware) + pooled bench/OHP confusion."""
    if "model" not in folds.columns:  # caches written before the model parameter existed
        folds = folds.assign(model=MODEL)
    keys = ["model", "variant", "theta", "axis"]
    mean_cols = ["set_acc", "window_macro_f1", "window_acc", "recall_bench", "recall_ohp"]
    agg = folds.groupby(keys, sort=False)[mean_cols].mean()
    sums = folds.groupby(keys, sort=False)[
        ["n_bench", "n_ohp", "bench_to_ohp", "ohp_to_bench"]
    ].sum()
    agg["bench_ohp_confusion"] = (sums["bench_to_ohp"] + sums["ohp_to_bench"]) / (
        sums["n_bench"] + sums["n_ohp"]
    )
    return agg.reset_index()


# --------------------------------------------------------------------------- theta_tol
def theta_tol(table, variant, axes=None, model=None):
    """Largest theta whose worst-axis drops vs theta=0 are within the section 7.3 limits.

    ``table``: aggregated frame (columns variant, theta, axis, set_acc, recall_bench,
    recall_ohp). A theta passes when, over all considered axes, the set accuracy drop is
    <= 2 pp and the bench and OHP recall drops are each <= 5 pp. ``axes=None`` uses every
    axis present for theta > 0. Returns 0 if no theta > 0 passes.
    """
    t = table[table["variant"] == variant]
    if model is not None and "model" in table.columns:
        t = t[t["model"] == model]
    base = t[t["theta"] == 0].iloc[0]
    best = 0.0
    eps = 1e-9
    for theta in sorted(t["theta"].unique()):
        if theta == 0:
            continue
        sub = t[t["theta"] == theta]
        if axes is not None:
            sub = sub[sub["axis"].isin(axes)]
        drop_set = base["set_acc"] - sub["set_acc"].min()
        drop_bench = base["recall_bench"] - sub["recall_bench"].min()
        drop_ohp = base["recall_ohp"] - sub["recall_ohp"].min()
        ok = drop_set <= TOL_SET_ACC + eps
        ok = ok and drop_bench <= TOL_RECALL + eps and drop_ohp <= TOL_RECALL + eps
        if ok:
            best = max(best, float(theta))
    return best


# --------------------------------------------------------------------------- main analysis
def run_analysis(cleaned, n_jobs=-1, combos=None):
    """Fold-level rows for the requested (model, variant) ``combos`` only."""
    combos = list(combos if combos is not None else [(MODEL, v) for v in VARIANTS])
    variants = {v for _, v in combos}
    classes = sorted(cleaned["label"].unique())
    conditions = build_conditions()
    participants = sorted(cleaned["participant"].unique())

    logger.info("Featurizing unrotated data")
    feat0 = engineer(cleaned)
    all_cols = [c for c in feat0.columns if c not in ("participant", "label", "category", "set")]
    fsets = {fs.name: fs for fs in lopo.build_feature_sets(all_cols)}
    base_fs = fsets[BASELINE_SET]
    mag_fs = lopo.FeatureSet("magnitude", tuple(magnitude_columns(all_cols)), use_pca=False)

    aug = {}
    for v in variants:
        if v in AUG_VARIANTS:
            k, max_deg = AUG_VARIANTS[v]
            logger.info("Featurizing %d augmentation copies for %s", k, v)
            aug[v] = engineer_augmented(augment_sets(cleaned, k, max_deg, AUG_SEED), engineer)

    logger.info("Featurizing %d participants x %d rotations", len(participants), len(conditions))
    jobs, keys = [], []
    for p in participants:
        sub = cleaned[cleaned["participant"] == p]
        for theta, axis, R in conditions:
            jobs.append(delayed(_featurize_task)(sub, R))
            keys.append((p, theta, axis))
    t0 = time.perf_counter()
    featurized = Parallel(n_jobs=n_jobs)(jobs)
    logger.info("Rotated featurization took %.0f s", time.perf_counter() - t0)
    test_feats = dict(zip(keys, featurized, strict=True))

    rows = []
    for p in participants:
        train0 = feat0[feat0["participant"] != p]
        logger.info("Fold: held-out %s", p)
        models = {}
        for model_name, v in combos:
            if v == "baseline":
                train, fs = train0, base_fs
            elif v == "R1":
                train, fs = train0, mag_fs
            else:
                copies = aug[v]
                train, fs = pd.concat([train0, copies[copies["participant"] != p]]), base_fs
            models[(model_name, v)] = lopo.build_pipeline(fs, model_name).fit(
                train[list(fs.columns)], train["label"]
            )
        for theta, axis, _ in conditions:
            ft = test_feats[(p, theta, axis)]
            y = ft["label"].to_numpy()
            sets = ft["set"].to_numpy()
            for (model_name, variant), model in models.items():
                fs = mag_fs if variant == "R1" else base_fs
                pred = np.asarray(model.predict(ft[list(fs.columns)]))
                row = fold_row(variant, theta, axis, p, y, pred, sets, classes)
                rows.append({"model": model_name, **row})
    return pd.DataFrame(rows)


# --------------------------------------------------------------------------- reporting
MODEL_ORDER = ("NN", "KNN", "RF", "DT", "NB")


def combos_in(table):
    """(model, variant) pairs present in ``table``: models in MODEL_ORDER, variants in VARIANTS."""
    present = set(zip(table["model"], table["variant"], strict=True))
    models = sorted(
        {m for m, _ in present},
        key=lambda m: (MODEL_ORDER.index(m), m) if m in MODEL_ORDER else (len(MODEL_ORDER), m),
    )
    return [(m, v) for m in models for v in VARIANTS if (m, v) in present]


def summarize_tables(table):
    """Per (model, variant): worst-signed-axis rows and random-axis means, per theta."""
    out = {}
    thetas = sorted(table["theta"].unique())
    for model, v in combos_in(table):
        t = table[(table["variant"] == v) & (table["model"] == model)]
        rows = []
        for theta in thetas:
            sub = t[t["theta"] == theta]
            if theta == 0:
                s = sub.iloc[0]
                worst_axis, worst_set = "none", s["set_acc"]
                bench, ohp = s["recall_bench"], s["recall_ohp"]
                min_bench, min_ohp = bench, ohp
                rand = sub
                conf = s["bench_ohp_confusion"]
            else:
                signed = sub[sub["axis"].isin(SIGNED_AXES)]
                w = signed.loc[signed["set_acc"].idxmin()]
                worst_axis, worst_set = w["axis"], w["set_acc"]
                bench, ohp = w["recall_bench"], w["recall_ohp"]
                min_bench, min_ohp = signed["recall_bench"].min(), signed["recall_ohp"].min()
                rand = sub[sub["axis"].str.startswith("rand")]
                conf = w["bench_ohp_confusion"]
            rows.append(
                {
                    "theta": theta,
                    "kabsch_g": kabsch_residual_g(theta),
                    "worst_axis": worst_axis,
                    "worst_set_acc": worst_set,
                    "worst_axis_bench": bench,
                    "worst_axis_ohp": ohp,
                    "worst_axis_confusion": conf,
                    "min_bench_any_signed": min_bench,
                    "min_ohp_any_signed": min_ohp,
                    "rand_mean_set_acc": rand["set_acc"].mean(),
                    "rand_mean_bench": rand["recall_bench"].mean(),
                    "rand_mean_ohp": rand["recall_ohp"].mean(),
                    "rand_mean_confusion": rand["bench_ohp_confusion"].mean(),
                }
            )
        out[(model, v)] = pd.DataFrame(rows)
    return out


def make_plot(tables, path):
    """One panel per model (shared y axis); colours are variants."""
    models = list(dict.fromkeys(m for m, _ in tables))
    fig, axes = plt.subplots(
        1,
        len(models),
        figsize=(7 * len(models) if len(models) > 1 else 7, 4.2),
        dpi=100,
        sharey=True,
        squeeze=False,
    )
    colors = {
        "baseline": "#1f77b4",
        "R1": "#2ca02c",
        "R2": "#d62728",
        "R2w": "#ff7f0e",
        "R2w8": "#9467bd",
    }
    for ax, model in zip(axes[0], models, strict=True):
        for (m, v), t in tables.items():
            if m != model:
                continue
            ax.plot(t["theta"], t["worst_set_acc"], "-o", color=colors[v], label=f"{v} worst axis")
            ax.plot(
                t["theta"], t["rand_mean_set_acc"], "--s", color=colors[v], label=f"{v} random mean"
            )
        ax.set_xlabel("rotation of held-out participant (deg)")
        ax.set_title(f"Rotation sensitivity: {model}")
        ax.grid(alpha=0.3)
        ax.legend(fontsize=7, ncol=3)
    axes[0][0].set_ylabel("set accuracy (mean over LOPO folds)")
    fig.tight_layout()
    path.parent.mkdir(parents=True, exist_ok=True)
    fig.savefig(path)
    plt.close(fig)


def render_report(table, tables, tols, meta):
    lines = ["# Phase 1: rotation sensitivity (S2a)", ""]
    lines += [
        "> Generated by `python -m workout_ml.analysis.rotation_sensitivity`; do not edit.",
        f"> {meta}",
        "",
        "The held-out participant's acc and gyro columns are rotated by the same R before "
        "feature engineering. Models are trained on unrotated data, except the augmented "
        "variants. The model is a parameter (NN, KNN, RF; hyper-parameters as in the main "
        "pipeline); every (model, variant) pair uses the same seeds, rotation conditions and "
        "tolerance definition. Augmentation method: the training set is the original (unrotated) training "
        "sets PLUS K rotated copies of them (not K copies only); each copy of each set gets "
        "its own random R (uniform random axis, angle uniform in [0, max]), applied to acc "
        "and gyro before feature engineering; same seed for every augmented variant. "
        + "; ".join(f"{n}: K={k}, max {d:g} deg" for n, (k, d) in AUG_VARIANTS.items())
        + ". Metrics are means over the 5 LOPO folds. "
        "'Worst axis' = the signed axis (+-x, +-y, +-z) with the lowest set accuracy at that "
        f"theta; 'rand' = mean over {N_RANDOM_AXES} seeded random axes. 'Kabsch' is the "
        "equivalent gravity residual 2 sin(theta/2) in g. 'conf' is the pooled "
        "bench->OHP + OHP->bench window rate over bench and OHP windows.",
        "",
        "## theta_tol (section 7.3 definition)",
        "",
        "Largest theta where, over the worst axis, set accuracy drops <= 2 pp and bench and "
        "OHP recall each drop <= 5 pp vs theta = 0. 'signed' considers the six signed axes; "
        "'all' also includes the random axes.",
        "",
        "| model | variant | theta_tol (signed axes) | theta_tol (all axes) |",
        "|---|---|---|---|",
    ]
    combos = list(tables)
    for m, v in combos:
        lines.append(f"| {m} | {v} | {tols[(m, v)]['signed']:g} | {tols[(m, v)]['all']:g} |")
    lines += ["", "## Metrics at theta = 0", ""]
    lines += [
        "| model | variant | set acc | window macro-F1 | bench recall | OHP recall | conf |",
        "|---|---|---|---|---|---|---|",
    ]
    for m, v in combos:
        r = table[(table["model"] == m) & (table["variant"] == v) & (table["theta"] == 0)].iloc[0]
        lines.append(
            f"| {m} | {v} | {r['set_acc']:.3f} | {r['window_macro_f1']:.3f} | "
            f"{r['recall_bench']:.3f} | {r['recall_ohp']:.3f} | {r['bench_ohp_confusion']:.3f} |"
        )
    for m, v in combos:
        t = tables[(m, v)]
        lines += ["", f"## {m} / {v}", ""]
        lines += [
            "| theta | Kabsch (g) | worst axis | set acc | bench | OHP | conf "
            "| min bench (signed) | min OHP (signed) | rand set acc | rand bench "
            "| rand OHP | rand conf |",
            "|---|---|---|---|---|---|---|---|---|---|---|---|---|",
        ]
        for _, r in t.iterrows():
            lines.append(
                f"| {r['theta']:g} | {r['kabsch_g']:.3f} | {r['worst_axis']} | "
                f"{r['worst_set_acc']:.3f} | {r['worst_axis_bench']:.3f} | "
                f"{r['worst_axis_ohp']:.3f} | {r['worst_axis_confusion']:.3f} | "
                f"{r['min_bench_any_signed']:.3f} | {r['min_ohp_any_signed']:.3f} | "
                f"{r['rand_mean_set_acc']:.3f} | {r['rand_mean_bench']:.3f} | "
                f"{r['rand_mean_ohp']:.3f} | {r['rand_mean_confusion']:.3f} |"
            )
    lines += ["", "![set accuracy vs rotation](phase1-rotation-sensitivity.png)", ""]
    return "\n".join(lines)


def _drift(table, variant):
    t = table[table["variant"] == variant]
    return {m: float(t[m].max() - t[m].min()) for m in ("set_acc", "window_macro_f1")}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tracking-uri", default=None)
    parser.add_argument("--no-mlflow", action="store_true")
    parser.add_argument("--n-jobs", type=int, default=-1)
    parser.add_argument(
        "--models",
        nargs="+",
        default=list(DEFAULT_MODELS),
        choices=sorted(lopo.MODEL_SPECS),
        help="models to evaluate (cached results of other models are kept and reported)",
    )
    parser.add_argument(
        "--variants",
        nargs="+",
        default=list(VARIANTS),
        choices=list(VARIANTS),
        help="variants to evaluate for the selected models",
    )
    parser.add_argument(
        "--fresh", action="store_true", help="ignore cached per-fold results and rerun everything"
    )
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    warnings.filterwarnings("ignore")
    start = time.perf_counter()
    config.ensure_dirs()
    sha, dirty = tracking.git_info()  # before any output file is written

    cached = pd.DataFrame()
    if not args.fresh and FOLDS_CSV_PATH.exists():
        cached = pd.read_csv(FOLDS_CSV_PATH)
        if "model" not in cached.columns:  # written before the model parameter existed: NN
            cached.insert(0, "model", MODEL)
        cached = cached[cached["variant"].isin(VARIANTS)]
    have = set(zip(cached.get("model", []), cached.get("variant", []), strict=False))
    wanted = [(m, v) for m in args.models for v in VARIANTS if v in args.variants]
    todo = [c for c in wanted if c not in have]
    logger.info("Reused from %s: %s; running: %s", FOLDS_CSV_PATH.name, sorted(have), todo)
    cleaned = load_cleaned()
    new = run_analysis(cleaned, n_jobs=args.n_jobs, combos=todo) if todo else pd.DataFrame()
    runtime = time.perf_counter() - start
    folds = pd.concat([cached, new], ignore_index=True)
    folds.to_csv(FOLDS_CSV_PATH, index=False)  # (model, variant) keyed cache
    table = aggregate(folds)
    combos = combos_in(table)
    order = {c: i for i, c in enumerate(combos)}
    table = table.assign(_o=[order[c] for c in zip(table["model"], table["variant"], strict=True)])
    table = table.sort_values("_o", kind="stable").drop(columns="_o").reset_index(drop=True)
    CSV_PATH.parent.mkdir(parents=True, exist_ok=True)
    table.to_csv(CSV_PATH, index=False)

    tables = summarize_tables(table)
    tols = {
        (m, v): {
            "signed": theta_tol(table, v, axes=set(SIGNED_AXES), model=m),
            "all": theta_tol(table, v, model=m),
        }
        for m, v in combos
    }
    make_plot(tables, PLOT_PATH)
    todo_names = ", ".join(f"{m}/{v}" for m, v in todo) or "none"
    meta = (
        f"Code {sha[:8]} (dirty={dirty}), data md5 {tracking.data_md5()}, "
        f"computed this invocation: {todo_names} ({runtime:.0f} s); "
        "other (model, variant) pairs reused from the cached per-fold results of the same "
        "protocol, seeds and data."
    )
    REPORT_PATH.write_text(render_report(table, tables, tols, meta), encoding="utf-8")

    for m, v in combos:
        r0 = tables[(m, v)].iloc[0]
        logger.info("%s/%s theta=0: set_acc %.4f", m, v, r0["worst_set_acc"])
    if (MODEL, "R1") in tables:
        logger.info("R1 drift (max-min over all conditions): %s", _drift(table, "R1"))
    logger.info("theta_tol: %s", tols)

    run_ids = {}
    if not args.no_mlflow:
        import mlflow

        tracking.setup_tracking(args.tracking_uri, experiment=EXPERIMENT)
        for m, v in todo:  # only pairs computed in this invocation get a new run
            with mlflow.start_run(run_name=v if m == MODEL else f"{m}/{v}") as run:
                mlflow.set_tags(
                    {"git_sha": sha, "git_dirty": dirty, "data_md5": tracking.data_md5()}
                )
                mlflow.log_params(
                    {
                        "variant": v,
                        "model": m,
                        "feature_set": "magnitude" if v == "R1" else BASELINE_SET,
                        "thetas": str(THETAS),
                        "n_random_axes": N_RANDOM_AXES,
                        "axis_seed": AXIS_SEED,
                        "aug_k": AUG_VARIANTS[v][0] if v in AUG_VARIANTS else 0,
                        "aug_max_deg": AUG_VARIANTS[v][1] if v in AUG_VARIANTS else 0,
                        "aug_seed": AUG_SEED if v in AUG_VARIANTS else "none",
                    }
                )
                tracking.log_flat_params("hp", lopo.model_hyperparameters(m))
                mlflow.log_metrics(
                    {
                        "theta_tol_signed": tols[(m, v)]["signed"],
                        "theta_tol_all": tols[(m, v)]["all"],
                        "set_acc_theta0": float(tables[(m, v)].iloc[0]["worst_set_acc"]),
                    }
                )
                with tempfile.TemporaryDirectory() as tmp:
                    p = Path(tmp) / f"per_theta_{m}_{v}.csv"
                    tables[(m, v)].to_csv(p, index=False)
                    mlflow.log_artifact(str(p))
                    p2 = Path(tmp) / f"all_conditions_{m}_{v}.csv"
                    sel = (table["variant"] == v) & (table["model"] == m)
                    table[sel].to_csv(p2, index=False)
                    mlflow.log_artifact(str(p2))
                mlflow.log_artifact(str(PLOT_PATH))
                run_ids[f"{m}/{v}"] = run.info.run_id
        logger.info("MLflow run ids: %s", run_ids)
    logger.info("Done in %.0f s", time.perf_counter() - start)
    return table, tables, tols, run_ids


if __name__ == "__main__":
    main()
