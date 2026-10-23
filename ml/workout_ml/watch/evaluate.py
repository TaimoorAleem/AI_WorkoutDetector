"""Phase 3 verdict: fit R on the fit set, evaluate models trained on MetaMotion on the watch.

Usage (spec 3.4):
    python -m workout_ml.watch.evaluate --fit watch-fit-v1 --eval watch-val-v1 [--write-profile]
    python -m workout_ml.watch.evaluate --in-sample watch-fit-v1     # optimistic first look

Models (all trained on all MetaMotion data): M1 KNN + rotation augmentation (production
candidate), M2 NN + augmentation, M3 KNN without augmentation, M4 CNN (optional: needs
``workout_ml.models.cnn.train_watch_predictor(cleaned_mm_df, seed=...)`` returning an object with
``predict_windows(cleaned_df) -> DataFrame[set, pred]``; skipped when the module or hook is absent).
"""

from __future__ import annotations

import argparse
import json
import logging
import tempfile
import warnings
from dataclasses import dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless

import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402

from workout_ml import config, tracking  # noqa: E402
from workout_ml.augment import augment_sets, engineer_augmented  # noqa: E402
from workout_ml.models import lopo  # noqa: E402
from workout_ml.reps.counter import count_set  # noqa: E402
from workout_ml.sensors import load_profile  # noqa: E402
from workout_ml.watch import metrics as M  # noqa: E402
from workout_ml.watch.calibrate import (  # noqa: E402
    FITTED_PROFILE_ID,
    PRIOR_PROFILE_ID,
    RESIDUAL_GATE_G,
    fit_rotation,
    fitted_profile,
    identity_profile,
    write_fitted_profile,
)
from workout_ml.watch.common import (  # noqa: E402
    engineer,
    load_metamotion_cleaned,
    watch_frame,
)
from workout_ml.watch.datasets import dataset_paths, dataset_session_ids  # noqa: E402
from workout_ml.watch.diagnostics import domain_classifier, feature_psi  # noqa: E402
from workout_ml.watch.ingest import calibration_dataset_check, session_quality  # noqa: E402

logger = logging.getLogger(__name__)

EXPERIMENT = "proprio-domain-shift"
FEATURE_SET = "basic+derived+pca+temporal"
MODEL_DEFS = {
    "M1": {"model": "KNN", "augment": True, "desc": "KNN + rotation augmentation (candidate)"},
    "M2": {"model": "NN", "augment": True, "desc": "NN + rotation augmentation"},
    "M3": {"model": "KNN", "augment": False, "desc": "KNN, no augmentation"},
    "M4": {"model": "CNN", "augment": None, "desc": "CNN (task C1, optional)"},
}
DEFAULT_MODELS = ("M1", "M2", "M3", "M4")
DEFAULT_BASELINES = {"M1": 0.986}  # current-baseline.md: KNN + augmentation, LOPO set accuracy
PROFILES = ("fitted", "prior")
REPORT_DIR = config.PROJECT_ROOT / "docs" / "reports"
SEED = lopo.SEED


# --------------------------------------------------------------------------- models


class ModelBank:
    """Trains each model once on all MetaMotion data; features and augmentation are cached."""

    def __init__(self, mm_clean: pd.DataFrame, aug_cfg: dict | None = None):
        self.mm_clean = mm_clean
        self.aug_cfg = dict(aug_cfg or config.EVAL_CONFIG["augmentation"])
        self._feat = None
        self._aug = None
        self._fitted: dict = {}

    @property
    def feat(self) -> pd.DataFrame:
        if self._feat is None:
            self._feat = engineer(self.mm_clean)
        return self._feat

    @property
    def feature_set(self):
        sets = {fs.name: fs for fs in lopo.build_feature_sets(self.feat.columns)}
        return sets[FEATURE_SET]

    @property
    def aug_feat(self) -> pd.DataFrame:
        if self._aug is None:
            c = self.aug_cfg
            self._aug = engineer_augmented(
                augment_sets(self.mm_clean, c["copies"], c["max_deg"], c["seed"]), engineer
            )
        return self._aug

    def _train_sklearn(self, name: str):
        spec = MODEL_DEFS[name]
        cols = list(self.feature_set.columns)
        X, y = self.feat[cols], self.feat["label"].to_numpy()
        if spec["augment"]:
            X = pd.concat([X, self.aug_feat[cols]])
            y = np.concatenate([y, self.aug_feat["label"].to_numpy()])
        return lopo.build_pipeline(self.feature_set, spec["model"]).fit(X, y)

    def get(self, name: str):
        if name not in self._fitted:
            if name == "M4":
                self._fitted[name] = _train_cnn(self.mm_clean)
            else:
                self._fitted[name] = self._train_sklearn(name)
        return self._fitted[name]

    def predict(self, name: str, feat: pd.DataFrame, cleaned: pd.DataFrame) -> pd.DataFrame:
        """Per-window predictions as a DataFrame with ``set`` and ``pred``."""
        model = self.get(name)
        if name == "M4":
            out = model.predict_windows(cleaned)
            return pd.DataFrame({"set": np.asarray(out["set"]), "pred": np.asarray(out["pred"])})
        cols = list(self.feature_set.columns)
        return pd.DataFrame({"set": feat["set"].to_numpy(), "pred": model.predict(feat[cols])})


def cnn_available() -> bool:
    try:
        from workout_ml.models import cnn
    except Exception:  # absent module, or its optional dependency (PyTorch) is not installed
        return False
    return hasattr(cnn, "train_watch_predictor")


def _train_cnn(mm_clean):
    from workout_ml.models import cnn

    return cnn.train_watch_predictor(mm_clean, seed=SEED)


# --------------------------------------------------------------------------- one (model, profile)


@dataclass
class ProfileData:
    """The evaluation dataset converted with one profile."""

    name: str
    profile: object
    cleaned: pd.DataFrame
    feat: pd.DataFrame
    segs: pd.DataFrame  # one row per set
    reps: pd.DataFrame  # one row per confirmed lift set


def _segments(cleaned: pd.DataFrame) -> pd.DataFrame:
    cols = ["kind", "label", "confirmed", "reps_user", "session_id", "participant"]
    return cleaned.groupby("set", sort=True)[cols].first()


def rep_rows(cleaned: pd.DataFrame, segs: pd.DataFrame) -> pd.DataFrame:
    """Rep counter on confirmed lift sets (true exercise; a counter failure counts as 0 reps)."""
    rows = []
    for s, row in segs[(segs["kind"] == "set") & (segs["confirmed"])].iterrows():
        set_df = cleaned[cleaned["set"] == s].sort_index(kind="stable")
        try:
            n = count_set(set_df, row["label"]).count
        except Exception as e:  # too short for the filter, unknown exercise, ...
            logger.warning("rep counter failed on %s: %s", s, e)
            n = 0
        rows.append(
            {"set": s, "label": row["label"], "predicted": n, "reps_user": row["reps_user"]}
        )
    return pd.DataFrame(rows, columns=["set", "label", "predicted", "reps_user"])


def prepare_profile(name, profile, dataset, **kw) -> ProfileData:
    cleaned = watch_frame(dataset, profile, **kw)
    if cleaned.empty:
        raise ValueError(f"dataset {dataset!r} has no usable segments")
    segs = _segments(cleaned)
    return ProfileData(
        name=name,
        profile=profile,
        cleaned=cleaned,
        feat=engineer(cleaned),
        segs=segs,
        reps=rep_rows(cleaned, segs),
    )


def window_frame(bank: ModelBank, model: str, pdata: ProfileData) -> pd.DataFrame:
    pred = bank.predict(model, pdata.feat, pdata.cleaned)
    segs = pdata.segs
    pred["kind"] = pred["set"].map(segs["kind"]).to_numpy()
    pred["truth"] = pred["set"].map(segs["label"]).to_numpy()
    return pred


def evaluate_row(win, pdata, tau, *, baseline=None, calibration_status=M.NA) -> dict:
    """All metrics and gate statuses for one (model, profile) on one dataset."""
    dec = M.segment_decisions(win, tau)
    wm = M.window_metrics(win)
    reps = M.rep_summary(pdata.reps)
    gates = M.evaluate_gates(
        dec=dec, win=win, wm=wm, reps=reps, baseline=baseline, calibration_status=calibration_status
    )
    lift = dec[dec["kind"] == "set"]
    raw_acc = float((lift["vote"] == lift["truth"]).mean()) if len(lift) else float("nan")
    return {
        "tau": tau,
        "n_lift_sets": len(lift),
        "n_rest": int((dec["kind"] == "rest").sum()),
        "n_other": int((dec["kind"] == "other").sum()),
        "set_acc": gates["set_accuracy"]["value"],
        "set_acc_ci": gates["set_accuracy"]["ci"],
        "set_acc_raw_vote": raw_acc,
        "window": wm,
        "bench_ohp": M.bench_ohp(win, dec),
        "rejection": M.rejection(dec),
        "reps": reps,
        "gates": gates,
        "decisions": dec,
    }


def rotation_residual(R, fit) -> float:
    """Weighted RMS residual (g) of an arbitrary R on the fit's per-exercise vectors."""
    w = np.array([fit.weights[e] for e in fit.exercises])
    A = np.array([fit.watch_vectors[e] for e in fit.exercises])
    B = np.array([fit.mm_vectors[e] for e in fit.exercises])
    res = np.linalg.norm(A @ np.asarray(R).T - B, axis=1)
    return float(np.sqrt(np.sum(w * res**2) / w.sum()))


def calibration_status(calib: dict | None, residual_g: float | None) -> str:
    """Calibration gate: calibration_check on watch-calib-v1 plus Kabsch residual < 0.15 g."""
    if calib is None or residual_g is None or not calib.get("sessions"):
        return M.NA
    ok = bool(calib["passed"]) and residual_g < RESIDUAL_GATE_G
    return M.PASS if ok else M.FAIL


def lopo_baselines(models, explicit=None, tracking_uri=None) -> dict:
    """LOPO set-accuracy baselines per model: explicit > latest MLflow pipeline run > defaults."""
    out = dict(DEFAULT_BASELINES)
    try:
        import mlflow

        uri = tracking_uri
        if uri:
            mlflow.set_tracking_uri(uri)
        exp = mlflow.get_experiment_by_name(config.MLFLOW_EXPERIMENT)
        if exp is not None:
            runs = mlflow.search_runs(
                [exp.experiment_id], order_by=["attributes.start_time DESC"], max_results=500
            )
            for name in models:
                spec = MODEL_DEFS[name]
                if spec["augment"] is None or "params.model" not in runs:
                    continue
                tag = "on" if spec["augment"] else "off"
                sel = runs[
                    (runs.get("params.model") == spec["model"])
                    & (runs.get("params.feature_set") == FEATURE_SET)
                    & (runs.get("tags.augmentation") == tag)
                ].dropna(subset=["metrics.lopo_set_acc_mean"])
                if len(sel):
                    out[name] = float(sel.iloc[0]["metrics.lopo_set_acc_mean"])
    except Exception as e:  # tracking store missing or unreadable: keep defaults
        logger.warning("could not read LOPO baselines from MLflow (%s); using defaults", e)
    out.update(explicit or {})
    return out


# --------------------------------------------------------------------------- run


def _no_data(status: str, **extra) -> dict:
    print(status)
    return {"status": status, **extra}


def run(
    fit,
    eval_=None,
    *,
    in_sample: bool = False,
    models=DEFAULT_MODELS,
    tau: float | None = None,
    tau_default: float = M.DEFAULT_TAU,
    write_profile: bool = False,
    calib="watch-calib-v1",
    mm_df: pd.DataFrame | None = None,
    tracking_uri: str | None = None,
    artifact_root=None,
    report_path=None,
    metrics_out=None,
    watch_root=None,
    datasets_file=None,
    aug_cfg: dict | None = None,
    baselines: dict | None = None,
    log_mlflow: bool = True,
) -> dict:
    """Fit R on ``fit``, evaluate on ``eval_`` (or on ``fit`` in-sample). Returns a result dict.

    ``status`` is ``"ok"``, or ``"no gym sessions yet"`` when the datasets are empty.
    """
    kw = {"root": watch_root, "datasets_file": datasets_file}
    git_sha, git_dirty = tracking.git_info()  # recorded before any artifact is written
    eval_ = fit if in_sample else eval_
    if eval_ is None:
        raise ValueError("pass an eval dataset, or in_sample=True")
    fit_paths = dataset_paths(fit, **kw)
    eval_paths = dataset_paths(eval_, **kw)
    if not fit_paths:
        return _no_data("no gym sessions yet", fit=str(fit))
    if not eval_paths:
        return _no_data("no gym sessions yet", eval=str(eval_), fit=str(fit))
    warnings.filterwarnings("ignore", category=UserWarning, module="sklearn")

    mm = mm_df if mm_df is not None else load_metamotion_cleaned()
    prior = load_profile(PRIOR_PROFILE_ID)
    watch_i = watch_frame(fit, identity_profile(prior), **kw)
    if watch_i.empty:
        return _no_data("no gym sessions yet", fit=str(fit))
    fit_res = fit_rotation(mm, watch_i, prior.rotation)
    fit_ids = dataset_session_ids(fit, datasets_file)
    fitted = fitted_profile(fit_res, fit_ids, prior)
    profiles = {"fitted": fitted, "prior": prior}

    bank = ModelBank(mm, aug_cfg)
    mode = "in-sample" if in_sample else "held-out"
    models = [m for m in models if m in MODEL_DEFS]
    skipped = {}
    if "M4" in models and not cnn_available():
        models.remove("M4")
        skipped["M4"] = "workout_ml.models.cnn.train_watch_predictor not available"

    # tau: chosen on the fit set only (M1 + fitted profile), then frozen for the eval set
    tau_info = {"tau": tau, "reason": "given", "curve": {}}
    fit_pd = prepare_profile("fitted", fitted, fit, **kw)
    if tau is None:
        ref_model = "M1" if "M1" in models else models[0]
        tau_info = M.select_tau(window_frame(bank, ref_model, fit_pd), default=tau_default)
        tau = tau_info["tau"]

    pdata = {}
    for pname, prof in profiles.items():
        pdata[pname] = (
            fit_pd
            if (pname == "fitted" and eval_ == fit)
            else prepare_profile(pname, prof, eval_, **kw)
        )

    calib_res = None
    try:
        calib_res = calibration_dataset_check(calib, root=watch_root, datasets_file=datasets_file)
    except (KeyError, FileNotFoundError) as e:
        logger.warning("calibration check unavailable: %s", e)
    base = lopo_baselines(models, baselines, tracking_uri)

    rows = {}
    for m in models:
        for pname in PROFILES:
            res_g = rotation_residual(profiles[pname].rotation, fit_res)
            cs = calibration_status(calib_res, res_g)
            win = window_frame(bank, m, pdata[pname])
            r = evaluate_row(win, pdata[pname], tau, baseline=base.get(m), calibration_status=cs)
            r["residual_g"] = res_g
            r["win"] = win
            rows[(m, pname)] = r

    diag = _diagnostics(bank, fit_res, pdata["fitted"], fitted, in_sample)
    quality = session_quality(
        list(dict.fromkeys([*fit_paths, *eval_paths, *_calib_paths(calib, **kw)]))
    )
    ctx = {
        "mode": mode,
        "in_sample": in_sample,
        "fit": fit if isinstance(fit, str) else "paths",
        "eval": eval_ if isinstance(eval_, str) else "paths",
        "fit_ids": fit_ids,
        "eval_ids": dataset_session_ids(eval_, datasets_file),
        "git_sha": git_sha,
        "git_dirty": git_dirty,
        "profiles": profiles,
        "fit_res": fit_res,
        "tau_info": tau_info,
        "tau": tau,
        "rows": rows,
        "models": models,
        "skipped": skipped,
        "baselines": base,
        "calib": calib_res,
        "diag": diag,
        "quality": quality,
        "mm_sets": int(mm["set"].nunique()),
        "eval_skipped": pdata["fitted"].cleaned.attrs.get("skipped", []),
    }

    run_ids = {}
    if log_mlflow:
        run_ids = _log_mlflow(ctx, tracking_uri, artifact_root)
    ctx["run_ids"] = run_ids

    report_path = Path(
        report_path
        or REPORT_DIR
        / ("phase3-domain-shift-insample.md" if in_sample else "phase3-domain-shift.md")
    )
    report = render_report(ctx)
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    if log_mlflow and run_ids.get("parent"):
        import mlflow

        with mlflow.start_run(run_id=run_ids["parent"]):
            mlflow.log_artifact(str(report_path))
    if write_profile:
        out = write_fitted_profile(fit_res, fit_ids)
        ctx["profile_written"] = str(out)
    flat = flat_metrics(ctx)
    if metrics_out:
        Path(metrics_out).parent.mkdir(parents=True, exist_ok=True)
        Path(metrics_out).write_text(json.dumps(flat, indent=2) + "\n", encoding="utf-8")
    ctx.update(status="ok", report_path=str(report_path), flat=flat)
    return ctx


def _calib_paths(calib, **kw):
    try:
        return dataset_paths(calib, **kw)
    except (KeyError, FileNotFoundError):
        return []


def _diagnostics(bank: ModelBank, fit_res, pd_fit: ProfileData, fitted, in_sample) -> dict:
    cols = [c for c in bank.feature_set.columns]
    X_mm = bank.feat
    X_w = pd_fit.feat
    psi_df = feature_psi(X_mm, X_w, cols, top=10)
    watch_groups = X_w["set"].map(pd_fit.segs["session_id"]).astype(str).to_numpy()
    grouping = "session"
    if len(np.unique(watch_groups)) < 2:
        watch_groups, grouping = (
            X_w["set"].astype(str).to_numpy(),
            "set (single session: optimistic)",
        )
    clf = domain_classifier(
        X_mm, X_mm["participant"].to_numpy(), X_w, watch_groups, cols, watch_grouping=grouping
    )
    # gravity vectors per exercise before/after R*
    grav = []
    R = fit_res.R
    for e in fit_res.exercises:
        w = np.array(fit_res.watch_vectors[e])
        grav.append(
            {
                "label": e,
                "watch_frame": w.tolist(),
                "watch_after_R": (R @ w).tolist(),
                "metamotion": fit_res.mm_vectors[e],
            }
        )
    return {"psi": psi_df, "domain": clf, "gravity": grav}


# --------------------------------------------------------------------------- tracking


def _log_mlflow(ctx, tracking_uri, artifact_root) -> dict:
    import mlflow

    tracking.setup_tracking(tracking_uri, artifact_root, experiment=EXPERIMENT)
    ids = {"children": {}}
    fit_res = ctx["fit_res"]
    with mlflow.start_run(
        run_name=f"domain-shift-{ctx['mode']}",
        tags={
            "git.sha": ctx["git_sha"],
            "git.dirty": ctx["git_dirty"],
            "mode": ctx["mode"],
            "phase": "3",
            "datasets.fit": str(ctx["fit"]),
            "datasets.eval": str(ctx["eval"]),
            "profile.fitted": ctx["profiles"]["fitted"].transform_id,
            "profile.prior": ctx["profiles"]["prior"].transform_id,
        },
    ) as parent:
        ids["parent"] = parent.info.run_id
        mlflow.log_params({"tau": ctx["tau"], "models": ",".join(ctx["models"])})
        mlflow.log_metrics(
            {
                "kabsch_residual_g": fit_res.residual_rms_g,
                "angle_from_prior_deg": fit_res.angle_from_prior_deg,
                "lever_min_g": fit_res.lever_min_g,
                **(
                    {"gyro_profile_r": fit_res.gyro_profile_r}
                    if fit_res.gyro_profile_r is not None
                    else {}
                ),
                "domain_auc": ctx["diag"]["domain"]["auc"],
            }
        )
        with tempfile.TemporaryDirectory() as tmp:
            p = write_fitted_profile(
                fit_res, ctx["fit_ids"], path=Path(tmp) / f"{FITTED_PROFILE_ID}.yaml"
            )
            mlflow.log_artifact(str(p))
    for (m, pname), r in ctx["rows"].items():
        with mlflow.start_run(
            run_id=None,
            run_name=f"{m}|{pname}",
            nested=True,
            parent_run_id=ids["parent"],
            tags={
                f"gate.{k}": M.suffix_status(v["status"], ctx["in_sample"])
                for k, v in r["gates"].items()
            }
            | {"model": m, "profile": pname, "mode": ctx["mode"]},
        ) as child:
            mlflow.log_metrics(_numeric(r))
            ids["children"][f"{m}|{pname}"] = child.info.run_id
    return ids


def _numeric(r: dict) -> dict:
    out = {
        "set_acc": r["set_acc"],
        "set_acc_raw_vote": r["set_acc_raw_vote"],
        "window_macro_f1": r["window"]["macro_f1"],
        "window_acc": r["window"]["window_acc"],
        "rejection_rate": r["rejection"]["rate"],
        "n_lift_sets": r["n_lift_sets"],
        "residual_g": r["residual_g"],
    }
    ov = r["reps"]["overall"]
    if ov:
        out.update(reps_mae=ov["mae"], reps_exact=ov["exact"], reps_within1=ov["within1"])
    for c, v in r["window"]["recall"].items():
        out[f"recall_{c}"] = v["recall"]
    return {k: float(v) for k, v in out.items() if v is not None and np.isfinite(v)}


def flat_metrics(ctx) -> dict:
    """Flat JSON for DVC metrics: per model x profile numbers and gate statuses."""
    out = {
        "mode": ctx["mode"],
        "tau": ctx["tau"],
        "kabsch_residual_g": ctx["fit_res"].residual_rms_g,
        "angle_from_prior_deg": ctx["fit_res"].angle_from_prior_deg,
        "gyro_profile_r": ctx["fit_res"].gyro_profile_r,
        "domain_auc": ctx["diag"]["domain"]["auc"],
        "rows": {},
    }
    for (m, pname), r in ctx["rows"].items():
        out["rows"][f"{m}|{pname}"] = {
            **_numeric(r),
            "gates": {
                k: M.suffix_status(v["status"], ctx["in_sample"]) for k, v in r["gates"].items()
            },
        }
    return out


# --------------------------------------------------------------------------- report


def _fmt(v, d=3):
    return "n/a" if v is None or (isinstance(v, float) and not np.isfinite(v)) else f"{v:.{d}f}"


def _ci(ci):
    return f"[{_fmt(ci[0], 2)}, {_fmt(ci[1], 2)}]"


def _matrix(R):
    return "\n".join("    " + "  ".join(f"{v:+.4f}" for v in row) for row in np.asarray(R))


def render_report(ctx: dict) -> str:
    from workout_ml.reporting import md_table

    ins = ctx["in_sample"]
    tag = " **IN-SAMPLE (optimistic)**" if ins else ""
    fr = ctx["fit_res"]
    L = []
    title = "Phase 3 domain-shift report" + (" (in-sample first look)" if ins else "")
    L += [f"# {title}", ""]
    if ins:
        L += [
            "> **IN-SAMPLE (optimistic).** R is fitted and the models are evaluated on the same "
            "session(s). Every number below is optimistic and every gate status is suffixed "
            "'(in-sample)'. The real verdict needs `--fit watch-fit-v1 --eval watch-val-v1`.",
            "",
        ]
    L += [
        "> Generated by `python -m workout_ml.watch.evaluate`. Do not edit by hand.",
        "",
        f"- Mode: {ctx['mode']}{tag}",
        f"- Git: `{ctx['git_sha']}` ({'dirty' if ctx['git_dirty'] == 'True' else 'clean' if ctx['git_dirty'] == 'False' else ctx['git_dirty']})",
        f"- Fit dataset: `{ctx['fit']}` sessions {', '.join(s[:8] for s in ctx['fit_ids']) or '-'}",
        f"- Eval dataset: `{ctx['eval']}` sessions {', '.join(s[:8] for s in ctx['eval_ids']) or '-'}",
        f"- MetaMotion training sets: {ctx['mm_sets']}",
        f"- Profiles: fitted `{ctx['profiles']['fitted'].profile_id}` (transform `{ctx['profiles']['fitted'].transform_id}`), "
        f"prior `{ctx['profiles']['prior'].profile_id}` (transform `{ctx['profiles']['prior'].transform_id}`)",
        f"- tau (vote-share threshold): {ctx['tau']:.2f} ({ctx['tau_info']['reason']}; chosen on the fit set, frozen for the eval set)",
        f"- MLflow runs: parent `{ctx['run_ids'].get('parent', '-')}`; models "
        + (
            ", ".join(f"{k} `{v[:8]}`" for k, v in ctx["run_ids"].get("children", {}).items())
            or "-"
        ),
    ]
    for m, why in ctx["skipped"].items():
        L.append(f"- {m} skipped: {why}")
    L.append("")

    # calibration
    L += ["## Calibration", ""]
    calib = ctx["calib"]
    if calib and calib["sessions"]:
        for s in calib["sessions"]:
            st = s.get("static", {})
            L += [
                f"Session `{s['session_id'][:8]}`: |a| {_fmt(st.get('abs_a_g'))} g (gate 1.00 +- 0.03), "
                f"gyro bias {_fmt(st.get('gyro_bias_norm_dps'), 2)} deg/s (gate < 1), "
                f"poses {'all OK' if s['gates']['poses'] else 'MISMATCH'}, "
                f"gyro sign identifiable axes {', '.join(s['gyro_sign']['identifiable_axes']) or 'none'} "
                f"({'consistent' if s['gates']['gyro_sign'] else 'INCONSISTENT'}). "
                f"Result: {'PASS' if s['passed'] else 'FAIL'}",
                "",
            ]
    else:
        L += ["No calibration session available: calibration gate is N/A.", ""]

    # fit
    det = float(np.linalg.det(fr.R))
    L += [
        "## Rotation fit (weighted Kabsch)",
        "",
        f"Fitted on {', '.join(fr.exercises)} (weights = sets: "
        + ", ".join(f"{e} {int(w)}" for e, w in fr.weights.items())
        + ").",
        "",
        "R* (canonical = R @ watch):",
        "",
        _matrix(fr.R),
        "",
        f"- det(R*) = {det:+.3f}",
        f"- Residual (weighted RMS): {_fmt(fr.residual_rms_g)} g (gate < {RESIDUAL_GATE_G}); "
        + ", ".join(f"{e} {_fmt(r, 2)}" for e, r in fr.residual_per_exercise.items()),
        f"- Angle from prior R: {_fmt(fr.angle_from_prior_deg, 1)} deg",
        f"- Unconstrained fit: det {fr.unconstrained['det']:+.3f}, residual {_fmt(fr.unconstrained['residual_rms_g'])} g"
        + (
            " **PREFERS MIRROR: escalate to the owner**" if fr.flags.get("mirror_preferred") else ""
        ),
        f"- Conditioning: singular values {', '.join(_fmt(s, 3) for s in fr.singular_values)} "
        f"(smallest {_fmt(fr.sigma_min, 3)}); weakest-axis lever {_fmt(fr.lever_min_g)} g",
        f"- Gyro confirmation: variance-profile r = {_fmt(fr.gyro_profile_r, 3)} (gate >= 0.8)",
    ]
    for w in fr.warnings:
        L.append(f"- WARNING: {w}")
    L.append("")

    # gate tables
    L += [f"## Gates{tag}", ""]
    gate_names = ["calibration", "set_accuracy", "macro_f1", "rejection", "reps"]
    hdr = ["model", "profile", "set acc [95% CI]", "macro-F1", *gate_names]
    rows = []
    for (m, pname), r in ctx["rows"].items():
        rows.append(
            [
                m,
                pname,
                f"{_fmt(r['set_acc'])} {_ci(r['set_acc_ci'])} (n={r['n_lift_sets']})",
                _fmt(r["window"]["macro_f1"]),
                *[M.suffix_status(r["gates"][g]["status"], ins) for g in gate_names],
            ]
        )
    L += [md_table(pd.DataFrame(rows, columns=hdr)), ""]
    L += [
        "Set accuracy counts a set rejected by tau as wrong; baselines (MetaMotion LOPO set "
        "accuracy): "
        + ", ".join(f"{k} {v:.3f}" for k, v in ctx["baselines"].items() if k in ctx["models"])
        + ".",
        "",
    ]
    L += ["### Per-class recall (window level)", ""]
    rec_rows = []
    for (m, pname), r in ctx["rows"].items():
        for c, v in r["window"]["recall"].items():
            note = "" if v["n_sets"] >= 2 else " (insufficient data)"
            rec_rows.append(
                [m, pname, c, f"{_fmt(v['recall'])}{note}", v["n_sets"], v["n_windows"]]
            )
    L += (
        [
            md_table(
                pd.DataFrame(
                    rec_rows, columns=["model", "profile", "class", "recall", "sets", "windows"]
                )
            ),
            "",
        ]
        if rec_rows
        else ["No lift sets.", ""]
    )

    # bench / ohp
    L += ["## bench <-> OHP confusion", ""]
    b_rows = []
    for (m, pname), r in ctx["rows"].items():
        for k, v in r["bench_ohp"].items():
            b_rows.append(
                [
                    m,
                    pname,
                    k.replace("_as_", " -> "),
                    f"{v['windows']}/{v['n_windows']} ({_fmt(v['window_rate'], 2)})",
                    f"{v['sets']}/{v['n_sets']}",
                ]
            )
    L += [
        md_table(
            pd.DataFrame(
                b_rows, columns=["model", "profile", "direction", "windows", "sets (vote)"]
            )
        ),
        "",
    ]

    # rejection
    L += ["## Rejection (rest + other not logged as a lift)", ""]
    j_rows = []
    for (m, pname), r in ctx["rows"].items():
        rj = r["rejection"]
        j_rows.append(
            [
                m,
                pname,
                f"{rj['rejected']}/{rj['n']}",
                _fmt(rj["rate"]),
                _ci(rj["ci"]) if rj["n"] else "n/a",
                f"rest {rj['rest']['rejected']}/{rj['rest']['n']}, other {rj['other']['rejected']}/{rj['other']['n']}",
            ]
        )
    L += [
        md_table(
            pd.DataFrame(
                j_rows, columns=["model", "profile", "rejected", "rate", "95% CI", "split"]
            )
        ),
        "",
    ]

    # reps
    L += ["## Rep counter (confirmed sets, true exercise)", ""]
    seen, p_rows = set(), []
    for (m, pname), r in ctx["rows"].items():
        if pname in seen:
            continue
        seen.add(pname)
        ov = r["reps"]["overall"]
        if ov is None:
            p_rows.append([pname, "overall", 0, "n/a", "n/a", "n/a"])
            continue
        p_rows.append(
            [
                pname,
                "overall",
                ov["n"],
                _fmt(ov["mae"], 2),
                _fmt(ov["exact"], 2),
                _fmt(ov["within1"], 2),
            ]
        )
        for e, s in r["reps"]["per_exercise"].items():
            p_rows.append(
                [pname, e, s["n"], _fmt(s["mae"], 2), _fmt(s["exact"], 2), _fmt(s["within1"], 2)]
            )
    L += [
        md_table(pd.DataFrame(p_rows, columns=["profile", "scope", "sets", "MAE", "exact", "+-1"])),
        "",
    ]

    # diagnostics
    d = ctx["diag"]
    L += [
        "## Domain diagnostics (not gates)",
        "",
        "### Top-10 feature PSI (MetaMotion vs watch, fitted profile)",
        "",
    ]
    L += [md_table(d["psi"].round(3)), ""]
    dc = d["domain"]
    L += [
        f"### Domain classifier: AUC {_fmt(dc['auc'], 3)} ({'above 0.8: investigate features' if dc['investigate'] else 'at or below 0.8'}); "
        f"watch grouped by {dc['watch_grouping']}; {dc['n_folds_used']} folds",
        "",
        "Top contributing features (standardised coefficient): "
        + ", ".join(f"`{f}` {c:+.2f}" for f, c in dc["top_features"]),
        "",
        "### Mean gravity vector per exercise (g)",
        "",
    ]
    g_rows = [
        [g["label"], _vec(g["watch_frame"]), _vec(g["watch_after_R"]), _vec(g["metamotion"])]
        for g in d["gravity"]
    ]
    L += [
        md_table(
            pd.DataFrame(g_rows, columns=["label", "watch (R=I)", "watch after R*", "MetaMotion"])
        ),
        "",
    ]

    # quality
    L += ["## Recording quality", ""]
    q_rows = [
        [
            q["session_id"][:8],
            q["kind"],
            _fmt(q["duration_s"], 0),
            _fmt(q["rate_hz"], 2),
            _fmt(q["dt_p99_ms"], 1),
            q["gaps_gt_100ms"],
            _fmt(q["clock_drift_ms"], 2),
            _fmt(100 * q["acc_frac_above_1p9g"], 2),
        ]
        for q in ctx["quality"]
    ]
    L += [
        md_table(
            pd.DataFrame(
                q_rows,
                columns=[
                    "session",
                    "kind",
                    "dur s",
                    "rate Hz",
                    "dt p99 ms",
                    "gaps>100ms",
                    "drift ms",
                    "% >1.9 g",
                ],
            )
        ),
        "",
    ]
    if ctx["eval_skipped"]:
        L += [
            f"Segments skipped at ingest: {len(ctx['eval_skipped'])} ({', '.join(f'{s}: {r}' for s, r in ctx['eval_skipped'][:5])}).",
            "",
        ]
    return "\n".join(L).rstrip() + "\n"


def _vec(v):
    return "(" + ", ".join(f"{x:+.2f}" for x in v) + ")"


# --------------------------------------------------------------------------- CLI


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    p.add_argument("--fit", default=None, help="dataset that fits R and tunes tau")
    p.add_argument("--eval", dest="eval_", default=None, help="held-out dataset (the verdict)")
    p.add_argument(
        "--in-sample", default=None, help="fit and evaluate on this dataset (optimistic)"
    )
    p.add_argument("--write-profile", action="store_true", help=f"write {FITTED_PROFILE_ID}.yaml")
    p.add_argument("--models", default=",".join(DEFAULT_MODELS), help="comma list of M1..M4")
    p.add_argument("--tau", type=float, default=None, help="override the tau selection")
    p.add_argument("--tau-default", type=float, default=M.DEFAULT_TAU)
    p.add_argument("--calib", default="watch-calib-v1")
    p.add_argument("--baseline", action="append", default=[], metavar="M1=0.986")
    p.add_argument("--tracking-uri", default=None)
    p.add_argument("--report-path", default=None)
    p.add_argument("--metrics-out", default=None, help="flat JSON (DVC metrics)")
    p.add_argument("--datasets-file", default=None)
    p.add_argument("--watch-root", default=None)
    return p.parse_args(argv)


def main(argv=None) -> int:
    args = parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    if args.in_sample:
        fit, eval_, in_sample = args.in_sample, args.in_sample, True
    elif args.fit and args.eval_:
        fit, eval_, in_sample = args.fit, args.eval_, False
    else:
        raise SystemExit("give --fit and --eval, or --in-sample")
    baselines = {k: float(v) for k, v in (b.split("=") for b in args.baseline)}
    res = run(
        fit,
        eval_,
        in_sample=in_sample,
        models=[m.strip() for m in args.models.split(",") if m.strip()],
        tau=args.tau,
        tau_default=args.tau_default,
        write_profile=args.write_profile,
        calib=args.calib,
        tracking_uri=args.tracking_uri,
        report_path=args.report_path,
        metrics_out=args.metrics_out,
        datasets_file=args.datasets_file,
        watch_root=args.watch_root,
        baselines=baselines,
    )
    if res["status"] != "ok":
        if args.metrics_out:
            Path(args.metrics_out).parent.mkdir(parents=True, exist_ok=True)
            Path(args.metrics_out).write_text(
                json.dumps({"status": res["status"]}, indent=2) + "\n", encoding="utf-8"
            )
        return 0
    print(f"report: {res['report_path']}")
    for key, r in res["rows"].items():
        print(key, {k: v["status"] for k, v in r["gates"].items()})
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
