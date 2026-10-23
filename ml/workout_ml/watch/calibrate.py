"""Fit the Galaxy Watch -> MetaMotion rotation R (weighted Kabsch on per-exercise gravity).

Convention (profiles): ``canonical = R @ device``, so the fit finds R with ``m_e ~ R w_e``
where ``w_e`` is the mean gravity vector (g) of exercise e in the watch frame and ``m_e`` the
same in the MetaMotion frame (PROJECT.md 6.4, spec 3.2).

CLI: ``python -m workout_ml.watch.calibrate --fit watch-fit-v1 [--write-profile] [--out f.json]``
"""

from __future__ import annotations

import argparse
import dataclasses
import datetime as dt
import json
import logging
from dataclasses import dataclass, field
from pathlib import Path

import numpy as np
import pandas as pd
import yaml
from scipy.spatial.transform import Rotation

from workout_ml.sensors import load_profile, profile_with_rotation
from workout_ml.sensors.profiles import PROFILES_DIR, Profile, compute_transform_id
from workout_ml.watch.datasets import dataset_paths, dataset_session_ids

logger = logging.getLogger(__name__)

PRIOR_PROFILE_ID = "galaxy-watch-prior-v0"
FITTED_PROFILE_ID = "galaxy-watch5pro-fit-v1"
RESIDUAL_GATE_G = 0.15
GYRO_R_GATE = 0.8
LEVER_WARN_G = 0.15  # RMS perpendicular component of the gravity set about its weakest axis
GYR_COLS = ["gyr_x", "gyr_y", "gyr_z"]
ACC_COLS = ["acc_x", "acc_y", "acc_z"]


@dataclass
class RotationFit:
    R: np.ndarray
    exercises: list
    weights: dict
    watch_vectors: dict  # watch frame (R = I profile)
    mm_vectors: dict
    residual_rms_g: float
    residual_per_exercise: dict
    angle_from_prior_deg: float
    unconstrained: dict
    singular_values: list
    sigma_min: float
    lever_min_g: float
    gyro_profile_r: float | None
    gyro_variance: dict = field(default_factory=dict)
    warnings: list = field(default_factory=list)
    flags: dict = field(default_factory=dict)

    def summary(self) -> dict:
        """JSON-serialisable summary (for metrics files and MLflow)."""
        return {
            "R": self.R.tolist(),
            "det": float(np.linalg.det(self.R)),
            "exercises": list(self.exercises),
            "weights": self.weights,
            "residual_rms_g": self.residual_rms_g,
            "residual_per_exercise": self.residual_per_exercise,
            "angle_from_prior_deg": self.angle_from_prior_deg,
            "unconstrained": self.unconstrained,
            "singular_values": self.singular_values,
            "sigma_min": self.sigma_min,
            "lever_min_g": self.lever_min_g,
            "gyro_profile_r": self.gyro_profile_r,
            "warnings": list(self.warnings),
            "flags": dict(self.flags),
            "residual_gate_pass": bool(self.residual_rms_g < RESIDUAL_GATE_G),
            "gyro_gate_pass": None
            if self.gyro_profile_r is None
            else bool(self.gyro_profile_r >= GYRO_R_GATE),
        }


def kabsch(A, B, w=None):
    """Weighted Kabsch. Rows of ``A`` (n, 3) map onto rows of ``B``: ``B ~ A @ R.T``.

    Returns ``(R_proper, R_free, singular_values)``: the det = +1 solution, the best orthogonal
    matrix with det free, and the singular values of the cross-covariance ``H = sum w a b^T``.
    """
    A = np.asarray(A, float)
    B = np.asarray(B, float)
    w = np.ones(len(A)) if w is None else np.asarray(w, float)
    H = (A * w[:, None]).T @ B
    U, S, Vt = np.linalg.svd(H)
    R_free = Vt.T @ U.T
    d = np.sign(np.linalg.det(R_free)) or 1.0
    R = Vt.T @ np.diag([1.0, 1.0, d]) @ U.T
    return R, R_free, S


def _residuals(R, A, B):
    return np.linalg.norm(A @ R.T - B, axis=1)


def _wrms(res, w):
    return float(np.sqrt(np.sum(w * res**2) / np.sum(w)))


def exercise_vectors(df: pd.DataFrame, labels=None) -> tuple[dict, dict, dict]:
    """Per label: (mean acc vector over all bins, number of bins, number of sets/segments)."""
    out, nbins, nsets = {}, {}, {}
    for label, g in df.groupby("label"):
        if labels is not None and label not in labels:
            continue
        out[label] = g[ACC_COLS].to_numpy().mean(axis=0)
        nbins[label] = len(g)
        nsets[label] = int(g["set"].nunique())
    return out, nbins, nsets


def _gyro_variances(df: pd.DataFrame, labels, R=None) -> np.ndarray:
    rows = []
    for e in labels:
        g = df.loc[df["label"] == e, GYR_COLS].to_numpy()
        if R is not None:
            g = g @ R.T
        rows.append(g.var(axis=0))
    return np.array(rows)  # (E, 3)


def fit_rotation(mm_df: pd.DataFrame, watch_df: pd.DataFrame, prior_R=None) -> RotationFit:
    """Fit R from MetaMotion CSF data (``mm_df``) and watch data converted with R = I.

    Both frames need ``label``, ``set`` and the six sensor columns. Only ``set``-kind lifts and
    ``rest`` segments of the watch side are used (``other`` is skipped, it has no MM counterpart).
    """
    if prior_R is None:
        prior_R = load_profile(PRIOR_PROFILE_ID).rotation
    if "kind" in watch_df.columns:
        watch_df = watch_df[watch_df["kind"].isin(["set", "rest"])]
    m_all, _, _ = exercise_vectors(mm_df)
    w_all, _, w_sets = exercise_vectors(watch_df)
    common = sorted(set(m_all) & set(w_all))
    if len(common) < 2:
        raise ValueError(
            f"need at least 2 labels present on both sides to fit R, got {common} "
            f"(MetaMotion {sorted(m_all)}, watch {sorted(w_all)})"
        )
    A = np.array([w_all[e] for e in common])
    B = np.array([m_all[e] for e in common])
    weights = np.array([w_sets[e] for e in common], dtype=float)
    R, R_free, S = kabsch(A, B, weights)

    warnings = []
    if len(common) < 4:
        warnings.append(f"only {len(common)} labels on both sides ({', '.join(common)})")
    res = _residuals(R, A, B)
    rms = _wrms(res, weights)
    res_free = _residuals(R_free, A, B)
    det_free = float(np.linalg.det(R_free))
    flags = {"mirror_preferred": bool(det_free < 0)}
    if flags["mirror_preferred"]:
        warnings.append(
            "unconstrained fit prefers det = -1 (mirror): contradicts the left-wrist "
            "assumption (D-006); ESCALATE to the owner"
        )
    # conditioning: how well-determined is R about its weakest axis?
    C = (B * weights[:, None]).T @ B / weights.sum()  # weighted second moment, g^2
    lam = np.linalg.eigvalsh(C)
    levers = np.sqrt(np.maximum(lam.sum() - lam, 0.0))  # RMS component perpendicular to each axis
    lever_min = float(levers.min())
    if lever_min < LEVER_WARN_G:
        warnings.append(
            f"poorly conditioned: gravity directions are near-collinear (weakest-axis lever "
            f"{lever_min:.3f} g < {LEVER_WARN_G} g); R is poorly determined about that axis"
        )
    if rms >= RESIDUAL_GATE_G:
        warnings.append(f"Kabsch residual {rms:.3f} g >= {RESIDUAL_GATE_G} g: left-wrist suspect")

    angle = float(
        np.degrees((Rotation.from_matrix(prior_R).inv() * Rotation.from_matrix(R)).magnitude())
    )

    gyro_r, gyro_var = None, {}
    if len(common) >= 2:
        v_w = _gyro_variances(watch_df, common, R)
        v_m = _gyro_variances(mm_df, common)
        if np.std(v_w) > 0 and np.std(v_m) > 0:
            gyro_r = float(np.corrcoef(v_w.ravel(), v_m.ravel())[0, 1])
        gyro_var = {"watch_after_R": v_w.tolist(), "metamotion": v_m.tolist(), "labels": common}

    return RotationFit(
        R=R,
        exercises=common,
        weights={e: float(wt) for e, wt in zip(common, weights)},
        watch_vectors={e: w_all[e].tolist() for e in common},
        mm_vectors={e: m_all[e].tolist() for e in common},
        residual_rms_g=rms,
        residual_per_exercise={e: float(r) for e, r in zip(common, res)},
        angle_from_prior_deg=angle,
        unconstrained={
            "R": R_free.tolist(),
            "det": det_free,
            "residual_rms_g": _wrms(res_free, weights),
        },
        singular_values=[float(s) for s in S],
        sigma_min=float(S.min()),
        lever_min_g=lever_min,
        gyro_profile_r=gyro_r,
        gyro_variance=gyro_var,
        warnings=warnings,
        flags=flags,
    )


# --------------------------------------------------------------------------- profiles


def identity_profile(prior: Profile | None = None) -> Profile:
    """Units-and-emulation-only profile (the Galaxy prior with R = I): watch-frame vectors."""
    prior = prior or load_profile(PRIOR_PROFILE_ID)
    return profile_with_rotation(prior, np.eye(3), profile_id=f"{prior.profile_id}-identity")


def fitted_profile(fit: RotationFit, fit_sessions=(), prior: Profile | None = None) -> Profile:
    """In-memory fitted profile (same content as the YAML ``write_fitted_profile`` writes)."""
    prior = prior or load_profile(PRIOR_PROFILE_ID)
    new = dataclasses.replace(
        prior,
        profile_id=FITTED_PROFILE_ID,
        rotation=np.asarray(fit.R),
        rotation_status="fitted",
        device={**prior.device, "model": "SM-R920"},
        calibration=_calibration_block(fit, fit_sessions, prior.profile_id),
        transform_id="",
    )
    object.__setattr__(new, "transform_id", compute_transform_id(new))
    return new


def _calibration_block(fit, fit_sessions, prior_id):
    return {
        "date": dt.date.today().isoformat(),
        "fit_sessions": [str(s) for s in fit_sessions],
        "residual_g": round(fit.residual_rms_g, 5),
        "gyro_profile_r": None if fit.gyro_profile_r is None else round(fit.gyro_profile_r, 4),
        "angle_from_prior_deg": round(fit.angle_from_prior_deg, 3),
        "prior_profile": prior_id,
    }


def write_fitted_profile(fit: RotationFit, fit_sessions=(), path=None, prior_id=PRIOR_PROFILE_ID):
    """Write ``galaxy-watch5pro-fit-v1.yaml`` (CLI ``--write-profile`` only; never from tests
    without an explicit ``path``). Returns the path."""
    path = Path(path or PROFILES_DIR / f"{FITTED_PROFILE_ID}.yaml")
    with open(PROFILES_DIR / f"{prior_id}.yaml", encoding="utf-8") as f:
        raw = yaml.safe_load(f)
    raw["profile_id"] = FITTED_PROFILE_ID if path.stem == FITTED_PROFILE_ID else path.stem
    raw["device"] = {**(raw.get("device") or {}), "model": "SM-R920"}
    raw["rotation"] = [[float(v) for v in row] for row in fit.R]
    raw["rotation_status"] = "fitted"
    raw["calibration"] = _calibration_block(fit, fit_sessions, prior_id)
    header = (
        "# FITTED by workout_ml.watch.calibrate (weighted Kabsch, det = +1; spec phase3-domain-shift.md).\n"
        "# canonical = R @ device. Do not edit by hand: re-run the fit.\n"
    )
    path.write_text(header + yaml.safe_dump(raw, sort_keys=False), encoding="utf-8")
    return path


# --------------------------------------------------------------------------- CLI


def main(argv=None) -> int:
    from workout_ml.watch.common import load_metamotion_cleaned, watch_frame

    p = argparse.ArgumentParser(description="Fit the watch->MetaMotion rotation R (Kabsch).")
    p.add_argument("--fit", default="watch-fit-v1", help="dataset name")
    p.add_argument("--write-profile", action="store_true", help=f"write {FITTED_PROFILE_ID}.yaml")
    p.add_argument("--out", default=None, help="write the fit summary as JSON")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(message)s")

    result: dict
    if not dataset_paths(args.fit):
        print(f"no gym sessions yet ({args.fit} is empty); nothing to fit")
        result = {"status": "no gym sessions yet", "dataset": args.fit}
    else:
        mm = load_metamotion_cleaned()
        watch = watch_frame(args.fit, identity_profile())
        fit = fit_rotation(mm, watch)
        result = {"status": "fitted", "dataset": args.fit, **fit.summary()}
        print(
            json.dumps(
                {
                    k: result[k]
                    for k in ("status", "residual_rms_g", "angle_from_prior_deg", "warnings")
                },
                indent=2,
            )
        )
        if args.write_profile:
            out = write_fitted_profile(fit, dataset_session_ids(args.fit))
            print(f"wrote {out}")
    if args.out:
        Path(args.out).parent.mkdir(parents=True, exist_ok=True)
        Path(args.out).write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
