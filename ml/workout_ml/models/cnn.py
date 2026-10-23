"""PyTorch 1D-CNN exercise classifier on CSF-1 data (Phase 4, task C1).

Same leakage-safe protocol and metrics as the classical models (``models/lopo.py``):
leave-one-participant-out over the cleaned 200 ms bins, window-level accuracy / macro-F1 /
per-class recall, set-level accuracy by majority vote. Differences, all documented in the
generated report:

- Unit of prediction: a *window* of W = 20 bins (4 s) ending at a bin, stride 1, built within
  one set only. The classical pipeline predicts one row per bin from rolling/FFT features;
  here the first W - 1 bins of each set have no prediction, so the CNN is scored on fewer
  rows (``n_sets * (W - 1)`` fewer). Set-level accuracy uses the same majority vote.
- Channels: the six raw axes plus ``acc_r`` and ``gyr_r`` (computed *after* augmentation),
  standardised with mean/std from the training participants of the fold only.
- Early stopping: on macro-F1 of one held-out *training* participant (chosen
  deterministically, never the test participant).
- Training augmentation (D-018 distribution): every training window gets a fresh random
  proper rotation (axis uniform on the sphere, angle U[0, 30] deg) applied to the acc and gyro
  triples, on the fly. Validation and test windows are never augmented.

CLI: ``python -m workout_ml.models.cnn [--no-augment | --both] [--epochs N] [--quick]``
Needs the optional ``dl`` extra (torch, onnx, onnxruntime); see CLAUDE.md.
"""

from __future__ import annotations

import argparse
import copy
import json
import logging
import os
import re
import tempfile
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import matplotlib

matplotlib.use("Agg")  # headless

import matplotlib.pyplot as plt  # noqa: E402
import mlflow  # noqa: E402
import numpy as np  # noqa: E402
import pandas as pd  # noqa: E402
import torch  # noqa: E402
from sklearn.metrics import confusion_matrix, f1_score  # noqa: E402
from torch import nn  # noqa: E402

from workout_ml import config, reporting, tracking  # noqa: E402
from workout_ml.models import lopo  # noqa: E402

logger = logging.getLogger(__name__)

RAW_COLUMNS = lopo.RAW_COLUMNS
CHANNELS = [*RAW_COLUMNS, "acc_r", "gyr_r"]
CNN_EXPERIMENT = "proprio-cnn"
CNN_REPORT_PATH = config.PROJECT_ROOT / "docs" / "reports" / "phase4-cnn-baseline.md"


@dataclass(frozen=True)
class CnnConfig:
    window: int = 20  # bins (4 s at 5 Hz)
    stride: int = 1
    channels: tuple = (32, 64, 64)
    kernel: int = 5
    dropout: float = 0.3
    lr: float = 1e-3
    weight_decay: float = 1e-2  # AdamW default
    batch_size: int = 128
    max_epochs: int = 40
    patience: int = 8
    aug_max_deg: float = 30.0
    seed: int = 42
    threads: int = 4


# --------------------------------------------------------------------------- windows
@dataclass
class Windows:
    """Sliding windows of raw bins. ``x``: (N, W, 6) float32; the rest are length-N arrays."""

    x: np.ndarray
    y: np.ndarray  # string labels
    participant: np.ndarray
    set_id: np.ndarray
    end_pos: np.ndarray  # position of the window's last bin within its set


def build_windows(df, window, stride=1):
    """Windows within each set only; the label is the set's label.

    ``df``: cleaned CSF frame (6 sensor columns, ``participant``, ``label``, ``set``) in time
    order. A window covers bins ``[e - W + 1, e]`` and predicts bin ``e``; sets shorter than
    ``window`` yield none. Windows never span two sets.
    """
    xs, ys, ps, ss, es = [], [], [], [], []
    for set_id, g in df.groupby("set", sort=True):
        arr = g[RAW_COLUMNS].to_numpy(dtype=np.float32)
        if len(arr) < window:
            continue
        w = np.lib.stride_tricks.sliding_window_view(arr, window, axis=0)  # (n, 6, W)
        w = np.ascontiguousarray(w[::stride].transpose(0, 2, 1))  # (n, W, 6)
        n = len(w)
        xs.append(w)
        ys.append(np.full(n, g["label"].iloc[0]))
        ps.append(np.full(n, g["participant"].iloc[0]))
        ss.append(np.full(n, set_id))
        es.append(np.arange(n) * stride + window - 1)
    if not xs:
        raise ValueError("No set is long enough for the window size")
    return Windows(
        np.concatenate(xs), np.concatenate(ys), np.concatenate(ps), np.concatenate(ss),
        np.concatenate(es),
    )  # fmt: skip


def select(win, mask):
    return Windows(win.x[mask], win.y[mask], win.participant[mask], win.set_id[mask],
                   win.end_pos[mask])  # fmt: skip


# --------------------------------------------------------------------------- channels / norm
def to_channels(x):
    """(B, W, 6) raw -> (B, W, 8): append acc_r and gyr_r (magnitudes of each triple)."""
    acc_r = torch.linalg.norm(x[..., 0:3], dim=-1, keepdim=True)
    gyr_r = torch.linalg.norm(x[..., 3:6], dim=-1, keepdim=True)
    return torch.cat([x, acc_r, gyr_r], dim=-1)


def fit_norm(train_bins):
    """Per-channel mean/std of the 8 channels from raw bins ``(n, 6)`` of training data only."""
    ch = to_channels(torch.tensor(np.asarray(train_bins), dtype=torch.float32)[None])[0].numpy()
    std = ch.std(axis=0)
    return {
        "channels": CHANNELS,
        "mean": ch.mean(axis=0).astype(float).tolist(),
        "std": np.where(std > 1e-8, std, 1.0).astype(float).tolist(),
    }


def training_bins(df, participants):
    """Raw bins (n, 6) of the given participants' sets (each bin once)."""
    return df.loc[df["participant"].isin(list(participants)), RAW_COLUMNS].to_numpy(np.float32)


def preprocess(x_raw, norm):
    """(B, W, 6) raw -> (B, 8, W) standardised network input."""
    ch = to_channels(x_raw)
    mean = torch.as_tensor(norm["mean"], dtype=torch.float32)
    std = torch.as_tensor(norm["std"], dtype=torch.float32)
    return ((ch - mean) / std).transpose(1, 2).contiguous()


# --------------------------------------------------------------------------- augmentation
def random_rotation_matrices(n, max_deg, generator):
    """``n`` proper rotations: axis uniform on the sphere, angle U[0, max_deg] (D-018)."""
    axis = torch.randn(n, 3, generator=generator, dtype=torch.float64)
    axis = axis / axis.norm(dim=1, keepdim=True)
    theta = torch.rand(n, generator=generator, dtype=torch.float64) * np.deg2rad(max_deg)
    zero = torch.zeros(n, dtype=torch.float64)
    K = torch.stack(
        [zero, -axis[:, 2], axis[:, 1], axis[:, 2], zero, -axis[:, 0], -axis[:, 1], axis[:, 0],
         zero], dim=1,
    ).reshape(n, 3, 3)  # fmt: skip
    eye = torch.eye(3, dtype=torch.float64).expand(n, 3, 3)
    s = torch.sin(theta)[:, None, None]
    c = (1 - torch.cos(theta))[:, None, None]
    return (eye + s * K + c * (K @ K)).to(torch.float32)  # Rodrigues


def rotate_windows(x, R):
    """Rotate the acc and gyro triples of every window by its own R (v' = R v)."""
    acc = torch.einsum("bij,btj->bti", R, x[..., 0:3])
    gyr = torch.einsum("bij,btj->bti", R, x[..., 3:6])
    return torch.cat([acc, gyr], dim=-1)


# --------------------------------------------------------------------------- model
class CNN1D(nn.Module):
    """3 x (Conv1d -> BatchNorm -> ReLU), max-pool after the first two, GAP, dropout, linear."""

    def __init__(self, in_channels=8, n_classes=6, channels=(32, 64, 64), kernel=5, dropout=0.3):
        super().__init__()
        layers, prev = [], in_channels
        for i, c in enumerate(channels):
            layers += [nn.Conv1d(prev, c, kernel, padding=kernel // 2), nn.BatchNorm1d(c),
                       nn.ReLU()]  # fmt: skip
            if i < len(channels) - 1:
                layers.append(nn.MaxPool1d(2))
            prev = c
        self.features = nn.Sequential(*layers)
        self.dropout = nn.Dropout(dropout)
        self.head = nn.Linear(prev, n_classes)

    def forward(self, x):  # (B, C, W) -> logits (B, n_classes)
        return self.head(self.dropout(self.features(x).mean(dim=-1)))


def make_model(cfg, n_classes):
    return CNN1D(len(CHANNELS), n_classes, cfg.channels, cfg.kernel, cfg.dropout)


# --------------------------------------------------------------------------- training
def choose_val_participant(participants, labels):
    """Deterministic inner-validation participant among the training participants.

    Candidates are participants that are not the *only* holder of any class in the training
    pool (otherwise holding them out would remove a class from training, which is what
    happened with ``rest``: only two participants have it). Among candidates: most distinct
    classes, then most windows, then alphabetically first. Falls back to all participants if
    nobody qualifies.
    """
    participants, labels = np.asarray(participants), np.asarray(labels)
    people = sorted(set(participants))
    held = {p: set(labels[participants == p]) for p in people}

    def sole(p):
        others = set().union(*(held[q] for q in people if q != p)) if len(people) > 1 else set()
        return held[p] - others

    pool = [p for p in people if not sole(p)] or people
    return min(pool, key=lambda p: (-len(held[p]), -int((participants == p).sum()), p))


def seed_everything(seed, threads):
    torch.manual_seed(seed)
    np.random.seed(seed)
    torch.use_deterministic_algorithms(True, warn_only=True)
    torch.set_num_threads(max(1, min(threads, os.cpu_count() or 1)))


def predict(model, x_raw, norm, batch=512):
    """Class indices and logits for raw windows (eval mode, no augmentation)."""
    model.eval()
    out = []
    with torch.no_grad():
        for i in range(0, len(x_raw), batch):
            xb = torch.as_tensor(x_raw[i : i + batch])
            out.append(model(preprocess(xb, norm)))
    logits = torch.cat(out)
    return logits.argmax(1).numpy(), logits


def class_weights(y_idx, n_classes):
    counts = np.bincount(y_idx, minlength=n_classes).astype(float)
    present = counts > 0
    w = np.ones(n_classes)
    w[present] = len(y_idx) / (present.sum() * counts[present])
    return torch.as_tensor(w, dtype=torch.float32)


def _macro_f1(y_true, y_pred):
    return float(f1_score(y_true, y_pred, labels=np.unique(y_true), average="macro",
                          zero_division=0))  # fmt: skip


def train_model(train, val, classes, norm, cfg, *, augment, seed, tag=""):
    """Train with on-the-fly rotation augmentation; early stop on the inner-val macro-F1.

    ``train`` / ``val``: ``Windows``. Returns (best-epoch model, history DataFrame, best epoch).
    """
    seed_everything(seed, cfg.threads)
    cls_idx = {c: i for i, c in enumerate(classes)}
    y_tr = np.array([cls_idx[c] for c in train.y])
    y_va = np.array([cls_idx[c] for c in val.y])
    model = make_model(cfg, len(classes))
    loss_fn = nn.CrossEntropyLoss(weight=class_weights(y_tr, len(classes)))
    opt = torch.optim.AdamW(model.parameters(), lr=cfg.lr, weight_decay=cfg.weight_decay)
    gen = torch.Generator().manual_seed(seed)
    x_tr = torch.as_tensor(train.x)
    y_tr_t = torch.as_tensor(y_tr)
    y_va_t = torch.as_tensor(y_va)
    n = len(x_tr)
    best = (-1.0, float("inf"))
    best_state, best_epoch, stale, rows = None, 0, 0, []
    for epoch in range(1, cfg.max_epochs + 1):
        model.train()
        perm = torch.randperm(n, generator=gen)
        total, seen = 0.0, 0
        for i in range(0, n, cfg.batch_size):
            idx = perm[i : i + cfg.batch_size]
            if len(idx) < 2:  # BatchNorm needs >1 sample
                continue
            xb = x_tr[idx]
            if augment:
                xb = rotate_windows(xb, random_rotation_matrices(len(idx), cfg.aug_max_deg, gen))
            loss = loss_fn(model(preprocess(xb, norm)), y_tr_t[idx])
            opt.zero_grad()
            loss.backward()
            opt.step()
            total += loss.item() * len(idx)
            seen += len(idx)
        pred, logits = predict(model, val.x, norm)
        val_loss = float(loss_fn(logits, y_va_t))
        val_f1 = _macro_f1(y_va, pred)
        rows.append({"epoch": epoch, "train_loss": total / max(seen, 1), "val_loss": val_loss,
                     "val_macro_f1": val_f1})  # fmt: skip
        score = (val_f1, -val_loss)
        if score > (best[0], -best[1]):
            best, best_epoch, stale = (val_f1, val_loss), epoch, 0
            best_state = copy.deepcopy(model.state_dict())
        else:
            stale += 1
            if stale >= cfg.patience:
                break
    model.load_state_dict(best_state)
    model.eval()
    logger.info("%s trained %d epochs, best epoch %d (val macro-F1 %.3f)",
                tag, len(rows), best_epoch, best[0])  # fmt: skip
    return model, pd.DataFrame(rows), best_epoch


# --------------------------------------------------------------------------- LOPO
@dataclass
class CnnLopo:
    folds: pd.DataFrame
    y_true: np.ndarray
    y_pred: np.ndarray
    classes: list
    summary: dict
    curves: pd.DataFrame
    train_seconds: float
    n_windows_scored: int


def evaluate_lopo_cnn(df, classes, cfg, *, augment, quick_log=None):
    """LOPO over the participants of ``df`` (the cleaned frame). Returns ``CnnLopo``."""
    win = build_windows(df, cfg.window, cfg.stride)
    participants = sorted(set(win.participant))
    fold_rows, all_true, all_pred, curves = [], [], [], []
    t0 = time.perf_counter()
    for fold, test_p in enumerate(participants):
        train_all = select(win, win.participant != test_p)
        val_p = choose_val_participant(train_all.participant, train_all.y)
        train = select(train_all, train_all.participant != val_p)
        val = select(train_all, train_all.participant == val_p)
        test = select(win, win.participant == test_p)
        assert test_p not in set(train.participant) | set(val.participant)
        norm = fit_norm(training_bins(df, set(train.participant)))
        model, hist, best_epoch = train_model(
            train, val, classes, norm, cfg, augment=augment, seed=cfg.seed + fold,
            tag=f"fold {fold} (test {test_p}, val {val_p})",
        )  # fmt: skip
        pred_idx, _ = predict(model, test.x, norm)
        y_pred = np.asarray(classes)[pred_idx]
        row = {"fold": fold, "test_participant": test_p, "val_participant": val_p,
               "best_epoch": best_epoch,
               **lopo._fold_metrics(test.y, y_pred, test.set_id, classes)}  # fmt: skip
        fold_rows.append(row)
        all_true.append(test.y)
        all_pred.append(y_pred)
        curves.append(hist.assign(fold=fold, test_participant=test_p))
        if quick_log:
            quick_log(row)
    folds = pd.DataFrame(fold_rows)
    return CnnLopo(
        folds, np.concatenate(all_true), np.concatenate(all_pred), list(classes),
        lopo.summarize_folds(folds, classes), pd.concat(curves, ignore_index=True),
        time.perf_counter() - t0, len(win.y),
    )  # fmt: skip


def train_final(df, classes, cfg, *, augment):
    """Model on all participants (deterministic inner-val participant). -> (model, norm, hist, val_p)"""
    win = build_windows(df, cfg.window, cfg.stride)
    val_p = choose_val_participant(win.participant, win.y)
    train = select(win, win.participant != val_p)
    val = select(win, win.participant == val_p)
    norm = fit_norm(training_bins(df, set(train.participant)))
    model, hist, _ = train_model(train, val, classes, norm, cfg, augment=augment,
                                 seed=cfg.seed + 1000, tag="final")  # fmt: skip
    return model, norm, hist, val_p


# --------------------------------------------------------------------------- ONNX
def export_onnx(model, path, window, n_channels=len(CHANNELS)):
    """Export with a dynamic batch dimension. Input ``input`` (B, 8, W), output ``logits``."""
    model.eval()
    dummy = torch.zeros(2, n_channels, window)
    torch.onnx.export(
        model, (dummy,), str(path), input_names=["input"], output_names=["logits"],
        dynamic_axes={"input": {0: "batch"}, "logits": {0: "batch"}}, dynamo=False,
    )  # fmt: skip
    return Path(path)


def onnx_parity(model, path, window, n=64, seed=0, n_channels=len(CHANNELS)):
    """Max abs logit difference between torch and onnxruntime on fixed random inputs."""
    import onnxruntime as ort

    rng = np.random.default_rng(seed)
    x = rng.normal(size=(n, n_channels, window)).astype(np.float32)
    model.eval()
    with torch.no_grad():
        ref = model(torch.as_tensor(x)).numpy()
    sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
    out = sess.run(["logits"], {"input": x})[0]
    # a different batch size checks the dynamic axis
    out1 = sess.run(["logits"], {"input": x[:1]})[0]
    return float(max(np.abs(out - ref).max(), np.abs(out1 - ref[:1]).max()))


def preprocessing_json(norm, classes, cfg):
    return {
        "window": cfg.window,
        "stride": cfg.stride,
        "sample_rate_hz": 5.0,
        "input": "float32 (batch, 8, window); channels in order below, standardised",
        "recipe": "raw window (window, 6 axes: acc_x,y,z in g; gyr_x,y,z in deg/s, CSF-1 200 ms "
        "bins) -> append acc_r=||acc||, gyr_r=||gyr|| -> (x - mean) / std per channel -> "
        "transpose to (8, window). Prediction refers to the window's last bin.",
        "channels": norm["channels"],
        "mean": norm["mean"],
        "std": norm["std"],
        "classes": list(classes),
    }


# --------------------------------------------------------------------------- reporting
def plot_curves(curves, path, title):
    fig, axes = plt.subplots(1, 2, figsize=(11, 4))
    try:
        for fold, g in curves.groupby("fold"):
            label = f"test {g['test_participant'].iloc[0]}"
            axes[0].plot(g["epoch"], g["train_loss"], label=label)
            axes[1].plot(g["epoch"], g["val_macro_f1"], label=label)
        axes[0].set_title("train loss")
        axes[1].set_title("inner-val macro-F1")
        for ax in axes:
            ax.set_xlabel("epoch")
        axes[1].legend(fontsize=8)
        fig.suptitle(title, fontsize=9)
        fig.tight_layout()
        fig.savefig(path, dpi=120)
    finally:
        plt.close(fig)


def _git_artifacts(tmp):
    """Record git state before artifacts are written."""
    sha, dirty = tracking.git_info()
    p = Path(tmp) / "git_state.json"
    p.write_text(json.dumps({"sha": sha, "dirty": dirty}))
    mlflow.log_artifact(str(p))


def _log_variant(res, final, cfg, classes, name, augment):
    """Log one variant's metrics/artifacts inside the active child run."""
    model, norm, final_hist, final_val_p = final
    mlflow.set_tag("augmentation", "on" if augment else "off")
    mlflow.log_params({
        "architecture": f"CNN1D conv{list(cfg.channels)} k{cfg.kernel} BN ReLU pool2x2 GAP "
        f"dropout{cfg.dropout} linear",
        "window_bins": cfg.window, "stride": cfg.stride, "optimizer": "AdamW", "lr": cfg.lr,
        "weight_decay": cfg.weight_decay, "batch_size": cfg.batch_size,
        "max_epochs": cfg.max_epochs, "patience": cfg.patience, "class_weights": "balanced",
        "aug_enabled": augment, "aug_kind": "rotation, on the fly, per window",
        "aug_max_deg": cfg.aug_max_deg if augment else 0, "seed": cfg.seed,
        "channels": ",".join(CHANNELS), "n_params": sum(p.numel() for p in model.parameters()),
        "final_val_participant": final_val_p, "torch": torch.__version__,
    })  # fmt: skip
    for _, row in res.folds.iterrows():
        m = {"fold_window_acc": row["window_acc"], "fold_window_macro_f1": row["window_macro_f1"],
             "fold_set_acc": row["set_acc"], "fold_best_epoch": row["best_epoch"]}  # fmt: skip
        for c in classes:
            if not np.isnan(row[f"recall_{c}"]):
                m[f"fold_recall_{c}"] = row[f"recall_{c}"]
        mlflow.log_metrics({k: float(v) for k, v in m.items()}, step=int(row["fold"]))
    mlflow.log_metrics({**res.summary, "train_seconds": res.train_seconds})
    cm = confusion_matrix(res.y_true, res.y_pred, labels=classes)
    all_curves = pd.concat(
        [res.curves, final_hist.assign(fold="final", test_participant="none")], ignore_index=True
    )
    with tempfile.TemporaryDirectory() as tmp:
        tmp = Path(tmp)
        _git_artifacts(tmp)
        res.folds.to_csv(tmp / "fold_metrics.csv", index=False)
        all_curves.to_csv(tmp / "training_curves.csv", index=False)
        plot_curves(res.curves, tmp / "training_curves.png", name)
        reporting.plot_confusion(cm, classes, tmp / "confusion_matrix.png",
                                 f"{name}\npooled LOPO confusion (window level)")  # fmt: skip
        pd.DataFrame(cm, index=classes, columns=classes).to_csv(tmp / "confusion_matrix.csv")
        for f in tmp.iterdir():
            mlflow.log_artifact(str(f))

        # final model: torch flavour + ONNX + preprocessing JSON
        mlflow.pytorch.log_model(
            model, name="model", serialization_format="pickle",
            pip_requirements=[f"torch=={torch.__version__}"],
        )
        onnx_dir = tmp / "onnx"
        onnx_dir.mkdir()
        onnx_path = export_onnx(model, onnx_dir / "model.onnx", cfg.window)
        diff = onnx_parity(model, onnx_path, cfg.window)
        (onnx_dir / "preprocessing.json").write_text(
            json.dumps(preprocessing_json(norm, classes, cfg), indent=2)
        )
        mlflow.log_artifacts(str(onnx_dir), artifact_path="onnx")
        mlflow.log_metric("onnx_parity_max_abs_diff", diff)
    return diff


def _parse_classical(path):
    """Best classical row + its bench/OHP confusion counts from ``current-baseline.md``.

    Read, never recomputed. Returns a dict or None when the file is unreadable.
    """
    try:
        text = Path(path).read_text(encoding="utf-8")
    except OSError:
        return None
    row = re.search(
        r"^\| 1 \| (?P<fs>[^|]+) \| (?P<model>[^|]+) \| \d+ \| (?P<wa>[\d.]+) ± (?P<wa_s>[\d.]+) "
        r"\| (?P<f1>[\d.]+) ± (?P<f1_s>[\d.]+) \| (?P<sa>[\d.]+) ± (?P<sa_s>[\d.]+) \|",
        text, re.M,
    )  # fmt: skip
    if not row:
        return None
    out = {k: v.strip() for k, v in row.groupdict().items()}
    cm = {}
    for m in re.finditer(r"^\| (bench|dead|ohp|rest|row|squat) \|((?: \d+ \|){6})$", text, re.M):
        cm[m.group(1)] = [int(v) for v in m.group(2).strip(" |").split("|")]
    cls = ["bench", "dead", "ohp", "rest", "row", "squat"]
    if "bench" in cm and "ohp" in cm:
        out["bench_to_ohp"] = cm["bench"][cls.index("ohp")]
        out["ohp_to_bench"] = cm["ohp"][cls.index("bench")]
        out["bench_n"] = sum(cm["bench"])
        out["ohp_n"] = sum(cm["ohp"])
    return out


def write_report(path, variants, classes, cfg, parent_id, meta, classical_path):
    """Generated ``phase4-cnn-baseline.md``. ``variants``: {name: dict(res, run_id, diff)}."""
    L = ["# Phase 4 baseline: 1D-CNN exercise classifier (PyTorch)", "",
         "Generated by `python -m workout_ml.models.cnn`. Do not edit by hand.", "",
         f"- MLflow experiment `{CNN_EXPERIMENT}`, parent run ID: `{parent_id}`"]  # fmt: skip
    for name, v in variants.items():
        L.append(f"- Child run `{name}`: `{v['run_id']}`")
    L += [f"- Git: `{meta['sha']}` (dirty: {meta['dirty']}); raw data md5 `{meta['md5']}`",
          f"- Sets: {meta['n_sets']}; windows scored: "
          f"{next(iter(variants.values()))['res'].n_windows_scored}",
          f"- torch {torch.__version__}, CPU, {cfg.threads} threads max, seed {cfg.seed}", "",
          "## Setup and how it lines up with the classical pipeline", "",
          f"- Input: cleaned CSF-1 bins (200 ms), windows of W = {cfg.window} bins "
          f"({cfg.window * 0.2:.0f} s), stride {cfg.stride}, built within each set only; the "
          "label is the set label. Channels: 6 raw axes + `acc_r`, `gyr_r` (computed after "
          "augmentation) = 8, standardised with mean/std of the training participants' bins.",
          "- Alignment: the classical models predict one row per 200 ms bin. A CNN window "
          "predicts its **last** bin, so the first W-1 = "
          f"{cfg.window - 1} bins (3.8 s) of every set have no prediction. Window-level metrics "
          "are therefore over `bins - sets * (W-1)` rows, a subset of the classical rows "
          "(the classical numbers are quoted from `current-baseline.md` and were not recomputed "
          "on that subset). Set-level accuracy is the same majority vote.",
          "- Model: 3 x (Conv1d k5 -> BatchNorm -> ReLU) with 32/64/64 channels, max-pool after "
          "the first two, global average pooling, dropout 0.3, linear. "
          f"Params: {meta['n_params']}.",
          f"- Training: AdamW lr {cfg.lr}, class-weighted cross-entropy (training fold), batch "
          f"{cfg.batch_size}, up to {cfg.max_epochs} epochs, early stopping (patience "
          f"{cfg.patience}) on macro-F1 of one held-out *training* participant (never the sole holder of a class "
          "in the training pool, then most classes, then most windows; never the test participant).",
          f"- Augmentation (D-018 distribution): per window, a fresh random proper rotation "
          f"(axis uniform on the sphere, angle U[0, {cfg.aug_max_deg:.0f}] deg) on both the acc "
          "and gyro triples before magnitudes; training windows only.", ""]  # fmt: skip

    L += ["## Comparison (mean ± std over the 5 LOPO folds)", "",
          "| model | window acc | window macro-F1 | set acc |", "| --- | --- | --- | --- |"]  # fmt: skip
    for name, v in variants.items():
        s = v["res"].summary
        L.append(
            f"| CNN ({name}) | {reporting.pm(s['lopo_window_acc_mean'], s['lopo_window_acc_std'])}"
            f" | {reporting.pm(s['lopo_window_macro_f1_mean'], s['lopo_window_macro_f1_std'])}"
            f" | {reporting.pm(s['lopo_set_acc_mean'], s['lopo_set_acc_std'])} |"
        )
    cl = _parse_classical(classical_path)
    if cl:
        L.append(
            f"| classical best ({cl['fs']} / {cl['model']}, augmented, from `current-baseline.md`)"
            f" | {cl['wa']} ± {cl['wa_s']} | {cl['f1']} ± {cl['f1_s']} | {cl['sa']} ± {cl['sa_s']} |"
        )
    else:
        L.append("| classical best | n/a (current-baseline.md not readable) | | |")
    L.append("")

    for name, v in variants.items():
        res = v["res"]
        L += [f"## Fold table: CNN ({name})", ""]
        tab = res.folds[["fold", "test_participant", "val_participant", "best_epoch", "n_sets",
                         "n_windows", "window_acc", "window_macro_f1", "set_acc"]].copy()  # fmt: skip
        s = res.summary
        tab = tab.astype(object)
        tab.loc[len(tab)] = ["mean ± std", "", "", "", "", "",
                             reporting.pm(s["lopo_window_acc_mean"], s["lopo_window_acc_std"]),
                             reporting.pm(s["lopo_window_macro_f1_mean"], s["lopo_window_macro_f1_std"]),
                             reporting.pm(s["lopo_set_acc_mean"], s["lopo_set_acc_std"])]  # fmt: skip
        L += [reporting.md_table(tab), ""]
        rec = res.folds[["test_participant", *[f"recall_{c}" for c in classes]]].copy()
        rec.columns = ["test_participant", *classes]
        L += ["Per-class recall by held-out participant (n/a = class absent):", "",
              reporting.md_table(rec), ""]  # fmt: skip
        cm = confusion_matrix(res.y_true, res.y_pred, labels=classes)
        cmdf = pd.DataFrame(cm, columns=classes)
        cmdf.insert(0, "true \\ predicted", classes)
        L += ["Pooled confusion matrix (window level):", "", reporting.md_table(cmdf), ""]

    if "bench" in classes and "ohp" in classes:
        L += _bench_ohp_section(variants, classes, cl)
    return _finish_report(path, L, variants, cfg, classes)


def _bench_ohp_section(variants, classes, cl):
    L = ["## Bench vs OHP", "",
          "| model | bench -> ohp | ohp -> bench | bench windows | ohp windows |",
          "| --- | --- | --- | --- | --- |"]  # fmt: skip
    for name, v in variants.items():
        res = v["res"]
        cm = confusion_matrix(res.y_true, res.y_pred, labels=classes)
        b, o = classes.index("bench"), classes.index("ohp")
        L.append(f"| CNN ({name}) | {cm[b, o]} | {cm[o, b]} | {cm[b].sum()} | {cm[o].sum()} |")
    if cl and "bench_to_ohp" in cl:
        L.append(f"| classical best ({cl['model']}) | {cl['bench_to_ohp']} | {cl['ohp_to_bench']} "
                 f"| {cl['bench_n']} | {cl['ohp_n']} |")  # fmt: skip
    L.append("")
    return L


def _finish_report(path, L, variants, cfg, classes):
    if len(variants) == 2:
        a, b = (variants[k]["res"].summary for k in variants)
        names = list(variants)
        L += ["## Augmentation on vs off", "",
              f"Window macro-F1: {names[0]} {a['lopo_window_macro_f1_mean']:.3f} vs {names[1]} "
              f"{b['lopo_window_macro_f1_mean']:.3f} (difference "
              f"{a['lopo_window_macro_f1_mean'] - b['lopo_window_macro_f1_mean']:+.3f}); the "
              "table above has the other metrics. With 5 folds and per-fold std of several "
              "points, differences of this size should not be over-read.", ""]  # fmt: skip

    L += ["## Runtime and ONNX", "",
          "| variant | LOPO training time (5 folds, s) | ONNX parity max abs logit diff |",
          "| --- | --- | --- |"]  # fmt: skip
    for name, v in variants.items():
        L.append(f"| {name} | {v['res'].train_seconds:.0f} | {v['diff']:.2e} |")
    L += ["", "The final model of each variant (trained on all participants, inner-val "
          "participant for early stopping) is logged as a torch model and as `onnx/model.onnx` "
          "(dynamic batch) with `onnx/preprocessing.json` (window, channel order, "
          "mean/std, classes) in the child run.", ""]  # fmt: skip
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text("\n".join(L), encoding="utf-8")


# --------------------------------------------------------------------------- orchestration
def run_cnn(df, *, variants=("aug",), cfg=None, tracking_uri=None, artifact_root=None,
            report_path=None, n_sets=None, classical_path=None):  # fmt: skip
    """LOPO + final model for each variant ('aug' / 'noaug'), tracked in MLflow.

    ``df``: cleaned CSF frame. Returns dict with parent run id, per-variant results.
    """
    import mlflow.onnx  # noqa: F401
    import mlflow.pytorch

    cfg = cfg or CnnConfig()
    classes = sorted(df["label"].unique())
    uri = tracking.setup_tracking(tracking_uri, artifact_root, experiment=CNN_EXPERIMENT)
    sha, dirty = tracking.git_info()
    md5 = tracking.data_md5()
    logger.info("MLflow tracking URI: %s", uri)
    out = {}
    with mlflow.start_run(run_name="cnn-lopo", tags={
        "git.sha": sha, "git.dirty": dirty, "data.raw.md5": md5, "phase": "4",
    }) as parent:  # fmt: skip
        mlflow.log_params({"cv": "LOPO", "window_bins": cfg.window, "variants": ",".join(variants),
                           "n_sets": n_sets or df["set"].nunique(), "n_bins": len(df)})  # fmt: skip
        for variant in variants:
            augment = variant == "aug"
            name = "aug" if augment else "no-aug"
            with mlflow.start_run(run_name=f"cnn | {name}", nested=True) as child:
                logger.info("== CNN %s ==", name)
                res = evaluate_lopo_cnn(df, classes, cfg, augment=augment)
                final = train_final(df, classes, cfg, augment=augment)
                diff = _log_variant(res, final, cfg, classes, f"CNN {name}", augment)
            out[name] = {"res": res, "run_id": child.info.run_id, "diff": diff,
                         "n_params": sum(p.numel() for p in final[0].parameters())}  # fmt: skip
        parent_id = parent.info.run_id
        if report_path:
            meta = {"sha": sha, "dirty": dirty, "md5": md5,
                    "n_sets": n_sets or df["set"].nunique(),
                    "n_params": next(iter(out.values()))["n_params"]}  # fmt: skip
            write_report(report_path, out, classes, cfg, parent_id, meta,
                         classical_path or config.REPORT_PATH)  # fmt: skip
    return {"parent_run_id": parent_id, "variants": out}


def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawTextHelpFormatter)
    g = p.add_mutually_exclusive_group()
    g.add_argument("--no-augment", action="store_true", help="train without rotation augmentation")
    g.add_argument("--both", action="store_true", help="run augmented and non-augmented variants")
    p.add_argument("--epochs", type=int, default=None, help="max epochs (default 40)")
    p.add_argument("--quick", action="store_true", help="smoke mode: 2 epochs, patience 1")
    p.add_argument("--tracking-uri", default=None)
    p.add_argument("--report", default=None, help="report path (default: docs/reports/ if --both)")
    return p.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    logging.basicConfig(level=logging.INFO,
                        format="%(asctime)s - %(name)s - %(levelname)s - %(message)s")  # fmt: skip
    from workout_ml.data.preprocessor import DataLoader, OutlierRemover

    cfg = CnnConfig()
    if args.quick:
        cfg = CnnConfig(max_epochs=2, patience=1)
    if args.epochs:
        cfg = CnnConfig(**{**asdict(cfg), "max_epochs": args.epochs})
    config.ensure_dirs()
    loader = DataLoader(config.RAW_DATA_PATH)
    resampled = loader.load_data()
    oc = config.OUTLIER_CONFIG
    cleaned = OutlierRemover.clean_outliers(
        resampled, config.FEATURE_CONFIG["predictor_columns"], method=oc["method"],
        scope=oc["scope"], C=oc["C"], n=oc["n"],
    )  # fmt: skip
    variants = ("aug", "noaug") if args.both else (("noaug",) if args.no_augment else ("aug",))
    if args.report:
        report = Path(args.report)
    elif args.both and not args.quick:
        report = CNN_REPORT_PATH  # the committed report needs both variants
    else:
        report = config.METRICS_DIR / "cnn-run.md"
    t0 = time.perf_counter()
    out = run_cnn(cleaned, variants=variants, cfg=cfg, tracking_uri=args.tracking_uri,
                  report_path=report, n_sets=cleaned["set"].nunique())  # fmt: skip
    logger.info("MLflow parent run: %s; report: %s", out["parent_run_id"], report)
    logger.info("Completed in %.0f s", time.perf_counter() - t0)
    return out


if __name__ == "__main__":
    main()
