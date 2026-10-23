"""Train the production candidate on all MetaMotion data and save it for the service.

    python -m proprio_inference.train_model --out models/proprio-classifier [--raw data/raw/MetaMotion]

M1 (KNN, ``basic+derived+pca+temporal``, rotation augmentation D-018) is trained exactly as the
Phase 3 harness's ``ModelBank`` does it; the output directory (``model.joblib`` + ``metadata.json``)
is what ``MODEL_URI`` points at. Metadata: name, version (git SHA + data md5), feature set, classes,
default tau.
"""

from __future__ import annotations

import argparse
import logging
from pathlib import Path

from workout_ml.watch import metrics
from workout_ml.watch.common import load_metamotion_cleaned
from workout_ml.watch.predict import DEFAULT_MODEL_ID, train_bundle


def main(argv=None) -> int:
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("--out", required=True, help="output directory for the model bundle")
    p.add_argument("--raw", default=None, help="MetaMotion raw data dir (default: config)")
    p.add_argument("--model", default=DEFAULT_MODEL_ID, help="harness model id (default M1)")
    p.add_argument("--tau", type=float, default=metrics.DEFAULT_TAU, help="default tau to record")
    p.add_argument("--name", default="proprio-classifier")
    p.add_argument("--version", default=None, help="override the git-sha + data-md5 version")
    args = p.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")

    mm = load_metamotion_cleaned(args.raw)
    bundle = train_bundle(
        mm, model_id=args.model, tau_default=args.tau, name=args.name, version=args.version
    )
    out = bundle.save(Path(args.out))
    print(f"saved {bundle.name} {bundle.version} ({len(bundle.classes)} classes) to {out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
